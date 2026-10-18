import Foundation

/// A picker file can contain only one part of a Photos asset. Keep deletion
/// tied to the loaded index and require a fresh resource check before deletion.
final class MediaOriginalSafety {
    enum Kind: Equatable { case photo, video, unknown }
    struct Item {
        let assetIdentifier: String?
        let kind: Kind
        let isCompound: Bool
    }
    struct Resource {
        let kind: Kind
        let typeIdentifier: String
    }

    private let items: [Item]
    private var loadedTypes: [Int: String] = [:]

    init(items: [Item]) { self.items = items }

    func recordLoaded(index: Int, typeIdentifier: String?) {
        guard items.indices.contains(index) else { return }
        loadedTypes[index] = typeIdentifier
    }

    func canReview(index: Int) -> Bool {
        guard items.indices.contains(index), loadedTypes[index] != nil else { return false }
        let item = items[index]
        return item.assetIdentifier?.isEmpty == false && item.kind != .unknown && !item.isCompound
    }

    func candidates(indices: [Int]) -> [(index: Int, identifier: String)] {
        var identifiers = Set<String>()
        return indices.compactMap { index in
            guard canReview(index: index), let identifier = items[index].assetIdentifier,
                  identifiers.insert(identifier).inserted else { return nil }
            return (index, identifier)
        }
    }

    func canDelete(index: Int, isLivePhoto: Bool, resources: [Resource]) -> Bool {
        guard canReview(index: index), !isLivePhoto, resources.count == 1,
              let resource = resources.first else { return false }
        return resource.kind == items[index].kind && resource.typeIdentifier == loadedTypes[index]
    }
}
