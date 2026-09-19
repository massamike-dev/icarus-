# ICARUS Web Design

ICARUS is a rugged command instrument for Michael: calm enough for daily use, precise enough for a vehicle cockpit, and recognizably built from steel, ember, and sky rather than generic AI neon.

## Runtime tokens

The canonical tokens live in `src/styles.css` under `:root`: Ink `#071019`, Panel `#0b1722`, Panel Raised `#101f2b`, Steel `#8fa5b5`, White `#f4f7f8`, Ember `#df6b2d`, Sky `#62c6e8`, Focus `#ffd18d`.

Typography pairs condensed, mechanical Barlow Condensed for identity and headings with Manrope for readable controls and copy. The signature element is the orbital W command core: a restrained instrument, not decorative sci-fi chrome.

Navigation labels and system states remain consistent across mobile and desktop. Motion is limited to the listening state and is disabled when reduced motion is requested. WCAG 2.2 AA keyboard focus and semantic controls are baseline requirements.

The full-body ICARUS companion retains the winged W helmet, navy armor, gold trim, and blue eyes. Keep him compact above mobile navigation, with explicit left/right placement and hide/restore controls. Tuck him while editing; never cover the composer or widen the viewport. Voice status reflects the native bridge, and Talk now requires a deliberate tap. Reduced motion disables decorative animation.

Settings prioritizes the controls people can actually use: saved voice, listening sensitivity, local model, account and privacy; patch notes follow the controls. Use Android's installed voice catalog and a preview, with one saved configuration for app speech and hands-free replies. Deep & warm describes vocal qualities, not a promised speaker identity. Native selects use the operating system's accessible picker. Save acknowledgements, pending state and recoverable errors must remain visible; unsupported builds must say an update is needed rather than present controls that cannot work.
