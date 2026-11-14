# Turtle Keyboard launcher artwork

App and IME display name: **烏龜鍵盤 － 廣東話** in every supported locale.

- `foreground-source.png`: transparent turtle/keyboard cutout, adapted from the supplied reference using the built-in imagegen tool.
- `play-store-icon.png`: opaque 512 × 512 PNG with a full ivory background. No pre-applied corners or external shadow.
- `preview-circle.png` and `preview-themed.png`: illustrative launcher mask previews. The themed palette is an example; Android selects actual colors.
- `export.swift`: deterministic export of the selected cutout into Android resources and store artwork.

Adaptive foreground and monochrome layers use a 432 × 432 transparent canvas, equivalent to 108dp at xxxhdpi. All meaningful artwork stays inside a centered 65dp diameter circle, within Android's 66dp safe zone. The ivory background fills the entire layer; the launcher supplies the mask. The monochrome layer uses the artwork's outlines, facial features, and keyboard keys as an alpha mask. Legacy square and round icons are exported at 48, 72, 96, 144, and 192 pixels.

Regenerate from the repository root:

```sh
swift -module-cache-path /private/tmp/keyboard-swift-cache artwork/launcher/export.swift artwork/launcher/foreground-source.png artwork/launcher
```

Reference: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive

Built-in imagegen edit prompt:

> Edit target: supplied turtle keyboard icon. Remove ONLY the ivory rounded-square tile and all background/outside edge artifacts. Preserve the EXACT turtle, hat, face, hands and keyboard artwork, colors, thick dark brown outlines, proportions, details. Produce a clean high resolution transparent-background cutout of the complete turtle and keyboard, centered with modest transparent margin. No tile, no border, no new text, no shadow outside the artwork. This is a foreground layer for an Android adaptive app icon. Keep all white keyboard keys and hat stripes opaque; remove background only.
