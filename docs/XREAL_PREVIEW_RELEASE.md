# ICARUS 1.6.16 XREAL Preview

This is an experimental Android test APK for stationary testing only. Do not use this preview while driving or for safety-critical guidance. Physical XREAL output, hardware compatibility, and in-place installation have not yet been validated on a device.

## Test scope

- A restored phone dashboard and a fuller XREAL driving-style HUD prototype, using shared telemetry and explicit stale-data handling.
- Local-first chat and hands-free command behavior, with a bundled offline interface.
- Phone-secondary Google Maps route-planning drafts. This is not a complete navigation system or a validated turn-by-turn XREAL navigation experience.

The release build checks the APK signature and requires the same signer certificate and application ID as the checksum-pinned public `v1.6.15-test` APK. Version code 47 is greater than the baseline's 46. It rejects added Android permissions and verifies that the offline UI is packaged. These checks do not substitute for installation or physical-device testing.

## Download and installation

Use `ICARUS-xreal-preview.apk`. `SHA256SUMS`, `verification.json`, and `SOURCE_COMMIT.txt` identify the exact APK bytes, signer comparison, and source commit. Keep any important app data backed up before testing. The APK shares the existing ICARUS package, so it is intended to upgrade the prior app rather than install alongside it. Android may prevent downgrading to a lower version code; restoring the older build may require uninstalling and losing local app data.

## Build boundaries

The dedicated workflow accepts only `work/icarus-xreal-preview-20261014` in `massamike-dev/icarus-`. Web tests, all native source contracts, private-delivery regressions, release JVM tests, and release lint must pass before signing. Signing and publication each require a current open draft PR at the workflow's exact commit.

Only the final publication job has repository write permission. It creates the new `v1.6.16-xreal-preview` prerelease, excludes it from latest-release selection, and refuses to reuse an existing tag or release. It does not produce a store bundle, modify the public update feed, or push to a branch. Signing uses the existing repository secrets; temporary signing material is removed even if a build step fails.

If the first branch push runs before its draft PR is open, open the draft PR and rerun the workflow on that exact branch/commit. A successful existing preview release is immutable: subsequent fixes require an explicitly chosen new version and tag.

## Dependency review

Vite is pinned to 8.3.3, which patches the maintainer's October 6 development-server advisories (GHSA-rq7h-c2jc-7f22, GHSA-vfpm-58rq-9qcg, GHSA-9jrq-w75r-8gcw). The installed APK uses generated static assets rather than a Vite development server. Automatic npm audit transmission is disabled during dependency installation. The high-severity source-map-js indexed-source-map denial-of-service advisory (GHSA-68fv-2mgg-jv7q) is patched with source-map-js 1.2.2. An npm advisory check is part of the current release review; passing it is not a comprehensive security assessment or production sign-off.
