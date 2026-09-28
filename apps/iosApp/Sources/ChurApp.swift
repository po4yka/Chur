import ChurApp
import Darwin
import PhotosUI
import Photos
import UIKit
import UniformTypeIdentifiers

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    let privacy = IosPrivacyCover()
    private(set) var controller: ChurController!
    private(set) var gate: GateResult!
    private(set) var storageReady = false
    weak var scene: SceneDelegate?

    private lazy var exports = ShareExportSink(
        present: { [weak self] url, target in
            if let scene = self?.scene {
                scene.presentExport(url, target: target)
            } else {
                try? FileManager.default.removeItem(at: url)
            }
        },
        cancel: { [weak self] in self?.scene?.cancelPendingExports() }
    )

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        #if DEBUG
        let releaseApplication = false
        #else
        let releaseApplication = true
        #endif
        gate = IosEntryPointKt.churNativeGate(releaseApplication: releaseApplication)
        controller = IosEntryPointKt.churController(privacy: privacy, exports: exports)

        do {
            for path in [IosEntryPointKt.churStorageRoot(), IosEntryPointKt.churSyncStateRoot()] {
                let url = NSURL(fileURLWithPath: path, isDirectory: true)
                try url.setResourceValue(true, forKey: .isExcludedFromBackupKey)
            }
            storageReady = true
        } catch {
            storageReady = false
        }

        if storageReady, gate is GateResultCompatible {
            IosSyncBackground.shared.register(controller: controller)
        }
        let mediaScratchReady = MediaPickerDelegate.prepareScratch()
        IosMediaPicker.shared.present = { [weak self] answer in
            guard mediaScratchReady, let scene = self?.scene else { MediaPickerDelegate.answerNothing(answer); return }
            scene.presentMediaPicker(answer)
        }
        IosBackupPicker.shared.present = { [weak self] answer in
            guard let scene = self?.scene else { _ = answer(nil); return }
            scene.presentBackupPicker(answer)
        }
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var mediaPicker: MediaPickerDelegate?
    private var backupPicker: BackupPickerDelegate?
    private var exportPicker: ExportPickerDelegate?
    private var pendingExports: [(URL, ExportTarget)] = []
    private var presentingExport = false
    private var exportUsesHostActivity = false
    private var activeExportURL: URL?
    private var activeExportController: UIViewController?
    private var exportGeneration = 0

    private var host: AppDelegate { UIApplication.shared.delegate as! AppDelegate }

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        host.scene = self
        let window = UIWindow(windowScene: scene)
        if host.storageReady {
            window.rootViewController = IosEntryPointKt.ChurViewController(controller: host.controller, gate: host.gate)
        } else {
            let failure = UIViewController()
            failure.view.backgroundColor = .systemBackground
            let label = UILabel()
            label.text = "Private storage is unavailable."
            label.textAlignment = .center
            label.translatesAutoresizingMaskIntoConstraints = false
            failure.view.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: failure.view.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: failure.view.centerYAnchor),
            ])
            window.rootViewController = failure
        }
        self.window = window
        window.makeKeyAndVisible()
        if host.storageReady, host.gate is GateResultCompatible {
            host.controller.begin()
        }
    }

    func sceneWillResignActive(_ scene: UIScene) {
        // The cover exists only while the scene is inactive, and only over a
        // screen the switcher must not show; `IosPrivacyCover` says why.
        if host.controller.privacyCoverNeeded { host.privacy.attach() }
        guard host.storageReady, host.gate is GateResultCompatible else { return }
        host.controller.background()
        IosSyncBackground.shared.schedule(earliestBeginDate: nil)
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        // UIKit takes the switcher picture after this returns, and a session
        // can have opened since the scene resigned active: a vault creation
        // finishes its key derivation without the lock epoch check an unlock
        // has. `attach` does nothing when the cover is already up.
        if host.controller.privacyCoverNeeded { host.privacy.attach() }
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        host.privacy.detach()
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        if host.scene === self { host.scene = nil }
    }

    func presentMediaPicker(_ answer: @escaping MediaAnswer) {
        guard let presenter = window?.rootViewController else { MediaPickerDelegate.answerNothing(answer); return }
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        // Any number of items, in the order picked, `IOS.md` §15.1. `.current`
        // hands over each item as stored rather than transcoded, so a HEIC
        // original stays HEIC (§15.3).
        configuration.selectionLimit = 0
        configuration.selection = .ordered
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        let delegate = MediaPickerDelegate(answer: answer) { [weak self] in self?.mediaPicker = nil }
        mediaPicker = delegate
        picker.delegate = delegate
        presenter.present(picker, animated: true)
    }

    func presentBackupPicker(_ answer: @escaping (String?) -> KotlinUnit) {
        guard let presenter = window?.rootViewController else { _ = answer(nil); return }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: true)
        let delegate = BackupPickerDelegate(answer: answer) { [weak self] in self?.backupPicker = nil }
        backupPicker = delegate
        picker.delegate = delegate
        presenter.present(picker, animated: true)
    }

    func presentExport(_ url: URL, target: ExportTarget) {
        pendingExports.append((url, target))
        presentNextExport()
    }

    private func presentNextExport() {
        guard !presentingExport, !pendingExports.isEmpty else { return }
        presentingExport = true
        let (url, target) = pendingExports.removeFirst()
        let generation = exportGeneration
        activeExportURL = url
        exportUsesHostActivity = target != .mediaLibrary
        if exportUsesHostActivity { host.controller.beginHostActivity() }
        guard FileManager.default.fileExists(atPath: url.path) else {
            finishExport(url, generation: generation)
            host.controller.report(message: "Export expired. Please try again.")
            return
        }
        guard let presenter = window?.rootViewController else {
            finishExport(url, generation: generation)
            return
        }
        if target == .mediaLibrary {
            guard let type = UTType(filenameExtension: url.pathExtension),
                  type.conforms(to: .image) || type.conforms(to: .movie) else {
                finishExport(url, generation: generation)
                host.controller.report(message: "Only photos and videos can be saved to Photos.")
                return
            }
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
                DispatchQueue.main.async {
                    guard let self, self.exportGeneration == generation else { return }
                    guard status == .authorized else {
                        if self.finishExport(url, generation: generation) {
                            self.host.controller.report(message: "Photos access was not granted.")
                        }
                        return
                    }
                    PHPhotoLibrary.shared().performChanges({
                        if type.conforms(to: .movie) {
                            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                        } else {
                            PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url)
                        }
                    }) { [weak self] saved, _ in
                        DispatchQueue.main.async {
                            if self?.finishExport(url, generation: generation) == true {
                                self?.host.controller.report(message: saved ? "Saved to Photos. The copy is outside the vault." : "Could not save to Photos.")
                            }
                        }
                    }
                }
            }
            return
        }
        if target == .files {
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            let delegate = ExportPickerDelegate { [weak self] saved in
                self?.exportPicker = nil
                if self?.finishExport(url, generation: generation) == true {
                    self?.host.controller.report(message: saved ? "Saved to Files. The copy is outside the vault." : "Save to Files cancelled.")
                }
            }
            exportPicker = delegate
            picker.delegate = delegate
            activeExportController = picker
            presenter.present(picker, animated: true)
            return
        }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, completed, _, _ in
            if self.finishExport(url, generation: generation) {
                self.host.controller.report(message: completed ? "Shared. The copy is outside the vault." : "Sharing cancelled.")
            }
        }
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1)
        }
        activeExportController = sheet
        presenter.present(sheet, animated: true)
    }

    func cancelPendingExports() {
        exportGeneration += 1
        pendingExports.forEach { try? FileManager.default.removeItem(at: $0.0) }
        pendingExports.removeAll()
        if let activeExportURL { try? FileManager.default.removeItem(at: activeExportURL) }
        activeExportController?.dismiss(animated: false)
        activeExportController = nil
        activeExportURL = nil
        if exportUsesHostActivity { host.controller.endHostActivity() }
        exportUsesHostActivity = false
        presentingExport = false
        exportPicker = nil
    }

    @discardableResult private func finishExport(_ url: URL, generation: Int) -> Bool {
        try? FileManager.default.removeItem(at: url)
        guard generation == exportGeneration, activeExportURL == url else { return false }
        if exportUsesHostActivity { host.controller.endHostActivity() }
        exportUsesHostActivity = false
        presentingExport = false
        activeExportURL = nil
        activeExportController = nil
        presentNextExport()
        return true
    }
}

private final class ExportPickerDelegate: NSObject, UIDocumentPickerDelegate {
    private let done: (Bool) -> Void

    init(done: @escaping (Bool) -> Void) {
        self.done = done
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        controller.dismiss(animated: true) { self.done(!urls.isEmpty) }
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        controller.dismiss(animated: true) { self.done(false) }
    }
}

/// Loads one picked item for Kotlin, `IosMediaPicker`: the item's index, a
/// report of the fetch that answers false once the user cancels, and the
/// answer with the copy's path and the item's own name.
typealias MediaLoad = (
    KotlinInt,
    @escaping (KotlinLong, KotlinLong) -> KotlinBoolean,
    @escaping (String?, String?) -> KotlinUnit
) -> KotlinUnit

/// Deletes the originals of the picked items at the given indices, and
/// answers how many it found and how many were deleted.
typealias MediaReview = ([KotlinInt], @escaping (KotlinInt, KotlinInt) -> KotlinUnit) -> KotlinUnit

/// The answer to a pick: how many items, how to load each one, and how to
/// delete their originals after the import.
typealias MediaAnswer = (KotlinInt, @escaping MediaLoad, @escaping MediaReview) -> KotlinUnit

private final class MediaPickerDelegate: NSObject, PHPickerViewControllerDelegate {
    private static let scratch = FileManager.default.temporaryDirectory
        .appendingPathComponent("chur-media-imports", isDirectory: true)
    private var answer: MediaAnswer?
    private let done: () -> Void

    static func prepareScratch() -> Bool {
        do {
            if FileManager.default.fileExists(atPath: scratch.path) {
                try FileManager.default.removeItem(at: scratch)
            }
            try FileManager.default.createDirectory(
                at: scratch,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete]
            )
            try (scratch as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
            return true
        } catch {
            return false
        }
    }

    /// Answers a picker that could not be shown as a pick of nothing.
    static func answerNothing(_ answer: MediaAnswer) {
        _ = answer(
            KotlinInt(int: 0),
            { _, _, loaded in loaded(nil, nil) },
            { _, done in done(KotlinInt(int: 0), KotlinInt(int: 0)) }
        )
    }

    init(answer: @escaping MediaAnswer, done: @escaping () -> Void) {
        self.answer = answer
        self.done = done
    }

    /// Answers with the pick and fetches nothing yet: Kotlin loads one item
    /// at a time and deletes its copy before it loads the next, so at most
    /// one plaintext copy exists, `IOS.md` §15.2. The providers stay with the
    /// load closure, which Kotlin holds until the pick is imported, and the
    /// Photos identifiers with the review closure, which Kotlin holds until
    /// the user answers the step after the import, §15.4.
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let answer else { return }
        self.answer = nil
        let providers = results.map(\.itemProvider)
        let assets = results.map(\.assetIdentifier)
        _ = answer(
            KotlinInt(int: Int32(providers.count)),
            { index, prepare, loaded in
                Self.load(
                    providers[index.intValue],
                    prepare: { prepare(KotlinLong(longLong: $0), KotlinLong(longLong: $1)).boolValue },
                    loaded: { _ = loaded($0, $1) }
                )
                return KotlinUnit()
            },
            { indices, done in
                Self.deleteOriginals(indices.compactMap { assets[$0.intValue] }) { found, deleted in
                    _ = done(KotlinInt(int: found), KotlinInt(int: deleted))
                }
                return KotlinUnit()
            }
        )
        done()
    }

    /// Deletes originals from the photo library, `DESIGN.md` §15.3 and
    /// `IOS.md` §15.4.
    ///
    /// Read-write access is asked for here, when the user chose to review the
    /// deletion, and not at import, which the picker serves without it. Photos
    /// shows the items and asks before it deletes them, and a refusal of
    /// either deletes nothing. An item outside the access the user granted is
    /// not found. The answer comes on the main thread.
    private static func deleteOriginals(_ identifiers: [String], done: @escaping (Int32, Int32) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            let granted = status == .authorized || status == .limited
            let assets = granted && !identifiers.isEmpty
                ? PHAsset.fetchAssets(withLocalIdentifiers: identifiers, options: nil)
                : nil
            guard let assets, assets.count > 0 else {
                DispatchQueue.main.async { done(0, 0) }
                return
            }
            let found = Int32(assets.count)
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets(assets)
            }) { deleted, _ in
                DispatchQueue.main.async { done(found, deleted ? found : 0) }
            }
        }
    }

    /// Fetches one item into a protected copy, `IOS.md` §15.1-15.2.
    ///
    /// An iCloud original may not be on the device yet. A timer on the main
    /// run loop reports the fetch four times a second, and each report also
    /// asks whether the user cancelled: a timer rather than a progress
    /// observer keeps a fetch that stalls on the network cancellable. The
    /// copy is made before the provider's callback returns, because the
    /// provider deletes its file then. Kotlin calls this on the main thread.
    private static func load(
        _ provider: NSItemProvider,
        prepare: @escaping (Int64, Int64) -> Bool,
        loaded: @escaping (String?, String?) -> Void
    ) {
        guard let identifier = provider.registeredTypeIdentifiers.first(where: {
            guard let type = UTType($0) else { return false }
            return type.conforms(to: .image) || type.conforms(to: .movie)
        }) else {
            loaded(nil, nil)
            return
        }
        let box = FetchBox()
        let ticker = Timer(timeInterval: 0.25, repeats: true) { ticker in
            guard let fetch = box.progress else { return }
            // A fetch of unknown size reports no total, and the bar stays
            // indeterminate.
            let total: Int64 = fetch.isIndeterminate ? 0 : 1_000
            if !prepare(Int64(fetch.fractionCompleted * 1_000), total) {
                ticker.invalidate()
                fetch.cancel()
            }
        }
        box.progress = provider.loadFileRepresentation(forTypeIdentifier: identifier) { source, _ in
            let copy = source.flatMap { copyToScratch($0, identifier: identifier) }
            let name = copy.flatMap { copy in
                provider.suggestedName.map {
                    $0.lowercased().hasSuffix("." + copy.pathExtension.lowercased()) ? $0 : "\($0).\(copy.pathExtension)"
                }
            }
            DispatchQueue.main.async {
                ticker.invalidate()
                loaded(copy?.path, name)
            }
        }
        RunLoop.main.add(ticker, forMode: .common)
    }

    /// Holds the fetch for the timer, which is made before the fetch starts.
    /// The timer and the assignment both run on the main thread.
    private final class FetchBox {
        var progress: Progress?
    }

    /// Copies a provider's file under a random name, which `IOS.md` §12 asks
    /// for: a file name must not tell the filename.
    private static func copyToScratch(_ source: URL, identifier: String) -> URL? {
        let ext = source.pathExtension.isEmpty ? (UTType(identifier)?.preferredFilenameExtension ?? "bin") : source.pathExtension
        let target = scratch.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
        do {
            guard FileManager.default.createFile(
                atPath: target.path,
                contents: nil,
                attributes: [.protectionKey: FileProtectionType.complete]
            ) else { throw CocoaError(.fileWriteUnknown) }
            let reader = try FileHandle(forReadingFrom: source)
            defer { try? reader.close() }
            let writer = try FileHandle(forWritingTo: target)
            defer { try? writer.close() }
            while let chunk = try reader.read(upToCount: 64 * 1024), !chunk.isEmpty {
                try writer.write(contentsOf: chunk)
            }
            try writer.close()
            return target
        } catch {
            try? FileManager.default.removeItem(at: target)
            return nil
        }
    }
}

private final class BackupPickerDelegate: NSObject, UIDocumentPickerDelegate {
    private var answer: ((String?) -> KotlinUnit)?
    private let done: () -> Void

    init(answer: @escaping (String?) -> KotlinUnit, done: @escaping () -> Void) {
        self.answer = answer
        self.done = done
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let source = urls.first else { finish(nil); return }
        let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(source.pathExtension.isEmpty ? "churbackup" : source.pathExtension)
        do {
            try FileManager.default.copyItem(at: source, to: target)
            finish(target.path)
        } catch {
            finish(nil)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(nil) }

    private func finish(_ path: String?) {
        guard let answer else { return }
        self.answer = nil
        _ = answer(path)
        done()
    }
}

private final class ShareExportSink: NSObject, ExportSink {
    private let present: (URL, ExportTarget) -> Void
    private let cancel: () -> Void
    private let directory = FileManager.default.temporaryDirectory.appendingPathComponent("chur-exports", isDirectory: true)
    private var generation = 0
    private var ready = false

    init(present: @escaping (URL, ExportTarget) -> Void, cancel: @escaping () -> Void) {
        self.present = present
        self.cancel = cancel
        super.init()
        // A terminated share sheet leaves plaintext behind; only our own
        // scratch directory is removed on the next process launch.
        ready = resetDirectory()
    }

    func cancelPending() {
        generation += 1
        cancel()
        ready = resetDirectory()
    }

    private func resetDirectory() -> Bool {
        do {
            if FileManager.default.fileExists(atPath: directory.path) {
                try FileManager.default.removeItem(at: directory)
            }
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete]
            )
            try (directory as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
            return true
        } catch {
            return false
        }
    }

    func create(displayName: String, contentType: String) -> ExportSinkDestination? {
        create(displayName: displayName, contentType: contentType, target: .default_, uri: nil)
    }

    func create(displayName: String, contentType: String, target: ExportTarget, uri: String?) -> ExportSinkDestination? {
        guard ready else { return nil }
        let createdGeneration = generation
        let ext = UTType(mimeType: contentType)?.preferredFilenameExtension
            ?? URL(fileURLWithPath: displayName).pathExtension
        let suffix = ext.range(of: "^[A-Za-z0-9]{1,10}$", options: .regularExpression) == nil ? "bin" : ext
        let folder = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        guard (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil else { return nil }
        let original = URL(fileURLWithPath: displayName).lastPathComponent
        let base = original.isEmpty || original == "." ? "chur-export" : original
        let name = URL(fileURLWithPath: base).pathExtension.isEmpty ? "\(base).\(suffix)" : base
        let url = folder.appendingPathComponent(target == .files ? name : "export.\(suffix)")
        let descriptor = Darwin.open(url.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { return nil }
        do {
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        } catch {
            Darwin.close(descriptor)
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return ShareDestination(
            url: url,
            descriptor: descriptor,
            target: target,
            present: present,
            valid: { [weak self] in self?.generation == createdGeneration }
        )
    }
}

private final class ShareDestination: NSObject, ExportSinkDestination {
    let descriptor: Int32
    private let url: URL
    private let target: ExportTarget
    private let present: (URL, ExportTarget) -> Void
    private let valid: () -> Bool
    private var closed = false

    init(url: URL, descriptor: Int32, target: ExportTarget, present: @escaping (URL, ExportTarget) -> Void, valid: @escaping () -> Bool) {
        self.url = url
        self.descriptor = descriptor
        self.target = target
        self.present = present
        self.valid = valid
    }

    func publish() {
        DispatchQueue.main.async {
            guard self.valid() else { self.discard(); return }
            self.present(self.url, self.target)
            let url = self.url
            DispatchQueue.main.asyncAfter(deadline: .now() + 30 * 60) {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }
    func discard() { try? FileManager.default.removeItem(at: url) }
    func close() {
        if !closed { Darwin.close(descriptor); closed = true }
    }
}
