import XCTest
import Photos
import PhotosUI
import AVFoundation
import CryptoKit
import ImageIO

final class LivePhotoNativeTests: XCTestCase {
    private func fixture(_ name: String) throws -> URL { try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: nil)) }

    func testMatchedPhotoMotionAudioAndPhotosRoundTrip() async throws {
        let photo = try fixture("still.jpg"), movie = try fixture("motion.mov")
        let sourceMovie = AVURLAsset(url: movie)
        let audioTracks = try await sourceMovie.loadTracks(withMediaType: .audio)
        XCTAssertEqual(audioTracks.count, 1)
        let videoTracks = try await sourceMovie.loadTracks(withMediaType: .video)
        XCTAssertEqual(videoTracks.count, 1)
        let metadataTracks = try await sourceMovie.loadTracks(withMediaType: .metadata)
        XCTAssertEqual(metadataTracks.count, 1)
        let imageSource = try XCTUnwrap(CGImageSourceCreateWithURL(photo as CFURL, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any])
        let apple = try XCTUnwrap(properties[kCGImagePropertyMakerAppleDictionary as String] as? [String: Any])
        let uuid = try XCTUnwrap(apple["17"] as? String)
        let metadata = try await sourceMovie.load(.metadata)
        let contentIdentifier = try XCTUnwrap(metadata.first { $0.identifier == .quickTimeMetadataContentIdentifier })
        let movieIdentifier = try await contentIdentifier.load(.stringValue)
        XCTAssertEqual(uuid, movieIdentifier)

        let live = expectation(description: "Native Live Photo recognized")
        var recognized = false
        PHLivePhoto.request(withResourceFileURLs: [photo, movie], placeholderImage: nil,
                            targetSize: CGSize(width: 64, height: 64), contentMode: .aspectFit) { object, info in
            if (info[PHLivePhotoInfoIsDegradedKey] as? Bool) == true { return }
            recognized = object != nil
            live.fulfill()
        }
        await fulfillment(of: [live], timeout: 20)
        XCTAssertTrue(recognized)
        guard recognized else { return }

        // The test runner needs its own Photos grant; this never uses the user's library on the Mac.
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        XCTAssertTrue(status == .authorized || status == .limited, "Grant Photos to the task-owned simulator test runner")
        guard status == .authorized || status == .limited else { return }
        var identifier: String?
        let saved = expectation(description: "Native asset created")
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, fileURL: photo, options: nil)
            request.addResource(with: .pairedVideo, fileURL: movie, options: nil)
            identifier = request.placeholderForCreatedAsset?.localIdentifier
        }) { success, error in XCTAssertTrue(success, "Photos create failed"); XCTAssertNil(error); saved.fulfill() }
        await fulfillment(of: [saved], timeout: 30)
        let id = try XCTUnwrap(identifier)
        let asset = try XCTUnwrap(PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject)
        XCTAssertTrue(asset.mediaSubtypes.contains(.photoLive))
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        defer { try? FileManager.default.removeItem(at: folder) }
        let captured = expectation(description: "All native resources captured")
        var capturedURL: URL?
        let capture = LivePhotoCapture()
        capture.capture(asset: asset, directory: folder, limit: LivePhotoBundleFile.maxBytes, shouldContinue: { true }) { url in capturedURL = url; captured.fulfill() }
        await fulfillment(of: [captured], timeout: 30)
        let bundleURL = try XCTUnwrap(capturedURL)
        let bundle = try LivePhotoBundleFile.read(bundleURL)
        XCTAssertEqual(Set(bundle.resources.map(\.type)), Set([1, 9]))
        XCTAssertEqual(bundle.resources.first { $0.type == 1 }?.digest, Data(SHA256.hash(data: try Data(contentsOf: photo))))
        XCTAssertEqual(bundle.resources.first { $0.type == 9 }?.digest, Data(SHA256.hash(data: try Data(contentsOf: movie))))

        let restored = expectation(description: "Full bundle restored")
        var restoredID: String?
        LivePhotoRestoration.restore(bundleURL, onFailure: { _ in XCTFail("Photos rejected the complete fixture") }) { restoredID = $0; restored.fulfill() }
        await fulfillment(of: [restored], timeout: 30)
        let restoredAsset = try XCTUnwrap(PHAsset.fetchAssets(withLocalIdentifiers: [try XCTUnwrap(restoredID)], options: nil).firstObject)
        XCTAssertTrue(restoredAsset.mediaSubtypes.contains(.photoLive))
        let recaptured = expectation(description: "Restored native resources captured")
        var restoredURL: URL?
        LivePhotoCapture().capture(asset: restoredAsset, directory: folder, limit: LivePhotoBundleFile.maxBytes, shouldContinue: { true }) { restoredURL = $0; recaptured.fulfill() }
        await fulfillment(of: [recaptured], timeout: 30)
        let restoredBundle = try LivePhotoBundleFile.read(try XCTUnwrap(restoredURL))
        XCTAssertEqual(bundle.resources.map(\.digest), restoredBundle.resources.map(\.digest))
        XCTAssertEqual(bundle.resources.map(\.filename), restoredBundle.resources.map(\.filename))
        XCTAssertEqual(bundle.resources.map(\.uti), restoredBundle.resources.map(\.uti))
        let restoredFiles = try restoredBundle.extract(try XCTUnwrap(restoredURL), into: folder)
        let movieIndex = try XCTUnwrap(restoredBundle.resources.firstIndex { $0.type == 9 })
        try await assertAudiblePCM(restoredFiles[movieIndex])
        // Simulator-created fixture assets remain in the dedicated disposable simulator for evidence.
    }

    /// Decode the movie recaptured from Photos, with a fixed memory and frame budget.
    private func assertAudiblePCM(_ movie: URL) async throws {
        let asset = AVURLAsset(url: movie)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        XCTAssertEqual(tracks.count, 1)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: try XCTUnwrap(tracks.first), outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ])
        XCTAssertTrue(reader.canAdd(output)); reader.add(output)
        XCTAssertTrue(reader.startReading())
        defer { if reader.status != .completed { reader.cancelReading() } }
        var decodedBytes = 0, peak = 0, nonzeroSamples = 0
        while let sample = output.copyNextSampleBuffer() {
            let block = try XCTUnwrap(CMSampleBufferGetDataBuffer(sample))
            let length = CMBlockBufferGetDataLength(block)
            guard length > 0, length <= 65_536, length <= 512 * 1024 - decodedBytes, length % 2 == 0 else {
                XCTFail("PCM decode exceeded the fixture budget"); return
            }
            var bytes = [UInt8](repeating: 0, count: length)
            let copied = bytes.withUnsafeMutableBytes {
                CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: $0.baseAddress!)
            }
            XCTAssertEqual(copied, kCMBlockBufferNoErr)
            for index in stride(from: 0, to: length, by: 2) {
                let raw = UInt16(bytes[index]) | UInt16(bytes[index + 1]) << 8
                let amplitude = abs(Int(Int16(bitPattern: raw)))
                peak = max(peak, amplitude)
                if amplitude > 0 { nonzeroSamples += 1 }
            }
            decodedBytes += length
        }
        XCTAssertEqual(reader.status, .completed)
        XCTAssertNil(reader.error)
        XCTAssertGreaterThan(decodedBytes / 2, 44_100, "Decode more than one second of restored audio")
        XCTAssertGreaterThan(nonzeroSamples, 44_100, "Restored PCM must contain a waveform")
        XCTAssertGreaterThan(peak, 256, "Restored audio must contain a nonzero tone")
    }

    func testUnsupportedRoleFailsWithoutDroppingSidecar() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let files = [try Data(contentsOf: fixture("still.jpg")), try Data(contentsOf: fixture("motion.mov")), Data([1, 2, 3])]
        let roles: [UInt32] = [1, 9, 42], names = ["still.jpg", "motion.mov", "extra.bin"]
        let utis = ["public.jpeg", "com.apple.quicktime-movie", "public.data"]
        let mimes = ["image/jpeg", "video/quicktime", "application/octet-stream"]
        let resources = files.enumerated().map { index, bytes in
            LivePhotoBundleFile.Resource(type: roles[index], length: UInt64(bytes.count), digest: Data(SHA256.hash(data: bytes)), filename: names[index], uti: utis[index], mime: mimes[index])
        }
        let bundle = LivePhotoBundleFile(captureTime: -1, resources: resources)
        let source = folder.appendingPathComponent("test.churlive")
        try (bundle.header() + files.reduce(Data(), +)).write(to: source)
        let completed = expectation(description: "Unsupported combination rejected")
        LivePhotoRestoration.restore(source, onFailure: { error in
            if let failure = error as? LivePhotoBundleFile.Failure { XCTAssertEqual(failure, .unsupported) }
            else { XCTAssertEqual((error as NSError).domain, "PHPhotosErrorDomain") }
        }) { identifier in XCTAssertNil(identifier); completed.fulfill() }
        await fulfillment(of: [completed], timeout: 10)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path), ["test.churlive"])
        XCTAssertEqual(try LivePhotoBundleFile.read(source).resources.count, 3)
    }

    func testPrecancelLeavesNoPartialPlaintext() async throws {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        XCTAssertTrue(status == .authorized || status == .limited)
        guard status == .authorized || status == .limited else { return }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let capture = LivePhotoCapture(); capture.cancel()
        let result = expectation(description: "Cancelled")
        let created = expectation(description: "Cancellation fixture")
        var fixtureID: String?
        let photo = try fixture("still.jpg"), movie = try fixture("motion.mov")
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, fileURL: photo, options: nil)
            request.addResource(with: .pairedVideo, fileURL: movie, options: nil)
            fixtureID = request.placeholderForCreatedAsset?.localIdentifier
        }) { success, _ in XCTAssertTrue(success); created.fulfill() }
        await fulfillment(of: [created], timeout: 30)
        let asset = try XCTUnwrap(PHAsset.fetchAssets(withLocalIdentifiers: [try XCTUnwrap(fixtureID)], options: nil).firstObject)
        capture.capture(asset: asset, directory: folder, limit: 1024, shouldContinue: { false }) { url in XCTAssertNil(url); result.fulfill() }
        await fulfillment(of: [result], timeout: 10)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }
}
