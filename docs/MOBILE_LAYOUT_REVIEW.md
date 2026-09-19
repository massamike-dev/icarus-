# Mobile layout correction — held for the next update

Reviewed September 18, 2026. No deployment or public release is part of this correction.

## Observed on the phone

The supplied silent recording shows the Android native connection confirmed, microphone permission allowed, LISTENING reported by Android, and a persistent wake-listener notification. It does not demonstrate an actual wake phrase, screen-off wake response, or a completed phone command. It also does not display a sign-in failure or identify the exact installed build.

The page can pan sideways and the listening-consent dialog is cut off on the right.

## Correction

The mobile decorative artwork was absolutely positioned 105–145 CSS pixels beyond the viewport, with no clipping ancestor. Its fixed, noninteractive wrapper now clips the artwork while the image retains its size, placement and animation. Interactive content is not globally clipped. The shared listening dialog is constrained to viewport width, and mobile navigation and overview grid tracks can shrink and wrap.

The same VoiceControls dialog is used on Command, Voice, Vehicle and Settings. Its confirmation, cancellation, focus handling and Android commands are unchanged. In-app patch notes include the layout correction for the eventual release.

## Verification and remaining checks

- Production web build passed after the CSS change.
- All three existing navigation/native-control regression tests passed, including repeated tab navigation and single dispatch after confirmation.
- Source review independently identified the overflowing decoration and checked the repair.
- Browser visual validation remains pending: the cloud browser URL security policy blocked the isolated layout fixture. No attempt was made to work around that block.
- The strict design audit still reports three pre-existing form/textarea findings in main.jsx and chat.jsx. This CSS repair does not constitute a full design-audit pass.
- Recheck page panning, all six navigation buttons, dialog readability, Cancel/Escape/focus restoration and enlarged text on a phone before release. Verify an actual wake response separately.

The delivered private APK and both deployed servers remain unchanged. This correction is held with the accumulated fixes until the next authorized update.
