import ChurApp
import UIKit

@MainActor
func configureAdaptiveBars() {
    IosAdaptiveBars.shared.configureCustomItem = { item in
        if let button = item.customView as? UIButton {
            let title = button.accessibilityLabel ?? ""
            let image = button.image(for: .normal)
            item.title = title
            item.image = image
            // A custom view's action belongs to its UIButton. Supply a real
            // overflow command that uses the same guarded action and owner.
            item.menuRepresentation = UIAction(title: title, image: image) { [weak button] _ in
                button?.sendActions(for: .touchUpInside)
            }
        }
        if #available(iOS 27.1, *) {
            item.axisBehavior = .verticalPreferred
            item.visibilityPriority = .high
        }
    }
}

/// Observes geometry without changing the root, navigation or modal presentation owner.
@MainActor
func installAdaptiveGeometry(on controller: UIViewController) {
    let observer = ReservedRegionView(frame: controller.view.bounds)
    observer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    observer.onRegions = { [weak controller] occlusions, divisions in
        guard let controller else { return }
        IosEntryPointKt.churUpdateLayout(controller: controller,
            occlusions: occlusions, divisions: divisions)
    }
    controller.view.addSubview(observer)
}

private final class ReservedRegionView: UIView {
    var onRegions: (([AdaptiveRegion], [AdaptiveRegion]) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        setNeedsUpdateConstraints()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func updateConstraints() {
        // UIKit tracks these reads and updates constraints when regions change,
        // including a hinge transition with unchanged window bounds.
        publishRegions()
        super.updateConstraints()
    }

    override func layoutSubviews() {
        publishRegions()
        super.layoutSubviews()
    }

    private func publishRegions() {
        guard #available(iOS 27.1, *) else { return }
        func regions(_ kind: UIView.ReservedRegion.Kind) -> [AdaptiveRegion] {
            reservedRegions(kind: kind).map { region in
                let frame = region.frame
                return AdaptiveRegion(x: frame.minX, y: frame.minY,
                    width: frame.width, height: frame.height)
            }
        }
        onRegions?(regions(.occlusion), regions(.division))
    }
}
