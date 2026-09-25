import ChurApp
import Darwin
import PhotosUI
import UIKit
import UniformTypeIdentifiers

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    let privacy = IosPrivacyCover()
    private(set) var controller: ChurController!
    private(set) var gate: GateResult!
    private(set) var storageReady = false
    weak var scene: SceneDelegate?

    private lazy var exports = ShareExportSink { [weak self] url in
        if let scene = self?.scene {
            scene.presentShare(url)
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }

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
        IosMediaPicker.shared.present = { [weak self] answer in
            guard let scene = self?.scene else { _ = answer(nil); return }
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
        host.privacy.attach()
        guard host.storageReady, host.gate is GateResultCompatible else { return }
        host.controller.background()
        IosSyncBackground.shared.schedule(earliestBeginDate: nil)
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        host.privacy.detach()
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        if host.scene === self { host.scene = nil }
    }

    func presentMediaPicker(_ answer: @escaping (String?) -> KotlinUnit) {
        guard let presenter = window?.rootViewController else { _ = answer(nil); return }
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = 1
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

    func presentShare(_ url: URL) {
        guard let presenter = window?.rootViewController else {
            try? FileManager.default.removeItem(at: url)
            return
        }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, _, _, _ in
            try? FileManager.default.removeItem(at: url)
        }
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1)
        }
        presenter.present(sheet, animated: true)
    }
}

private final class MediaPickerDelegate: NSObject, PHPickerViewControllerDelegate {
    private var answer: ((String?) -> KotlinUnit)?
    private let done: () -> Void

    init(answer: @escaping (String?) -> KotlinUnit, done: @escaping () -> Void) {
        self.answer = answer
        self.done = done
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider,
              let identifier = provider.registeredTypeIdentifiers.first(where: {
                  guard let type = UTType($0) else { return false }
                  return type.conforms(to: .image) || type.conforms(to: .movie)
              }) else {
            finish(nil)
            return
        }
        provider.loadFileRepresentation(forTypeIdentifier: identifier) { [self] source, _ in
            let path: String?
            if let source {
                let ext = source.pathExtension.isEmpty ? (UTType(identifier)?.preferredFilenameExtension ?? "bin") : source.pathExtension
                let target = FileManager.default.temporaryDirectory
                    .appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
                do {
                    try FileManager.default.copyItem(at: source, to: target)
                    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: target.path)
                    path = target.path
                } catch {
                    try? FileManager.default.removeItem(at: target)
                    path = nil
                }
            } else {
                path = nil
            }
            DispatchQueue.main.async { self.finish(path) }
        }
    }

    private func finish(_ path: String?) {
        guard let answer else { return }
        self.answer = nil
        _ = answer(path)
        done()
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
    private let present: (URL) -> Void
    private let directory = FileManager.default.temporaryDirectory.appendingPathComponent("chur-exports", isDirectory: true)

    init(present: @escaping (URL) -> Void) {
        self.present = present
        super.init()
        // A terminated share sheet leaves plaintext behind; only our own
        // scratch directory is removed on the next process launch.
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func create(displayName: String, contentType: String) -> ExportSinkDestination? {
        let ext = UTType(mimeType: contentType)?.preferredFilenameExtension
            ?? URL(fileURLWithPath: displayName).pathExtension
        let suffix = ext.range(of: "^[A-Za-z0-9]{1,10}$", options: .regularExpression) == nil ? "bin" : ext
        let url = directory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(suffix)
        let descriptor = Darwin.open(url.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { return nil }
        do {
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        } catch {
            Darwin.close(descriptor)
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return ShareDestination(url: url, descriptor: descriptor, present: present)
    }
}

private final class ShareDestination: NSObject, ExportSinkDestination {
    let descriptor: Int32
    private let url: URL
    private let present: (URL) -> Void
    private var closed = false

    init(url: URL, descriptor: Int32, present: @escaping (URL) -> Void) {
        self.url = url
        self.descriptor = descriptor
        self.present = present
    }

    func publish() { DispatchQueue.main.async { self.present(self.url) } }
    func discard() { try? FileManager.default.removeItem(at: url) }
    func close() {
        if !closed { Darwin.close(descriptor); closed = true }
    }
}
