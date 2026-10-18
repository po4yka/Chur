#if canImport(UIKit)
import Foundation
import Photos
import UniformTypeIdentifiers
import CryptoKit

/// Owns one cancellable resource request. Callbacks retain this owner until completion.
final class LivePhotoCapture {
    private let lock = NSLock()
    private var request: PHAssetResourceDataRequestID?
    private var cancelled = false
    func cancel() {
        lock.lock(); cancelled = true; let id = request; lock.unlock()
        if let id { PHAssetResourceManager.default().cancelDataRequest(id) }
    }
    private func continuing() -> Bool { lock.lock(); defer { lock.unlock() }; return !cancelled }

    func capture(asset: PHAsset, directory: URL, limit: UInt64, shouldContinue: @escaping () -> Bool,
                 completion: @escaping (URL?) -> Void) {
        let initial = Self.inventory(asset)
        let modified = asset.modificationDate
        let originals = PHAssetResource.assetResources(for: asset)
        DispatchQueue.global(qos: .userInitiated).async {
            let target = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension("churlive")
            var success = false
            defer { if !success { try? FileManager.default.removeItem(at: target) }; DispatchQueue.main.async { completion(success ? target : nil) } }
            do {
                guard asset.mediaSubtypes.contains(.photoLive), (2...32).contains(originals.count) else { throw LivePhotoBundleFile.Failure.invalid }
                var bundle = LivePhotoBundleFile(captureTime: asset.creationDate.map { max(-1, Int64($0.timeIntervalSince1970 * 1000)) } ?? -1,
                    resources: originals.map { r in
                        let uti = r.uniformTypeIdentifier
                        return .init(type: UInt32(r.type.rawValue), length: r.type == .photo || r.type == .pairedVideo ? 1 : 0,
                            digest: Data(repeating: 0, count: 32), filename: r.originalFilename, uti: uti,
                            mime: UTType(uti)?.preferredMIMEType?.lowercased() ?? "application/octet-stream")
                    })
                let header = try bundle.header()
                guard UInt64(header.count) <= limit, FileManager.default.createFile(atPath: target.path, contents: header, attributes: [.protectionKey: FileProtectionType.complete, .posixPermissions: 0o600]) else { throw LivePhotoBundleFile.Failure.limit }
                try (target as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
                let file = try FileHandle(forWritingTo: target); defer { try? file.close() }
                try file.seekToEnd()
                var total = UInt64(header.count)
                for (index, resource) in originals.enumerated() {
                    guard self.continuing(), shouldContinue() else { throw LivePhotoBundleFile.Failure.cancelled }
                    let semaphore = DispatchSemaphore(value: 0)
                    var sha = SHA256(), length: UInt64 = 0, failure: Error?
                    let options = PHAssetResourceRequestOptions(); options.isNetworkAccessAllowed = true
                    let id = PHAssetResourceManager.default().requestData(for: resource, options: options, dataReceivedHandler: { chunk in
                        guard failure == nil else { return }
                        do {
                            guard self.continuing(), shouldContinue() else { throw LivePhotoBundleFile.Failure.cancelled }
                            guard UInt64(chunk.count) <= min(limit, LivePhotoBundleFile.maxBytes) - total else { throw LivePhotoBundleFile.Failure.limit }
                            try file.write(contentsOf: chunk); sha.update(data: chunk)
                            length += UInt64(chunk.count); total += UInt64(chunk.count)
                        } catch { failure = error; self.cancel() }
                    }, completionHandler: { error in
                        if let error { failure = error }; semaphore.signal()
                    })
                    self.lock.lock(); self.request = id; let cancel = self.cancelled; self.lock.unlock()
                    if cancel { PHAssetResourceManager.default().cancelDataRequest(id) }
                    semaphore.wait()
                    self.lock.lock(); self.request = nil; self.lock.unlock()
                    if let failure { throw failure }
                    bundle.resources[index].length = length; bundle.resources[index].digest = Data(sha.finalize())
                }
                guard self.continuing(), shouldContinue(),
                      let fresh = PHAsset.fetchAssets(withLocalIdentifiers: [asset.localIdentifier], options: nil).firstObject,
                      fresh.modificationDate == modified, Self.inventory(fresh) == initial else { throw LivePhotoBundleFile.Failure.changed }
                try file.seek(toOffset: 0); try file.write(contentsOf: bundle.header()); try file.synchronize()
                success = true
            } catch { /* Public caller reports a redacted, recoverable import failure. */ }
        }
    }
    private static func inventory(_ asset: PHAsset) -> [String] {
        PHAssetResource.assetResources(for: asset).map { "\($0.type.rawValue)|\($0.originalFilename)|\($0.uniformTypeIdentifier)|\($0.pixelWidth)|\($0.pixelHeight)" }
    }
}

/// Restores every stored role in one Photos transaction. No resource is dropped.
enum LivePhotoRestoration {
    static func save(_ source: URL, shouldContinue: @escaping () -> Bool = { true }, completion: @escaping (Bool) -> Void) {
        restore(source, shouldContinue: shouldContinue) { completion($0 != nil) }
    }
    static func restore(_ source: URL, shouldContinue: @escaping () -> Bool = { true }, onFailure: ((Error) -> Void)? = nil, completion: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let folder = source.deletingLastPathComponent().appendingPathComponent(UUID().uuidString, isDirectory: true)
            do {
                let bundle = try LivePhotoBundleFile.read(source, verify: false)
                guard PHAssetCreationRequest.supportsAssetResourceTypes(bundle.resources.map { NSNumber(value: $0.type) }) else { throw LivePhotoBundleFile.Failure.unsupported }
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete, .posixPermissions: 0o700])
                try (folder as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
                let files = try bundle.extract(source, into: folder, shouldContinue: shouldContinue)
                guard shouldContinue() else { throw LivePhotoBundleFile.Failure.cancelled }
                var identifier: String?
                PHPhotoLibrary.shared().performChanges({
                    let request = PHAssetCreationRequest.forAsset()
                    if bundle.captureTime >= 0 { request.creationDate = Date(timeIntervalSince1970: Double(bundle.captureTime) / 1000) }
                    for (r, url) in zip(bundle.resources, files) {
                        let options = PHAssetResourceCreationOptions(); options.originalFilename = r.filename
                        options.uniformTypeIdentifier = r.uti; options.shouldMoveFile = false
                        request.addResource(with: PHAssetResourceType(rawValue: Int(r.type))!, fileURL: url, options: options)
                    }
                    identifier = request.placeholderForCreatedAsset?.localIdentifier
                }) { saved, error in
                    try? FileManager.default.removeItem(at: folder)
                    DispatchQueue.main.async { if let error { onFailure?(error) }; completion(saved ? identifier : nil) }
                }
            } catch {
                try? FileManager.default.removeItem(at: folder)
                DispatchQueue.main.async { onFailure?(error); completion(nil) }
            }
        }
    }
}
#endif
