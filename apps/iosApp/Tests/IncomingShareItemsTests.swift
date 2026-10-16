import Foundation
import UniformTypeIdentifiers
import XCTest
@testable import ChurScratch

final class IncomingShareItemsTests: XCTestCase {
    func testFileAndCaptionAreBothAvailableToEncrypt() throws {
        let item = NSExtensionItem()
        let file = NSItemProvider(item: Data([1, 2, 3]) as NSData, typeIdentifier: UTType.data.identifier)
        item.attachments = [file]
        item.attributedContentText = NSAttributedString(string: "A caption")
        let providers = IncomingShareItems.providers(from: [item])
        XCTAssertEqual(providers.count, 2)
        XCTAssertTrue(providers[0] === file)
        let loaded = expectation(description: "caption")
        providers[1].loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { value, error in
            XCTAssertNil(error)
            let text = (value as? String) ?? (value as? Data).flatMap { String(data: $0, encoding: .utf8) }
            XCTAssertEqual(text, "A caption")
            loaded.fulfill()
        }
        wait(for: [loaded], timeout: 5)
    }
    func testCaptionWithoutAttachmentsAndEmptyBody() {
        let text = NSExtensionItem(); text.attributedContentText = NSAttributedString(string: "Shared text")
        let empty = NSExtensionItem(); empty.attributedContentText = NSAttributedString(string: "")
        XCTAssertEqual(IncomingShareItems.providers(from: [text, empty]).count, 1)
    }
    func testCaptionsCountTowardsTheHardRequestBound() {
        let item = NSExtensionItem()
        item.attachments = (0..<IncomingShareInbox.itemLimit).map { _ in NSItemProvider(item: Data([1]) as NSData, typeIdentifier: UTType.data.identifier) }
        item.attributedContentText = NSAttributedString(string: "Caption")
        XCTAssertGreaterThan(IncomingShareItems.providers(from: [item]).count, IncomingShareInbox.itemLimit)
    }
    func testExactRepeatedTextIsDeduplicatedButDifferentCaptionsAndUrlsRemain() {
        let seen = IncomingShareTextSet()
        XCTAssertTrue(seen.insert("https://example.com"))
        XCTAssertFalse(seen.insert("https://example.com"))
        XCTAssertTrue(seen.insert("https://example.com\n"))
        XCTAssertTrue(seen.insert("Different caption"))
        XCTAssertTrue(seen.insert("Приватный текст"))
        XCTAssertFalse(seen.insert("Приватный текст"))
    }
}
