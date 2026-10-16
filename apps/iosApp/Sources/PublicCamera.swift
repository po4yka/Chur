import Foundation
import Darwin
import ImageIO
import UniformTypeIdentifiers

/// The public camera owns only random JPEG pairs in this directory.
struct PublicCameraFiles {
    let root: URL
    static let photoLimit = 16 * 1024 * 1024
    static let previewLimit = 2 * 1024 * 1024

    func photo(id: UUID) -> URL { root.appendingPathComponent(id.uuidString + ".jpg") }
    func preview(for photo: URL) -> URL { photo.deletingPathExtension().appendingPathExtension("preview.jpg") }
    func owns(_ url: URL) -> Bool {
        let path = url.standardizedFileURL
        return path.deletingLastPathComponent() == root.standardizedFileURL
            && path.pathExtension == "jpg"
            && UUID(uuidString: path.deletingPathExtension().lastPathComponent) != nil
    }
    func discard(_ photo: URL) {
        guard owns(photo) else { return }
        try? FileManager.default.removeItem(at: photo)
        try? FileManager.default.removeItem(at: preview(for: photo))
    }
}

enum PublicCameraAlbumError: Error, Equatable { case unavailable, invalidPhoto, full }

/// An unencrypted local album. Only a reviewed capture reaches `save`.
struct PublicCameraAlbum {
    struct Entry { let id: String; let date: Date; let bytes: Int64 }
    let root: URL
    let maxCount: Int
    let maxBytes: Int64

    init(root: URL, maxCount: Int = 100, maxBytes: Int64 = 256 * 1024 * 1024) {
        self.root = Self.canonicalRoot(root)
        self.maxCount = maxCount
        self.maxBytes = maxBytes
    }

    func prepare() throws {
        try checkDirectory(root.deletingLastPathComponent(), allowMissing: true)
        try checkDirectory(root, allowMissing: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try checkRoot()
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: root.path)
        #endif
    }

    // Foundation can change /private/var and /var aliases after creation.
    // Use one POSIX path, including when the album does not exist yet.
    private static func canonicalRoot(_ requested: URL) -> URL {
        let requested = requested.standardizedFileURL
        let publicParent = requested.deletingLastPathComponent()
        var ancestor = publicParent.deletingLastPathComponent()
        // Canonicalize the trusted Documents anchor, while keeping both
        // public/camera unresolved so neither can redirect album access.
        var missing = [requested.lastPathComponent, publicParent.lastPathComponent]
        while true {
            if let resolved = realpath(ancestor.path, nil) {
                defer { free(resolved) }
                let existing = URL(fileURLWithPath: String(cString: resolved), isDirectory: true)
                return missing.reversed().reduce(existing) { $0.appendingPathComponent($1, isDirectory: true) }
            }
            guard ancestor.path != "/" else { return requested.standardizedFileURL }
            missing.append(ancestor.lastPathComponent)
            ancestor.deleteLastPathComponent()
        }
    }

    private func checkRoot() throws {
        try checkDirectory(root.deletingLastPathComponent())
        try checkDirectory(root)
    }

    private func checkDirectory(_ directory: URL, allowMissing: Bool = false) throws {
        var status = stat()
        guard lstat(directory.path, &status) == 0 else {
            if allowMissing && errno == ENOENT { return }
            throw PublicCameraAlbumError.unavailable
        }
        guard status.st_mode & S_IFMT == S_IFDIR,
              let resolved = realpath(directory.path, nil) else { throw PublicCameraAlbumError.unavailable }
        defer { free(resolved) }
        guard String(cString: resolved) == directory.path else { throw PublicCameraAlbumError.unavailable }
    }

    private func url(_ id: String) throws -> URL {
        guard let uuid = UUID(uuidString: id), uuid.uuidString == id else { throw PublicCameraAlbumError.invalidPhoto }
        return root.appendingPathComponent(id + ".jpg")
    }

    func entries() throws -> [Entry] {
        try checkRoot()
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .creationDateKey]).compactMap { file in
            let id = file.deletingPathExtension().lastPathComponent
            guard file.pathExtension == "jpg", (try? url(id))?.lastPathComponent == file.lastPathComponent else { return nil }
            let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .creationDateKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true,
                  let bytes = values.fileSize, bytes > 0, bytes <= PublicCameraFiles.photoLimit else { return nil }
            return Entry(id: id, date: values.creationDate ?? .distantPast, bytes: Int64(bytes))
        }.sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
    }

    @discardableResult func save(_ source: URL) throws -> String {
        try prepare()
        let bytes = try Self.read(source, limit: PublicCameraFiles.photoLimit)
        try Self.validateJpeg(bytes)
        let existing = try entries()
        let used = existing.reduce(Int64(0)) { $0 + $1.bytes }
        guard existing.count < maxCount, Int64(bytes.count) <= maxBytes,
              used <= maxBytes - Int64(bytes.count) else { throw PublicCameraAlbumError.full }
        let id = UUID().uuidString
        let destination = try url(id)
        #if os(iOS)
        try bytes.write(to: destination, options: [.atomic, .completeFileProtection])
        #else
        try bytes.write(to: destination, options: .atomic)
        #endif
        do { try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path) }
        catch { try? FileManager.default.removeItem(at: destination); throw error }
        return id
    }

    func read(_ id: String) throws -> Data {
        try checkRoot()
        let bytes = try Self.read(url(id), limit: PublicCameraFiles.photoLimit)
        try Self.validateJpeg(bytes)
        return bytes
    }

    func delete(_ id: String) throws {
        try checkRoot()
        let file = try url(id)
        let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else { throw PublicCameraAlbumError.invalidPhoto }
        try FileManager.default.removeItem(at: file)
    }

    private static func read(_ file: URL, limit: Int) throws -> Data {
        let fd = Darwin.open(file.path, O_RDONLY | O_NOFOLLOW)
        guard fd >= 0 else { throw PublicCameraAlbumError.unavailable }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var status = stat()
        guard fstat(fd, &status) == 0, status.st_mode & S_IFMT == S_IFREG,
              status.st_size > 0, status.st_size <= Int64(limit) else { throw PublicCameraAlbumError.invalidPhoto }
        let data = try handle.read(upToCount: limit + 1) ?? Data()
        guard !data.isEmpty, data.count <= limit, Int64(data.count) == status.st_size else { throw PublicCameraAlbumError.invalidPhoto }
        return data
    }

    private static func validateJpeg(_ bytes: Data) throws {
        guard let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1, CGImageSourceGetType(source) as String? == UTType.jpeg.identifier,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              (1...4096).contains(width.intValue), (1...4096).contains(height.intValue) else {
            throw PublicCameraAlbumError.invalidPhoto
        }
    }
}

#if canImport(UIKit)
import AVFoundation
import ChurApp
import UIKit

/// A public camera is independent of private imports, sessions, and exports.
/// Permission callbacks may prepare a photo, but only an explicit Save may publish it.
final class PublicCamera: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    typealias CaptureAnswer = (String?, String?, String?) -> KotlinUnit
    typealias SaveAnswer = (KotlinBoolean, String?) -> KotlinUnit
    private let files = PublicCameraFiles(root: FileManager.default.temporaryDirectory.appendingPathComponent("chur-public-camera", isDirectory: true))
    private let album = PublicCameraAlbum(root: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("public/camera", isDirectory: true))
    private let presenter: () -> UIViewController?
    private let beginPrompt: () -> Int64
    private let endPrompt: (Int64) -> Bool
    private var permissionPrompt: Int64?
    private var generation = 0
    private var captureAnswer: CaptureAnswer?
    private weak var picker: UIImagePickerController?
    private var captures = Set<URL>()
    private var albumPreview: URL?

    init(presenter: @escaping () -> UIViewController?, beginPrompt: @escaping () -> Int64, endPrompt: @escaping (Int64) -> Bool) {
        self.presenter = presenter
        self.beginPrompt = beginPrompt
        self.endPrompt = endPrompt
        super.init()
    }

    func prepare() -> Bool {
        do {
            if FileManager.default.fileExists(atPath: files.root.path) { try FileManager.default.removeItem(at: files.root) }
            try FileManager.default.createDirectory(at: files.root, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete, .posixPermissions: 0o700])
            try (files.root as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
            try album.prepare()
            return true
        } catch { return false }
    }

    func capture(_ answer: @escaping CaptureAnswer) {
        guard captureAnswer == nil, captures.isEmpty else { _ = answer(nil, nil, "Finish the current photo first."); return }
        clearAlbumPreview()
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            _ = answer(nil, nil, "Camera is unavailable on this device. Use an iPhone or iPad with a camera."); return
        }
        captureAnswer = answer
        let token = generation
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: showCamera(token: token)
        case .notDetermined:
            permissionPrompt = beginPrompt()
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self, token == self.generation else { return }
                    guard self.finishPermissionPrompt() else { self.finishCapture(message: "Photo capture cancelled."); return }
                    if granted { self.showCamera(token: token) }
                    else { self.finishCapture(message: "Camera access was denied. Enable Camera in iOS Settings and try again.") }
                }
            }
        default: finishCapture(message: "Camera access is unavailable. Enable Camera in iOS Settings and try again.")
        }
    }

    private func showCamera(token: Int) {
        guard token == generation, captureAnswer != nil else { return }
        guard let root = presenter(), root.presentedViewController == nil else { finishCapture(message: "Camera cannot open now. Try again."); return }
        let camera = UIImagePickerController()
        camera.sourceType = .camera
        camera.mediaTypes = [UTType.image.identifier]
        camera.cameraCaptureMode = .photo
        camera.allowsEditing = false
        camera.modalPresentationStyle = .fullScreen
        camera.delegate = self
        picker = camera
        root.present(camera, animated: true)
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        finishCapture(message: "Photo capture cancelled.")
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        guard captureAnswer != nil, let image = info[.originalImage] as? UIImage,
              image.size.width > 0, image.size.height > 0,
              image.size.width * image.scale <= 16_384, image.size.height * image.scale <= 16_384,
              image.size.width * image.size.height * image.scale * image.scale <= 67_108_864 else {
            finishCapture(message: "This photo is too large. Try another capture."); return
        }
        let photo = files.photo(id: UUID())
        let review = files.preview(for: photo)
        do {
            let full = try Self.jpeg(image, maxEdge: 4096, limit: PublicCameraFiles.photoLimit)
            let thumbnail = try Self.jpeg(image, maxEdge: 960, limit: PublicCameraFiles.previewLimit)
            try full.write(to: photo, options: [.atomic, .completeFileProtection])
            try thumbnail.write(to: review, options: [.atomic, .completeFileProtection])
            captures.insert(photo)
            let answer = captureAnswer
            captureAnswer = nil
            closePicker()
            _ = answer?(photo.path, review.path, nil)
        } catch {
            files.discard(photo)
            finishCapture(message: "Photo could not be prepared. Try again.")
        }
    }

    private static func jpeg(_ image: UIImage, maxEdge: CGFloat, limit: Int) throws -> Data {
        let ratio = min(1, maxEdge / max(image.size.width, image.size.height))
        let size = CGSize(width: max(1, (image.size.width * ratio).rounded(.down)), height: max(1, (image.size.height * ratio).rounded(.down)))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let normalized = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let data = normalized.jpegData(compressionQuality: 0.9), !data.isEmpty, data.count <= limit else {
            throw CocoaError(.fileWriteOutOfSpace)
        }
        return data
    }

    func save(_ path: String, answer: @escaping SaveAnswer) {
        let photo = URL(fileURLWithPath: path).standardizedFileURL
        guard files.owns(photo), captures.contains(photo), captureAnswer == nil else {
            _ = answer(KotlinBoolean(bool: false), "Photo is unavailable. Take another photo."); return
        }
        do {
            try album.save(photo)
            discard(path)
            _ = answer(KotlinBoolean(bool: true), "Photo saved to the public Chur album.")
        } catch PublicCameraAlbumError.full {
            _ = answer(KotlinBoolean(bool: false), "The public album is full (100 photos or 256 MiB). Delete a photo, then save again.")
        } catch {
            _ = answer(KotlinBoolean(bool: false), "Photo could not be saved. Try again.")
        }
    }

    func list() -> [PublicCameraPhoto] {
        do {
            return try album.entries().prefix(100).map { PublicCameraPhoto(id: $0.id, label: "Photo " + DateFormatter.localizedString(from: $0.date, dateStyle: .medium, timeStyle: .short)) }
        } catch { return [] }
    }

    func open(_ id: String, answer: (String?, String?) -> KotlinUnit) {
        clearAlbumPreview()
        do {
            let bytes = try album.read(id)
            guard let image = UIImage(data: bytes) else { throw PublicCameraAlbumError.invalidPhoto }
            let thumbnail = try Self.jpeg(image, maxEdge: 960, limit: PublicCameraFiles.previewLimit)
            let photo = files.photo(id: UUID())
            let review = files.preview(for: photo)
            try thumbnail.write(to: review, options: [.atomic, .completeFileProtection])
            albumPreview = review
            _ = answer(review.path, nil)
        } catch { _ = answer(nil, "Photo could not be opened.") }
    }

    func delete(_ id: String) -> String? {
        do { try album.delete(id); clearAlbumPreview(); return nil }
        catch { return "Photo could not be deleted." }
    }

    private func clearAlbumPreview() {
        if let albumPreview { try? FileManager.default.removeItem(at: albumPreview) }
        albumPreview = nil
    }

    func discard(_ path: String) {
        let photo = URL(fileURLWithPath: path).standardizedFileURL
        guard files.owns(photo), captures.contains(photo) else { return }
        captures.remove(photo)
        files.discard(photo)
    }

    func cancel() {
        generation += 1
        _ = finishPermissionPrompt()
        finishCapture(message: "Photo capture cancelled.")
        for photo in Array(captures) { discard(photo.path) }
        clearAlbumPreview()
        IosPublicCamera.shared.didCancel()
    }

    private func finishPermissionPrompt() -> Bool {
        guard let prompt = permissionPrompt else { return true }
        permissionPrompt = nil
        return endPrompt(prompt)
    }

    private func finishCapture(message: String) {
        let answer = captureAnswer
        captureAnswer = nil
        closePicker()
        _ = answer?(nil, nil, message)
    }
    private func closePicker() {
        picker?.delegate = nil
        picker?.dismiss(animated: false)
        picker = nil
    }
}
#endif
