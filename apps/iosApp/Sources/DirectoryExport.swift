import Darwin
import Foundation

/// One transient grant. No URL or bookmark is passed to the shared application.
final class DirectoryExportSession: @unchecked Sendable {
    private let lock = NSLock()
    private var rootURL: URL?
    private var stopAccess: (() -> Void)?
    private var active = true
    private var operations = 0

    init?(url: URL, startAccess: () -> Bool, stopAccess: @escaping () -> Void) {
        guard startAccess() else { return nil }
        guard let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true, values.isSymbolicLink != true else {
            stopAccess()
            return nil
        }
        rootURL = url
        self.stopAccess = stopAccess
    }

    var directory: DirectoryExportNode? {
        lock.lock(); defer { lock.unlock() }
        guard active, let rootURL else { return nil }
        return DirectoryExportNode(url: rootURL, session: self, ownsSession: true)
    }

    var isActive: Bool {
        lock.lock(); defer { lock.unlock() }
        return active
    }

    fileprivate func acquire() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard active else { return false }
        operations += 1
        return true
    }

    fileprivate func release() {
        lock.lock()
        operations -= 1
        let stop = !active && operations == 0 ? stopAccess : nil
        if stop != nil { stopAccess = nil; rootURL = nil }
        lock.unlock()
        stop?()
    }

    func close() {
        lock.lock()
        active = false
        // An active native writer owns its descriptor until its caller closes
        // it. Keep the scope and coordination lease until that cleanup; closing
        // the fd here could race Rust's dup and reuse an unrelated descriptor.
        let stop = operations == 0 ? stopAccess : nil
        if stop != nil { stopAccess = nil; rootURL = nil }
        lock.unlock()
        stop?()
    }

    deinit { close() }
}

/// A directory within the grant. Every child name is one safe path component.
final class DirectoryExportNode: @unchecked Sendable {
    private var url: URL?
    private let session: DirectoryExportSession
    private let ownsSession: Bool

    fileprivate init(url: URL, session: DirectoryExportSession, ownsSession: Bool = false) {
        self.url = url
        self.session = session
        self.ownsSession = ownsSession
    }

    static func safeName(_ name: String) -> String {
        let forbidden = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:"))
        var clean = String(name.unicodeScalars.map { forbidden.contains($0) ? "_" : String($0) }.joined().prefix(180))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        while clean.utf8.count > 180 { clean.removeLast() }
        return clean.isEmpty || clean == "." || clean == ".." ? "Untitled" : clean
    }

    fileprivate static func candidate(_ name: String, number: Int, file: Bool) -> String {
        guard number > 0 else { return name }
        let component = name as NSString
        let ext = file ? component.pathExtension : ""
        let stem = ext.isEmpty ? name : component.deletingPathExtension
        return ext.isEmpty ? "\(stem) (\(number + 1))" : "\(stem) (\(number + 1)).\(ext)"
    }

    func createDirectory(displayName: String) -> DirectoryExportNode? {
        guard let url, session.acquire() else { return nil }
        let request = DirectoryExportCreation<URL> { _ = Darwin.rmdir($0.path) }
        let coordinator = DirectoryExportCoordinator()
        DispatchQueue.global(qos: .userInitiated).async {
            defer { self.session.release(); request.complete(nil) }
            coordinator.coordinate(at: url) { parent in
                guard self.session.isActive else { return }
                for index in 0..<1000 {
                    let child = parent.appendingPathComponent(Self.candidate(Self.safeName(displayName), number: index, file: false), isDirectory: true)
                    if Darwin.mkdir(child.path, S_IRWXU) == 0 { request.complete(child); return }
                    if errno != EEXIST { return }
                }
            }
        }
        guard let created = request.wait(while: { session.isActive }) else {
            DispatchQueue.global(qos: .utility).async { coordinator.cancel() }
            return nil
        }
        return DirectoryExportNode(url: created, session: session)
    }

    func createFile(displayName: String, plaintextSize: Int64) -> DirectoryExportFile? {
        guard plaintextSize >= 0, let url, session.acquire() else { return nil }
        // Cancellation ends the caller's wait, but the provider worker keeps
        // its scope until coordination drains. Only an undelivered file can
        // be abandoned here; a native writer keeps its fd until close().
        let result = DirectoryExportFile(session: session, displayName: Self.safeName(displayName))
        let request = DirectoryExportCreation<DirectoryExportFile> { $0.abandonBeforeDelivery() }
        let coordinator = DirectoryExportCoordinator()
        DispatchQueue.global(qos: .userInitiated).async {
            defer { result.coordinationFinished(); request.complete(nil) }
            coordinator.coordinate(at: url) { parent in
                guard self.session.isActive else { return }
                let child = parent.appendingPathComponent(".chur-partial-\(UUID().uuidString)")
                let fd = Darwin.open(child.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, S_IRUSR | S_IWUSR)
                guard fd >= 0 else { return }
                if result.open(url: child, descriptor: fd) {
                    request.complete(result)
                    result.awaitCompletion()
                } else {
                    Darwin.close(fd)
                    try? FileManager.default.removeItem(at: child)
                }
            }
        }
        guard let delivered = request.wait(while: { session.isActive }) else {
            DispatchQueue.global(qos: .utility).async { coordinator.cancel() }
            return nil
        }
        return delivered
    }

    func close() {
        url = nil
        if ownsSession { session.close() }
    }
}

/// One worker owns coordination. Foundation explicitly permits cancel()
/// from another thread; no other coordinator operation crosses that boundary.
private final class DirectoryExportCoordinator: @unchecked Sendable {
    private let value = NSFileCoordinator()
    func coordinate(at url: URL, accessor: (URL) -> Void) {
        var error: NSError?
        value.coordinate(writingItemAt: url, options: .forMerging, error: &error, byAccessor: accessor)
    }
    func cancel() { value.cancel() }
}

/// Transfers a creation once. Cancellation can discard only an unclaimed result.
private final class DirectoryExportCreation<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private let ready = DispatchSemaphore(value: 0)
    private let discard: (Value) -> Void
    private var value: Value?
    private var completed = false
    private var cancelled = false
    init(discard: @escaping (Value) -> Void) { self.discard = discard }

    func complete(_ value: Value?) {
        lock.lock()
        if cancelled {
            lock.unlock()
            if let value { discard(value) }
        } else if !completed {
            self.value = value; completed = true
            lock.unlock(); ready.signal()
        } else { lock.unlock() }
    }

    func wait(while active: () -> Bool) -> Value? {
        while active() {
            if ready.wait(timeout: .now() + .milliseconds(50)) == .success {
                lock.lock(); let result = value; value = nil; lock.unlock()
                return result
            }
        }
        lock.lock(); cancelled = true; let abandoned = value; value = nil; lock.unlock()
        if let abandoned { discard(abandoned) }
        return nil
    }
}

final class DirectoryExportFile: @unchecked Sendable {
    private let lock = NSLock()
    private let completion = DispatchSemaphore(value: 0)
    private let coordinated = DispatchSemaphore(value: 0)
    private let session: DirectoryExportSession
    private let displayName: String
    private var url: URL?
    private var identity: DirectoryExportFileIdentity?
    private var fd: Int32 = -1
    private var published = false
    private var closed = false
    private var coordinationComplete = false
    private var scopeReleased = false

    fileprivate init(session: DirectoryExportSession, displayName: String) {
        self.session = session
        self.displayName = displayName
    }

    var descriptor: Int32 { lock.lock(); defer { lock.unlock() }; return fd }

    fileprivate func open(url: URL, descriptor: Int32) -> Bool {
        guard let identity = DirectoryExportFileIdentity(descriptor: descriptor) else { return false }
        lock.lock(); self.url = url; self.identity = identity; fd = descriptor; lock.unlock()
        guard session.isActive else {
            lock.lock(); self.url = nil; self.identity = nil; fd = -1; lock.unlock()
            return false
        }
        return true
    }

    func publishChecked() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !closed, session.isActive, fd >= 0 else { return false }
        if published { return true }
        guard Darwin.fsync(fd) == 0 else { return false }
        guard let url else { return false }
        for index in 0..<1000 {
            let target = url.deletingLastPathComponent().appendingPathComponent(
                DirectoryExportNode.candidate(displayName, number: index, file: true)
            )
            if Darwin.renamex_np(url.path, target.path, UInt32(RENAME_EXCL)) == 0 {
                self.url = target
                published = true
                return true
            }
            if errno != EEXIST { return false }
        }
        return false
    }

    func discard() {
        lock.lock()
        published = false
        // A batch can fail after an earlier file has been published and
        // closed. That file still owns its identity and access lease.
        let rollback = coordinationComplete ? takeRollback() : nil
        let release = coordinationComplete && !scopeReleased
        if release { scopeReleased = true }
        lock.unlock()
        if let rollback { Self.remove(rollback, coordinated: true) }
        if release { session.release() }
    }

    func close() {
        guard closeDescriptor() else { return }
        coordinated.wait()
    }

    fileprivate func abandonBeforeDelivery() { _ = closeDescriptor() }

    private func closeDescriptor() -> Bool {
        lock.lock()
        guard !closed else { lock.unlock(); return false }
        closed = true
        if fd >= 0 { Darwin.close(fd); fd = -1 }
        lock.unlock()
        completion.signal()
        return true
    }

    fileprivate func awaitCompletion() {
        completion.wait()
        lock.lock()
        let rollback = !published ? takeRollback() : nil
        lock.unlock()
        if let rollback { Self.remove(rollback, coordinated: false) }
    }

    fileprivate func coordinationFinished() {
        lock.lock()
        coordinationComplete = true
        // discard() can race the end of the first coordinated accessor.
        let rollback = !published ? takeRollback() : nil
        let release = !published && !scopeReleased
        if release { scopeReleased = true }
        lock.unlock()
        if let rollback { Self.remove(rollback, coordinated: true) }
        if release { session.release() }
        coordinated.signal()
    }

    /// Called under lock. Names alone never establish ownership of an output.
    private func takeRollback() -> (URL, DirectoryExportFileIdentity)? {
        guard let url, let identity else { return nil }
        self.url = nil
        self.identity = nil
        return (url, identity)
    }

    private static func remove(_ output: (URL, DirectoryExportFileIdentity), coordinated: Bool) {
        let removeOwnedFile = {
            if output.1.matches(url: output.0) { _ = Darwin.unlink(output.0.path) }
        }
        if coordinated {
            DirectoryExportCoordinator().coordinate(at: output.0.deletingLastPathComponent()) { _ in removeOwnedFile() }
        } else { removeOwnedFile() }
    }

    deinit {
        // A successful batch keeps published files. Their retained capability
        // ends when the host releases the destination object.
        if !scopeReleased { session.release() }
    }
}

private struct DirectoryExportFileIdentity {
    let device: dev_t
    let inode: ino_t
    let birthSeconds: Int
    let birthNanoseconds: Int

    init?(descriptor: Int32) {
        var value = stat()
        guard Darwin.fstat(descriptor, &value) == 0 else { return nil }
        device = value.st_dev
        inode = value.st_ino
        birthSeconds = value.st_birthtimespec.tv_sec
        birthNanoseconds = value.st_birthtimespec.tv_nsec
    }

    func matches(url: URL) -> Bool {
        var value = stat()
        return Darwin.lstat(url.path, &value) == 0 &&
            value.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG) &&
            value.st_dev == device && value.st_ino == inode &&
            value.st_birthtimespec.tv_sec == birthSeconds && value.st_birthtimespec.tv_nsec == birthNanoseconds
    }
}
