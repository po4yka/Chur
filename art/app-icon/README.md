# Chur mirror charm

The selected icon is a silver mirror repelling a small horned charcoal spirit.
The reflected silhouette explains the protective boundary. The palette uses
DESIGN.md neutrals; the previous launcher artwork is replaced.

## Editable Apple source

`../../apps/iosApp/ChurMirror.icon` is the native document created, saved,
closed, and reopened in Icon Composer 27. It has two groups and four aligned
1024 × 1024 transparent sRGB PNG sources:

- `Mirror assembly`: `01-mirror`, `02-reflection`, and `03-spirit`.
- `Mono contrast`: `04-mono`, visible only for the Mono appearance.

Only the mirror receives native glass/specular treatment. Blur, refraction,
translucency, and additional shadows are disabled. System Dark supplies the
background. Mono uses a simplified contrast adaptation; it does not reuse the
silver shading. Keep the appearance opacity overrides when editing. The system
supplies the final mask; source layers have no rounded-square tile.

The iOS target references the native document through `project.yml` and uses
`ChurMirror` as its app-icon name. Its deployment target remains iOS 18.0.
Xcode provides older-system representations from the native icon, as described
in [Apple’s document workflow](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer).

## Artwork and Android exports

`selected-concept.png` is the exact user-selected concept. `generated/` retains
the four unmodified generated layer originals. `layer-prompts.json` contains the
exact edit prompts. Bounds with alpha greater than 24 were cropped and
proportionally fitted to these rectangles
on the shared 1024px canvas (left, top, right, bottom): mirror `(112,113,774,905)`,
reflection `(515,332,697,728)`, spirit `(681,309,912,718)`, and Mono
`(112,113,912,905)`. The aligned production images are embedded in the `.icon`.
`preview.png` is a native Default export for review, not an application fallback.

Run `python3 art/app-icon/export_android.py` with Pillow installed to reproduce
the Android resources. The exporter reads the native document’s PNG sources,
composites the three color layers, derives the one-color alpha mask from the
Mono regions, and produces five adaptive densities (108, 162, 216, 324, 432px).
The outside spirit has transparent eyes; the reflection is negative space with
positive eyes. `android-foreground.png` and `android-monochrome.png` retain the
full-size composites. A 72% scene scale keeps meaningful alpha within a 31dp
radius on Android’s 108dp canvas. The launcher background is `#0A0A0A`.

## Visual checks

Default, Dark, and Mono were inspected in Icon Composer with effects 26 and 27.
All six native exports (Default, Dark, Clear Light/Dark, Tinted Light/Dark) were
reviewed together in a comparison sheet and reduced to 60px and 32px. Default was also inspected
against image wallpaper. Android circle, rounded-square, and square masks were
reviewed at 48px and 32px. These are artwork checks; device and distribution
acceptance still require the normal application release checks.

XcodeGen regeneration, plist/project syntax, PNG dimensions, transparency,
single-color Android mask, and `git diff --check` passed on 2026-10-18.
The iOS Simulator build and Android `:apps:androidApp:processDebugResources`
were attempted through `build-gate`; each exited 75 after waiting 600 seconds
for a slot. Neither compiler ran. Repeat those checks when a slot is available;
the artwork previews do not establish application-build or store acceptance.
