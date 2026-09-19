# Settings interaction contract

This contract governs the restored device settings workflow. Existing auth, Memory and Chat audit findings remain tracked separately; this document does not declare those flows compliant.

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Select/Listbox | Native `select` in `src/settings-preferences.jsx` | DESIGN.md; Android installed voice catalog | Platform-owned picker geometry and keyboard behavior | Settings component tests; phone voice preview |
| Form | Settings preference components and `src/native-settings.js` | Native voice validation and acknowledged saves | Voice settings; sensitivity | Exact-result, failure and draft tests |
| Scrollbar | `src/styles.css` and `src/mobile.css` | DESIGN.md runtime tokens | Document scroll; bounded settings dialog | Static review; phone layout check |
| Toast | Inline `settings-status` live region | Request-correlated native response | Pending, success and error | Settings acknowledgement tests |
| CRUD | Native device preference and local-model actions | Android bridge and local-model manager | Persist voice; update sensitivity; download/remove model | Component tests and Android checks |

## Evidence and behavior

Android `IcarusNativeBridge`, `VoicePreferences`, `VoiceSettings`, `WakeWordService` and `LocalModelManager` define the actual actions and lifecycle. `src/device-actions.js` owns native subscription dispatch; `src/native-settings.js` correlates settings requests and bounds their wait.

Voice selection, rate and pitch are local device preferences, distinct from account history. Explicit Save reports success only after Android confirms the selected values; failures preserve the draft. A tab change retains the draft for this session. The preview uses saved values. Unsupported hosts explain the required update. An unavailable speech engine exposes Android's settings shortcut.

Listening requires the existing explicit hands-free consent and Android microphone permission. Saving voice or sensitivity never enables listening. Sensitivity changes do not interrupt an active command; the acknowledged response explains when the listener must restart.

Deleting a local model requires a named confirmation with the consequence and Cancel initially focused. Android's response determines completion. Download progress reflects actual bytes, and downloaded-but-unverified is distinct from ready. These controls do not promise automatic offline Chat or hands-free fallback.

Use visible labels, semantic buttons, native slider/select keyboard behavior, bounded dialogs and inline recoverable errors. Keep the existing visual identity and focus styles. Browser/phone rendering and actual speech remain device acceptance checks, not conclusions inferred from DOM tests.

## HUD controls

`src/vehicle.jsx` uses the same inline status, button and dialog styles as Settings. `src/native-settings.js` owns correlated native requests for both surfaces, including bounded waits and optional cancellation on unmount. `src/hud-controls.js` validates action-specific acknowledgements. No new native callback owner is introduced.

The installed bridge must advertise `hudControlVersion: 1` before these controls send HUD actions. The route-setup control additionally requires the advertised `open_navigation_access_settings` capability, keeping older Test builds usable without sending an unsupported action. XREAL is opt-in with named enable/disable confirmation; cancellation sends nothing. Enabling does not enable the microphone or confirm glasses connection. A launch acknowledgement confirms only Android accepted a launch, not that glasses rendered it. The bundled screen-fixed overlay and separate tracked Unity companion are distinct targets. Unknown telemetry and navigation never become sample data.

Route setup is a parked phone task. The control opens Android's notification-access settings and explains the platform-level privacy scope before leaving ICARUS. The native route bridge filters notifications to active Google Maps and Waze guidance, displays only a parsed maneuver and distance, clears stale data when access or the listener disappears, and never invents route details. During driving, navigation owns the central safe view; secondary diagnostics remain peripheral.

Phone fallback remains independent of the XREAL toggle. A failed status read blocks XREAL launch but does not hide the phone HUD once native support is confirmed. Requests are never retried automatically. Success, malformed acknowledgement, timeout, duplicate input, old host and unmount are covered by HUD tests.

## Private installer download

`/test-download` retains the normal account sign-in gate and uses the existing restricted APK API. `src/private-download.js` verifies private-service identity, deployed commit, complete byte count, APK archive prefix and SHA-256 before offering a browser save. Tokens stay in authorization headers, never links. Cancellation, bounded waits, duplicate protection and unmount cleanup are owned by `src/private-download.jsx`. A browser save request is not an installation or a verified device upgrade. Public mode never offers the private installer.
