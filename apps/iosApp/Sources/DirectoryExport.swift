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
        defer { session.release() }
        var coordinationError: NSError?
        var created: URL?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forMerging, error: &coordinationError) { parent in
            guard session.isActive else { return }
            for index in 0..<1000 {
                let child = parent.appendingPathComponent(Self.candidate(Self.safeName(displayName), number: index, file: false), isDirectory: true)
                if Darwin.mkdir(child.path, S_IRWXU) == 0 { created = child; return }
                if errno != EEXIST { return }
            }
        }
        guard coordinationError == nil, let created, session.isActive else { return nil }
        return DirectoryExportNode(url: created, session: session)
    }

    func createFile(displayName: String, plaintextSize: Int64) -> DirectoryExportFile? {
        guard plaintextSize >= 0, let url, session.acquire() else { return nil }
        // The accessor stays alive while Rust writes its duplicated descriptor.
        // It closes only after publish/discard and descriptor close, so provider
        // writes remain coordinated without an intermediate plaintext copy.
        let result = DirectoryExportFile(session: session, displayName: Self.safeName(displayName))
        let acquired = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            var error: NSError?
            NSFileCoordinator().coordinate(writingItemAt: url, options: .forMerging, error: &error) { parent in
                guard self.session.isActive else { acquired.signal(); return }
                let child = parent.appendingPathComponent(".chur-partial-\(UUID().uuidString)")
                let fd = Darwin.open(child.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, S_IRUSR | S_IWUSR)
                if fd >= 0 {
                    if result.open(url: child, descriptor: fd) { acquired.signal(); result.awaitCompletion() }
                    else { Darwin.close(fd); try? FileManager.default.removeItem(at: child); acquired.signal() }
                    return
                }
                acquired.signal()
            }
            // Coordination can fail without invoking the accessor.
            if error != nil { acquired.signal() }
            result.coordinationFinished()
        }
        acquired.wait()
        guard result.descriptor >= 0 else { return nil }
        return result
    }

    func close() {
        url = nil
        if ownsSession { session.close() }
    }
}

final class DirectoryExportFile: @unchecked Sendable {
    private let lock = NSLock()
    private let completion = DispatchSemaphore(value: 0)
    private let coordinated = DispatchSemaphore(value: 0)
    private let session: DirectoryExportSession
    private let displayName: String
    private var url: URL?
    private var fd: Int32 = -1
    private var published = false
    private var closed = false

    fileprivate init(session: DirectoryExportSession, displayName: String) {
        self.session = session
        self.displayName = displayName
    }

    var descriptor: Int32 { lock.lock(); defer { lock.unlock() }; return fd }

    fileprivate func open(url: URL, descriptor: Int32) -> Bool {
        lock.lock(); self.url = url; fd = descriptor; lock.unlock()
        guard session.isActive else {
            lock.lock(); self.url = nil; fd = -1; lock.unlock()
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

    func discard() { lock.lock(); published = false; lock.unlock() }

    func close() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        if fd >= 0 { Darwin.close(fd); fd = -1 }
        lock.unlock()
        completion.signal()
        coordinated.wait()
    }

    fileprivate func awaitCompletion() {
        completion.wait()
        lock.lock()
        if !published, let url { try? FileManager.default.removeItem(at: url) }
        url = nil
        lock.unlock()
    }

    fileprivate func coordinationFinished() {
        session.release()
        coordinated.signal()
    }
}
