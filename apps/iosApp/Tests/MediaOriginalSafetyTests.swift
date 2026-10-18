import XCTest
@testable import ChurScratch

final class MediaOriginalSafetyTests: XCTestCase {
    private func safety(kind: MediaOriginalSafety.Kind = .photo, compound: Bool = false, id: String? = "asset") -> MediaOriginalSafety {
        MediaOriginalSafety(items: [.init(assetIdentifier: id, kind: kind, isCompound: compound)])
    }

    func testLivePhotoStillCannotOfferOrDeleteItsOriginal() {
        let selected = safety(compound: true)
        selected.recordLoaded(index: 0, typeIdentifier: "public.heic")
        XCTAssertFalse(selected.canReview(index: 0))
        XCTAssertFalse(selected.canDelete(index: 0, isLivePhoto: false,
            resources: [.init(kind: .photo, typeIdentifier: "public.heic")]))
    }

    func testFreshLivePhotoMetadataBlocksDeletionEvenWhenProviderOmitsIt() {
        let selected = safety()
        selected.recordLoaded(index: 0, typeIdentifier: "public.heic")
        XCTAssertFalse(selected.canDelete(index: 0, isLivePhoto: true,
            resources: [.init(kind: .photo, typeIdentifier: "public.heic")]))
    }

    func testOnlyLoadedIndexCanBeReviewedAndDeleted() {
        let selected = MediaOriginalSafety(items: [
            .init(assetIdentifier: "first", kind: .photo, isCompound: false),
            .init(assetIdentifier: "second", kind: .photo, isCompound: false),
        ])
        selected.recordLoaded(index: 1, typeIdentifier: "public.jpeg")
        XCTAssertFalse(selected.canReview(index: 0))
        XCTAssertTrue(selected.canReview(index: 1))
        XCTAssertTrue(selected.canDelete(index: 1, isLivePhoto: false,
            resources: [.init(kind: .photo, typeIdentifier: "public.jpeg")]))
        XCTAssertEqual(selected.candidates(indices: [-1, 0, 1, 1, 2]).map(\.identifier), ["second"])
    }

    func testUnknownAndMissingIdentifiersFailClosed() {
        for selected in [safety(kind: .unknown), safety(id: nil), safety(id: "")] {
            selected.recordLoaded(index: 0, typeIdentifier: "public.jpeg")
            XCTAssertFalse(selected.canReview(index: 0))
        }
    }

    func testFailedOrRepeatedLoadCannotRetainDeletionEligibility() {
        let selected = safety()
        XCTAssertFalse(selected.canReview(index: 0))
        selected.recordLoaded(index: 0, typeIdentifier: "public.jpeg")
        XCTAssertTrue(selected.canReview(index: 0))
        selected.recordLoaded(index: 0, typeIdentifier: nil)
        XCTAssertFalse(selected.canReview(index: 0))
        selected.recordLoaded(index: -1, typeIdentifier: "public.jpeg")
        XCTAssertFalse(selected.canReview(index: -1))
    }

    func testAlternateEditedUnknownAndDifferentRepresentationsCannotBeDeleted() {
        let selected = safety()
        selected.recordLoaded(index: 0, typeIdentifier: "public.jpeg")
        for resources: [MediaOriginalSafety.Resource] in [
            [],
            [.init(kind: .photo, typeIdentifier: "public.heic")],
            [.init(kind: .unknown, typeIdentifier: "public.jpeg")],
            [.init(kind: .video, typeIdentifier: "public.jpeg")],
            [.init(kind: .photo, typeIdentifier: "public.jpeg"), .init(kind: .photo, typeIdentifier: "com.adobe.raw-image")],
            [.init(kind: .photo, typeIdentifier: "public.jpeg"), .init(kind: .unknown, typeIdentifier: "com.apple.adjustment")],
        ] {
            XCTAssertFalse(selected.canDelete(index: 0, isLivePhoto: false, resources: resources))
        }
    }

    func testOrdinaryVideoRemainsEligible() {
        let selected = safety(kind: .video)
        selected.recordLoaded(index: 0, typeIdentifier: "public.mpeg-4")
        XCTAssertTrue(selected.canDelete(index: 0, isLivePhoto: false,
            resources: [.init(kind: .video, typeIdentifier: "public.mpeg-4")]))
    }
}
