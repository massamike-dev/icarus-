# ICARUS 1.6.20 — Voice, Account & HUD update

Android test update, build 51. Download `ICARUS-xreal-preview.apk` and install over the previous signed ICARUS app. Do not uninstall or clear app storage if you want to retain local memories and chats. The same application ID and existing release signer are retained, with a higher version code.

## Changes

- Separate ICARUS speech language and named installed-voice choices. Morgan — Warm Narrator is the default delivery preset; existing explicit preferences are preserved. This is a style applied to installed speech voices, not a Morgan Freeman recording or clone. Changing ICARUS preferences does not alter Android system settings.
- Visible Sign in entry opens account access. Continue to the configured cloud workspace to sign in and view that account's memories. Switching does not upload device memories or transfer records between workspaces.
- Memory identifies device versus account storage, confirms saves, and distinguishes loading, empty and failed-read states. Refresh supports retries without deleting saved data.
- Cleaner racing-inspired native speed gauge and an interactive phone-map/glasses-HUD design preview with a cyan sample route line.

## Remaining limits

The map preview uses fictional roads and sample values. Embedded live maps and a live route line are not connected; actual map guidance continues in the external maps app. XREAL remains a stationary prototype, not for use while operating a vehicle. Physical installation, speech quality/language availability, live account recovery and glasses output require device testing.

This release does not change main, the production website, the public update feed or Google Play. The release pipeline gates signing on web and native checks, browser QA, the existing signer, unchanged Android permissions and bundled-UI verification. SHA256SUMS, SOURCE_COMMIT.txt and verification.json identify the delivered artifact.
