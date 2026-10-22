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

/// An unencrypted local album. Legacy JPEGs and new captures share the same directory.
struct PublicCameraAlbum {
    struct Entry { let id: String; let date: Date; let bytes: Int64 }
    let root: URL
    let maxCount: Int
    let maxBytes: Int64

    init(root: URL, maxCount: Int = Int.max, maxBytes: Int64 = Int64.max) {
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


extension PublicCameraAlbum {
    struct MediaEntry { let id: String; let date: Date; let video: Bool }
    func mediaURL(_ id: String) throws -> URL {
        try checkRoot()
        let name = (id as NSString).deletingPathExtension
        guard UUID(uuidString: name) != nil, ["jpg", "mp4", "mov"].contains((id as NSString).pathExtension), !id.contains("/") else { throw PublicCameraAlbumError.invalidPhoto }
        let url = root.appendingPathComponent(id)
        var status = stat()
        guard lstat(url.path, &status) == 0, status.st_mode & S_IFMT == S_IFREG else { throw PublicCameraAlbumError.unavailable }
        return url
    }
    func mediaEntries() throws -> [MediaEntry] {
        try checkRoot()
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey]).compactMap { url in
            guard (try? mediaURL(url.lastPathComponent)) != nil else { return nil }
            return MediaEntry(id: url.lastPathComponent, date: (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast, video: ["mp4", "mov"].contains(url.pathExtension))
        }.sorted { $0.date == $1.date ? $0.id < $1.id : $0.date > $1.date }
    }
    func pendingMedia(video: Bool) throws -> URL {
        try prepare()
        let free = try root.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity ?? 0
        guard free > 32 * 1024 * 1024 else { throw PublicCameraAlbumError.full }
        return root.appendingPathComponent(".pending-" + UUID().uuidString + (video ? ".mov" : ".jpg"))
    }
    func commitMedia(_ source: URL) throws {
        try checkRoot()
        let name = source.lastPathComponent
        guard source.deletingLastPathComponent() == root, name.hasPrefix(".pending-"),
              UUID(uuidString: String(source.deletingPathExtension().lastPathComponent.dropFirst(9))) != nil,
              ["jpg", "mp4", "mov"].contains(source.pathExtension) else { throw PublicCameraAlbumError.invalidPhoto }
        var status = stat()
        guard lstat(source.path, &status) == 0, status.st_mode & S_IFMT == S_IFREG, status.st_size > 0 else { throw PublicCameraAlbumError.invalidPhoto }
        if source.pathExtension == "jpg" {
            guard let image = CGImageSourceCreateWithURL(source as CFURL, nil),
                  CGImageSourceGetType(image) as String? == UTType.jpeg.identifier,
                  let properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [CFString: Any],
                  let width = properties[kCGImagePropertyPixelWidth] as? Int,
                  let height = properties[kCGImagePropertyPixelHeight] as? Int,
                  (1...32768).contains(width), (1...32768).contains(height) else { throw PublicCameraAlbumError.invalidPhoto }
        } else {
            let handle = try FileHandle(forReadingFrom: source)
            defer { try? handle.close() }
            let header = try handle.read(upToCount: 12) ?? Data()
            guard header.count == 12, String(data: header[4..<8], encoding: .ascii) == "ftyp" else { throw PublicCameraAlbumError.invalidPhoto }
            #if canImport(UIKit)
            let duration = CMTimeGetSeconds(AVURLAsset(url: source).duration)
            guard duration.isFinite && duration > 0 else { throw PublicCameraAlbumError.invalidPhoto }
            #endif
        }
        let handle = try FileHandle(forWritingTo: source)
        try handle.synchronize(); try handle.close()
        try FileManager.default.moveItem(at: source, to: root.appendingPathComponent(String(name.dropFirst(9))))
    }
}

#if canImport(UIKit)
import AVFoundation
import AVKit
import Photos
import ChurApp
import UIKit

private final class PublicCameraPreview: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    var focus: ((CGPoint) -> Void)?
    override init(frame: CGRect) {
        super.init(frame: frame)
        previewLayer.videoGravity = .resizeAspect
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tap(_:))))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
    @objc private func tap(_ gesture: UITapGestureRecognizer) { focus?(previewLayer.captureDevicePointConverted(fromLayerPoint: gesture.location(in: self))) }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let connection = previewLayer.connection else { return }
        let angle: CGFloat
        switch window?.windowScene?.interfaceOrientation { case .landscapeLeft: angle = 0; case .landscapeRight: angle = 180; case .portraitUpsideDown: angle = 270; default: angle = 90 }
        if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
    }
}

/// AVFoundation owns capture on one serial queue. UI callbacks carry no vault data.
final class PublicCamera: NSObject, AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate {
    private let album = PublicCameraAlbum(root: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("public/camera", isDirectory: true))
    private let queue = DispatchQueue(label: "public-camera-capture")
    private let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var input: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var front = false
    private var selectedLens = ""
    private var lenses: [AVCaptureDevice] = []
    private var video = false
    private var flash = false
    private var sound = true
    private var busy = false
    private var configured = false
    private var active = false
    private var requested = false
    private var generation = 0
    private var started: Date?
    private var timer: DispatchSourceTimer?
    private var pendingPhoto: URL?
    private var failedSave: URL?
    private var preview: PublicCameraPreview?
    private var player: AVPlayerViewController?
    private var permissionPrompt: Int64?
    private let presenter: () -> UIViewController?
    private let beginPrompt: () -> Int64
    private let endPrompt: (Int64) -> Bool
    private let thumbnails = FileManager.default.temporaryDirectory.appendingPathComponent("public-camera-thumbnails", isDirectory: true)

    init(presenter: @escaping () -> UIViewController?, beginPrompt: @escaping () -> Int64, endPrompt: @escaping (Int64) -> Bool) {
        self.presenter = presenter; self.beginPrompt = beginPrompt; self.endPrompt = endPrompt
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(interrupted), name: .AVCaptureSessionWasInterrupted, object: session)
        NotificationCenter.default.addObserver(self, selector: #selector(interruptionEnded), name: .AVCaptureSessionInterruptionEnded, object: session)
        NotificationCenter.default.addObserver(self, selector: #selector(interrupted), name: .AVCaptureSessionRuntimeError, object: session)
    }
    func prepare() -> Bool { do { try album.prepare(); try FileManager.default.createDirectory(at: thumbnails, withIntermediateDirectories: true); return true } catch { return false } }
    func view() -> UIView {
        let view = PublicCameraPreview()
        view.previewLayer.session = session
        view.focus = { [weak self] point in self?.queue.async { self?.focus(point) } }
        preview = view
        return view
    }
    func start() {
        requested = true
        active = true
        request(.video) { [weak self] permitted in
            guard let self, self.active else { return }
            if permitted { self.queue.async {
                // Recover finished files after process death, and retain incomplete files for retry.
                if let files = try? FileManager.default.contentsOfDirectory(at: self.album.root, includingPropertiesForKeys: nil) {
                    for file in files where file.lastPathComponent.hasPrefix(".pending-") {
                        do { try self.album.commitMedia(file) } catch { self.failedSave = file }
                    }
                }
                self.configure(); if self.configured { self.session.startRunning() }; self.publish(self.failedSave == nil ? nil : "An interrupted capture needs recovery. Tap Retry.") } }
            else { self.publish("Camera access is off. Enable Camera in system Settings.") }
        }
    }
    private func request(_ media: AVMediaType, answer: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: media) {
        case .authorized: answer(true)
        case .notDetermined:
            let token = generation
            permissionPrompt = beginPrompt()
            AVCaptureDevice.requestAccess(for: media) { [weak self] granted in DispatchQueue.main.async {
                guard let self, token == self.generation else { return }
                let valid = self.permissionPrompt.map(self.endPrompt) ?? true
                self.permissionPrompt = nil
                answer(granted && valid)
            } }
        default: answer(false)
        }
    }
    private func configure() {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if let input { session.removeInput(input); self.input = nil }
        let types: [AVCaptureDevice.DeviceType] = front ? [.builtInWideAngleCamera] : [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
        lenses = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera], mediaType: .video, position: front ? .front : .back).devices
        let device = lenses.first { $0.uniqueID == selectedLens } ?? AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: front ? .front : .back).devices.first
        guard let device, let newInput = try? AVCaptureDeviceInput(device: device), session.canAddInput(newInput) else { configured = false; publish("This camera is unavailable."); return }
        session.addInput(newInput); input = newInput
        if !session.outputs.contains(photoOutput), session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        if !session.outputs.contains(movieOutput), session.canAddOutput(movieOutput) { session.addOutput(movieOutput) }
        if !video && session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
        else if session.canSetSessionPreset(.hd1920x1080) { session.sessionPreset = .hd1920x1080 }
        else if session.canSetSessionPreset(.hd1280x720) { session.sessionPreset = .hd1280x720 }
        else { session.sessionPreset = .high }
        if video, device.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }) {
            do {
                try device.lockForConfiguration()
                device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
                device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
                device.unlockForConfiguration()
            } catch { publish("The requested frame rate is unavailable.") }
        }
        if let dimensions = device.activeFormat.supportedMaxPhotoDimensions.max(by: { Int64($0.width) * Int64($0.height) < Int64($1.width) * Int64($1.height) }) {
            photoOutput.maxPhotoDimensions = dimensions
        }
        configureAudio()
        if let connection = movieOutput.connection(with: .video), movieOutput.availableVideoCodecTypes.contains(.h264) { movieOutput.setOutputSettings([AVVideoCodecKey: AVVideoCodecType.h264], for: connection) }
        configured = session.outputs.contains(photoOutput) && session.outputs.contains(movieOutput)
    }
    private func configureAudio() {
        if let audioInput { session.removeInput(audioInput); self.audioInput = nil }
        guard video, sound, AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
              let microphone = AVCaptureDevice.default(for: .audio), let audio = try? AVCaptureDeviceInput(device: microphone), session.canAddInput(audio) else { return }
        session.addInput(audio); audioInput = audio
        if let connection = movieOutput.connection(with: .audio), movieOutput.supportedOutputSettingsKeys(for: connection).contains(AVFormatIDKey) {
            movieOutput.setOutputSettings([AVFormatIDKey: kAudioFormatMPEG4AAC], for: connection)
        }
    }
    func setVideo(_ enabled: Bool) { queue.async { guard !self.movieOutput.isRecording else { return }; self.video = enabled; self.configure(); self.publish() } }
    func flip() { queue.async { guard !self.busy else { return }; self.front.toggle(); self.selectedLens = ""; self.configure(); self.publish() } }
    func lens(_ id: String) { queue.async { guard !self.busy else { return }; self.selectedLens = id; self.configure(); self.publish() } }
    func zoom(_ value: Float) { queue.async { self.configureDevice { $0.videoZoomFactor = min(max(CGFloat(value), $0.minAvailableVideoZoomFactor), min(10, $0.maxAvailableVideoZoomFactor)) }; self.publish() } }
    func exposure(_ value: Float) { queue.async { self.configureDevice { $0.setExposureTargetBias(min(max(value, $0.minExposureTargetBias), $0.maxExposureTargetBias)) }; self.publish() } }
    func toggleFlash() { queue.async { self.flash.toggle(); self.configureDevice { device in if device.hasTorch && self.video { device.torchMode = self.flash ? .on : .off } }; self.publish() } }
    func toggleSound() { queue.async { self.sound.toggle(); self.session.beginConfiguration(); self.configureAudio(); self.session.commitConfiguration(); self.publish() } }
    private func configureDevice(_ body: (AVCaptureDevice) -> Void) {
        guard let device = input?.device else { return }
        do { try device.lockForConfiguration(); defer { device.unlockForConfiguration() }; body(device) } catch { publish("Camera control is unavailable.") }
    }
    private func focus(_ point: CGPoint) { configureDevice { device in
        if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.autoFocus) { device.focusPointOfInterest = point; device.focusMode = .autoFocus }
        if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.continuousAutoExposure) { device.exposurePointOfInterest = point; device.exposureMode = .continuousAutoExposure }
    } }
    func capture() {
        guard active else { return }
        let angle = preview?.previewLayer.connection?.videoRotationAngle ?? 90
        queue.async {
            if self.movieOutput.isRecording { self.movieOutput.stopRecording(); return }
            guard self.configured, self.session.isRunning, !self.busy else { return }
            if self.video && self.sound && AVCaptureDevice.authorizationStatus(for: .audio) != .authorized {
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.active else { return }
                    self.request(.audio) { [weak self] permitted in
                        guard let self, self.active else { return }
                        self.queue.async {
                            self.sound = permitted
                            self.session.beginConfiguration(); self.configureAudio(); self.session.commitConfiguration()
                            self.publish(permitted ? "Sound is on. Tap Record to start." : "Microphone access is off. Video will have no sound.")
                        }
                    }
                }
                return
            }
            do {
                let file = try self.album.pendingMedia(video: self.video)
                self.busy = true
                if self.video {
                    if let connection = self.movieOutput.connection(with: .video), connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
                    self.movieOutput.minFreeDiskSpaceLimit = 16 * 1024 * 1024
                    self.movieOutput.startRecording(to: file, recordingDelegate: self)
                    self.started = Date()
                    let timer = DispatchSource.makeTimerSource(queue: self.queue)
                    timer.schedule(deadline: .now(), repeating: 1); timer.setEventHandler { [weak self] in self?.publish() }; timer.resume(); self.timer = timer
                } else {
                    self.pendingPhoto = file
                    let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                    settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
                    if self.photoOutput.supportedFlashModes.contains(self.flash ? .on : .off) { settings.flashMode = self.flash ? .on : .off }
                    if let connection = self.photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
                    self.photoOutput.capturePhoto(with: settings, delegate: self)
                }
                self.publish()
            } catch { self.busy = false; self.publish("Capture could not start. Check available storage.") }
        }
    }
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        queue.async {
            guard let destination = self.pendingPhoto else { return }; self.pendingPhoto = nil
            guard error == nil, let data = photo.fileDataRepresentation() else { self.busy = false; self.publish("Photo could not be captured."); return }
            do { try data.write(to: destination, options: [.atomic, .completeFileProtection]); try self.album.commitMedia(destination); self.failedSave = nil; self.busy = false; self.publish("Saved") }
            catch { self.failedSave = destination; self.busy = false; self.publish("Photo could not be saved. Tap Retry.") }
        }
    }
    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        queue.async {
            self.timer?.cancel(); self.timer = nil; self.started = nil
            let successful = error == nil || (error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true
            do {
                guard successful else { throw PublicCameraAlbumError.invalidPhoto }
                try self.album.commitMedia(outputFileURL); self.failedSave = nil; self.busy = false; self.publish("Saved")
            } catch { self.failedSave = outputFileURL; self.busy = false; self.publish("Recording interrupted. Tap Retry to recover the file.") }
        }
    }
    func retry() { queue.async { guard let file = self.failedSave else { self.publish(); return }; do { try self.album.commitMedia(file); self.failedSave = nil; self.publish("Saved") } catch { self.publish("File is still unavailable. Its temporary data has been retained.") } } }
    func list() -> [PublicCameraPhoto] {
        (try? album.mediaEntries())?.map { PublicCameraPhoto(id: $0.id, label: DateFormatter.localizedString(from: $0.date, dateStyle: .medium, timeStyle: .short), video: $0.video) } ?? []
    }
    func thumbnail(_ id: String, edge: Int, answer: @escaping (String?) -> KotlinUnit) {
        queue.async {
            do {
                let source = try self.album.mediaURL(id)
                let image: CGImage
                if ["mp4", "mov"].contains(source.pathExtension) {
                    let generator = AVAssetImageGenerator(asset: AVURLAsset(url: source)); generator.appliesPreferredTrackTransform = true; generator.maximumSize = CGSize(width: edge, height: edge)
                    image = try generator.copyCGImage(at: .zero, actualTime: nil)
                } else {
                    guard let input = CGImageSourceCreateWithURL(source as CFURL, nil), let thumbnail = CGImageSourceCreateThumbnailAtIndex(input, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: edge] as CFDictionary) else { throw PublicCameraAlbumError.invalidPhoto }
                    image = thumbnail
                }
                let destination = self.thumbnails.appendingPathComponent(id + "-\(edge).jpg")
                guard let writer = CGImageDestinationCreateWithURL(destination as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { throw PublicCameraAlbumError.unavailable }
                CGImageDestinationAddImage(writer, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
                guard CGImageDestinationFinalize(writer) else { throw PublicCameraAlbumError.unavailable }
                DispatchQueue.main.async { _ = answer(destination.path) }
            } catch { DispatchQueue.main.async { _ = answer(nil) } }
        }
    }
    func playback(_ id: String) -> UIView? {
        guard let url = try? album.mediaURL(id), ["mp4", "mov"].contains(url.pathExtension), let parent = presenter() else { return nil }
        closePlayback()
        let player = AVPlayerViewController(); player.player = AVPlayer(url: url)
        parent.addChild(player); player.didMove(toParent: parent); self.player = player
        player.player?.play()
        return player.view
    }
    func closePlayback() { player?.player?.pause(); player?.player?.replaceCurrentItem(with: nil); player?.willMove(toParent: nil); player?.view.removeFromSuperview(); player?.removeFromParent(); player = nil }
    func delete(_ id: String) -> String? {
        do { let url = try album.mediaURL(id); try FileManager.default.removeItem(at: url); for edge in [320, 1600] { try? FileManager.default.removeItem(at: thumbnails.appendingPathComponent(id + "-\(edge).jpg")) }; return nil }
        catch { publish("File could not be deleted."); return "File could not be deleted." }
    }
    func export(_ ids: [String]) {
        guard let parent = presenter(), let urls = try? ids.map({ try album.mediaURL($0) }), !urls.isEmpty else { return }
        let choices = UIAlertController(title: NSLocalizedString("Export", comment: ""), message: NSLocalizedString("Exported files can be read by other apps and may enter photo sync.", comment: ""), preferredStyle: .actionSheet)
        choices.addAction(UIAlertAction(title: NSLocalizedString("Share", comment: ""), style: .default) { _ in
            let sheet = UIActivityViewController(activityItems: urls, applicationActivities: nil)
            sheet.popoverPresentationController?.sourceView = parent.view
            sheet.popoverPresentationController?.sourceRect = CGRect(x: parent.view.bounds.midX, y: parent.view.bounds.midY, width: 1, height: 1)
            parent.present(sheet, animated: true)
        })
        choices.addAction(UIAlertAction(title: NSLocalizedString("Save to Photos", comment: ""), style: .default) { [weak self] _ in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                guard status == .authorized || status == .limited else { self?.publish("Photo library access is off."); return }
                PHPhotoLibrary.shared().performChanges({ urls.forEach { url in
                    let request = PHAssetCreationRequest.forAsset()
                    request.addResource(with: ["mp4", "mov"].contains(url.pathExtension) ? .video : .photo, fileURL: url, options: nil)
                } }, completionHandler: { saved, _ in self?.publish(saved ? "Saved to Photos" : "Export failed. Local files are unchanged.") })
            }
        })
        choices.addAction(UIAlertAction(title: NSLocalizedString("Cancel", comment: ""), style: .cancel))
        choices.popoverPresentationController?.sourceView = parent.view
        choices.popoverPresentationController?.sourceRect = CGRect(x: parent.view.bounds.midX, y: parent.view.bounds.midY, width: 1, height: 1)
        parent.present(choices, animated: true)
    }
    func cancel() { requested = false; stop() }
    func suspend() { stop() }
    func resume() { if requested { start() } }
    private func stop() {
        active = false; generation += 1
        if let permissionPrompt { _ = endPrompt(permissionPrompt); self.permissionPrompt = nil }
        closePlayback()
        queue.async { if self.movieOutput.isRecording { self.movieOutput.stopRecording() }; self.session.stopRunning(); self.publish() }
    }
    @objc private func interrupted() { queue.async { if self.movieOutput.isRecording { self.movieOutput.stopRecording() }; self.publish("Camera interrupted. Return to the camera to continue.") } }
    @objc private func interruptionEnded() { DispatchQueue.main.async { if self.active { self.start() } } }
    private func publish(_ message: String? = nil) {
        queue.async { self.publishOnQueue(message) }
    }
    private func publishOnQueue(_ message: String?) {
        let device = input?.device
        let status = IosCameraStatus(ready: configured && session.isRunning, recording: movieOutput.isRecording,
            seconds: Int64(started.map { Date().timeIntervalSince($0) } ?? 0), zoom: Float(device?.videoZoomFactor ?? 1),
            minZoom: Float(device?.minAvailableVideoZoomFactor ?? 1), maxZoom: Float(min(10, device?.maxAvailableVideoZoomFactor ?? 1)),
            exposure: device?.exposureTargetBias ?? 0, minExposure: device?.minExposureTargetBias ?? 0, maxExposure: device?.maxExposureTargetBias ?? 0,
            hasFlash: device?.hasFlash ?? false, flash: flash, sound: sound,
            lenses: [PublicCameraLens(id: "", label: NSLocalizedString("Auto", comment: ""))] + lenses.map { PublicCameraLens(id: $0.uniqueID, label: $0.localizedName) }, selectedLens: selectedLens, busy: busy, message: message.map { NSLocalizedString($0, comment: "") })
        DispatchQueue.main.async { IosPublicCamera.shared.didChange(status: status) }
    }
}
#endif
