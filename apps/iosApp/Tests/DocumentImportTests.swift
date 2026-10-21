import Darwin
import Foundation
import UniformTypeIdentifiers
import XCTest
@testable import ChurScratch

final class DocumentImportTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }

    func testKeepsOriginalNameTypeAndBytesWithoutScratchCopy() throws {
        let url = root.appendingPathComponent("Original report.pdf")
        try Data([1, 2, 3]).write(to: url)
        let stopped = expectation(description: "scope released after source close")
        let selected = DocumentImportSelection(urls: [url], startAccess: { _ in true }, stopAccess: { _ in stopped.fulfill() })
        selected.load(index: 0) { source in
            guard let source else { XCTFail("Source not opened"); return }
            XCTAssertEqual(source.name, "Original report.pdf")
            XCTAssertEqual(source.contentType, "application/pdf")
            XCTAssertEqual(source.size, 3)
            var data = [UInt8](repeating: 0, count: 3)
            XCTAssertEqual(Darwin.read(source.descriptor, &data, data.count), 3)
            XCTAssertEqual(data, [1, 2, 3])
            // This also exercises cancellation cleanup called inline by a
            // cancelled Kotlin continuation; it cannot wait on this accessor.
            source.close(); source.close()
        }
        wait(for: [stopped], timeout: 5)
        selected.close()
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), ["Original report.pdf"])
    }

    func testAudioLeaseProvidesReadableURLAndOriginalTypeUntilRelease() throws {
        // Two PCM16 mono samples in a valid 8 kHz WAV container.
        let bytes = Data([
            82, 73, 70, 70, 40, 0, 0, 0, 87, 65, 86, 69,
            102, 109, 116, 32, 16, 0, 0, 0, 1, 0, 1, 0,
            64, 31, 0, 0, 128, 62, 0, 0, 2, 0, 16, 0,
            100, 97, 116, 97, 4, 0, 0, 0, 0, 0, 0, 0,
        ])
        let url = root.appendingPathComponent("Recording.wav")
        try bytes.write(to: url)
        let stopped = expectation(description: "audio scope released")
        let selected = DocumentImportSelection(urls: [url], startAccess: { _ in true }, stopAccess: { _ in stopped.fulfill() })
        selected.load(index: 0) { source in
            guard let source else { XCTFail("Audio source not opened"); return }
            XCTAssertEqual(source.name, "Recording.wav")
            XCTAssertEqual(source.readablePath, url.path)
            XCTAssertEqual(source.contentType, UTType.wav.preferredMIMEType)
            XCTAssertEqual(try? Data(contentsOf: URL(fileURLWithPath: source.readablePath)), bytes)
            selected.close()
            XCTAssertEqual(try? Data(contentsOf: URL(fileURLWithPath: source.readablePath)), bytes)
            source.close()
        }
        wait(for: [stopped], timeout: 5)
    }

    func testJpegXlMediaFileLeaseProvidesReadableOriginalUntilCodecReleasesIt() throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let original = try Data(contentsOf: repository.appendingPathComponent("tests/media/jpeg-xl/still-container.jxl"))
        // The unrestricted media picker can return a format that the OS has not registered.
        let url = root.appendingPathComponent("Provider copy.bin")
        try original.write(to: url)
        let stopped = expectation(description: "media file scope released after codec")
        let selected = DocumentImportSelection(urls: [url], startAccess: { _ in true }, stopAccess: { _ in stopped.fulfill() })
        selected.load(index: 0) { source in
            guard let source else { XCTFail("Media file source not opened"); return }
            XCTAssertEqual(source.readablePath, url.path)
            XCTAssertEqual(source.name, "Provider copy.bin")
            XCTAssertEqual(source.size, Int64(original.count))
            selected.close()
            XCTAssertGreaterThanOrEqual(fcntl(source.descriptor, F_GETFD), 0)
            XCTAssertEqual(try? Data(contentsOf: URL(fileURLWithPath: source.readablePath)), original)
            source.close()
        }
        wait(for: [stopped], timeout: 5)
    }

    func testAnActiveDescriptorStaysValidUntilNativeImportReleasesIt() throws {
        let url = root.appendingPathComponent("document.txt")
        try Data([1]).write(to: url)
        let stopped = expectation(description: "closed scope")
        let selected = DocumentImportSelection(urls: [url], startAccess: { _ in true }, stopAccess: { _ in stopped.fulfill() })
        selected.load(index: 0) { source in
            guard let source else { XCTFail("Source not opened"); return }
            selected.close()
            XCTAssertGreaterThanOrEqual(fcntl(source.descriptor, F_GETFD), 0)
            source.close()
        }
        wait(for: [stopped], timeout: 5)
        XCTAssertEqual(selected.count, 0)
        let refused = expectation(description: "no retained selection")
        selected.load(index: 0) { source in XCTAssertNil(source); refused.fulfill() }
        wait(for: [refused], timeout: 1)
    }

    func testLargeFileUsesProviderDescriptorWithoutTheMediaScratchLimit() throws {
        let url = root.appendingPathComponent("large.bin")
        let fd = Darwin.open(url.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        XCTAssertGreaterThanOrEqual(fd, 0)
        let size: Int64 = 5 * 1024 * 1024 * 1024
        XCTAssertEqual(ftruncate(fd, size), 0)
        Darwin.close(fd)
        let stopped = expectation(description: "large descriptor released")
        let selected = DocumentImportSelection(urls: [url], startAccess: { _ in true }, stopAccess: { _ in stopped.fulfill() })
        selected.load(index: 0) { source in
            XCTAssertEqual(source?.size, size)
            XCTAssertEqual(source?.name, "large.bin")
            source?.close()
        }
        wait(for: [stopped], timeout: 5)
        selected.close()
    }

    func testRejectsDirectoriesAndOversizedFilesAndBalancesTheirGrants() throws {
        let url = root.appendingPathComponent("too-large.bin")
        let fd = Darwin.open(url.path, O_WRONLY | O_CREAT | O_EXCL, S_IRUSR | S_IWUSR)
        XCTAssertGreaterThanOrEqual(fd, 0)
        XCTAssertEqual(ftruncate(fd, (1 << 40) + 1), 0)
        Darwin.close(fd)
        let stopped = expectation(description: "rejected grants stopped")
        stopped.expectedFulfillmentCount = 2
        let selected = DocumentImportSelection(urls: [root, url], startAccess: { _ in true }, stopAccess: { _ in stopped.fulfill() })
        for index in 0..<2 { selected.load(index: index) { XCTAssertNil($0) } }
        wait(for: [stopped], timeout: 5)
        selected.close()
    }

    func testDeniedAndCancelledSelectionsDoNotOpenAProviderSource() throws {
        let denied = expectation(description: "denied")
        let selected = DocumentImportSelection(urls: [root], startAccess: { _ in false }, stopAccess: { _ in XCTFail("No grant to stop") })
        selected.load(index: 0) { XCTAssertNil($0); denied.fulfill() }
        wait(for: [denied], timeout: 5)
        selected.close()
        selected.load(index: 0) { XCTAssertNil($0) }
    }

    func testCancelWhileWaitingForAProviderReadAnswersOnceAndReleasesTheGrant() throws {
        try checkWaitingReadCancellation(closeSelection: true)
    }

    func testPrepareCancellationWakesAReadWaitingBehindAnotherCoordinatedWriter() throws {
        try checkWaitingReadCancellation(closeSelection: false)
    }

    private func checkWaitingReadCancellation(closeSelection: Bool) throws {
        let url = root.appendingPathComponent("waiting.txt")
        try Data([1]).write(to: url)
        let writerReady = DispatchSemaphore(value: 0)
        let releaseWriter = DispatchSemaphore(value: 0)
        let writerFinished = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            var error: NSError?
            NSFileCoordinator().coordinate(writingItemAt: url, options: [], error: &error) { _ in
                writerReady.signal()
                releaseWriter.wait()
            }
            writerFinished.signal()
        }
        XCTAssertEqual(writerReady.wait(timeout: .now() + 5), .success)
        defer { releaseWriter.signal(); XCTAssertEqual(writerFinished.wait(timeout: .now() + 5), .success) }
        let started = expectation(description: "grant started")
        let stopped = expectation(description: "cancelled grant stopped")
        let answered = expectation(description: "pending read cancelled")
        answered.assertForOverFulfill = true
        let flag = DocumentImportCancelFlag()
        let selected = DocumentImportSelection(urls: [url], startAccess: { _ in started.fulfill(); return true }, stopAccess: { _ in stopped.fulfill() })
        selected.load(index: 0, shouldContinue: { flag.allowed }) { source in
            XCTAssertNil(source)
            source?.close()
            answered.fulfill()
        }
        wait(for: [started], timeout: 5)
        if closeSelection { selected.close() } else { flag.cancel() }
        wait(for: [answered, stopped], timeout: 5)
        selected.close()
    }

    func testMIMEUsesProviderOrOriginalNameAndHasAnOpaqueFallback() {
        XCTAssertEqual(DocumentImportSelection.contentType(provider: .pdf, originalFilename: "random.bin"), "application/pdf")
        XCTAssertEqual(DocumentImportSelection.contentType(provider: nil, originalFilename: "Original.pdf"), "application/pdf")
        XCTAssertEqual(DocumentImportSelection.contentType(provider: nil, originalFilename: "unknown.zzz-unregistered"), "application/octet-stream")
    }
}

private final class DocumentImportCancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var current = true
    var allowed: Bool { lock.lock(); defer { lock.unlock() }; return current }
    func cancel() { lock.lock(); current = false; lock.unlock() }
}
