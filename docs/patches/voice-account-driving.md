# Next preview patch: voice, accounts, Memory and driving graphics

This draft builds on the 1.6.19 blue-glass preview; no replacement APK is published.

## Included

- Independent speech-language preference; human-readable installed voice aliases; Morgan — Warm Narrator names the existing default warm delivery preset. This uses the installed Android TTS engine, not a celebrity voice or a separate neural voice service. Existing preferences survive.
- Visible Sign in in the local app header opens the account panel. Cloud sign-in remains an explicit mode transition; it does not transfer or erase device data. The hosted workspace handles authentication.
- Memory identifies device versus account scope. Loading, read failure and confirmed empty states are distinct. Save acknowledgement is checked; failed saves retain input. Refresh supports recovery without clearing storage. New UI tests cover persistence across a fresh workspace and corrupt-data preservation.
- Native phone/XREAL speed cluster uses a simpler racing-inspired arc and larger italic speed typography. Existing measured-data, stale-state and clear-center contracts remain intact.
- Vehicle includes an opt-in, interactive map/HUD design preview with fictional roads and a sample cyan route line. It does not consume location, emit navigation actions, or claim live telemetry.

## Remaining release gates

- Android compile, JVM tests, lint and on-device install/upgrade verification.
- Verify actual TTS voices and language fallback on Samsung and Beam Pro.
- Verify sign-in, password recovery and account memories against the chosen live workspace. This patch does not copy account records between services.
- Device memory persistence through force-close, restart and app upgrade; local data is still lost if app storage is cleared or the app is uninstalled.
- Visual and hardware review of native HUD. XREAL remains for stationary evaluation.
- Embedded live maps and route line are NOT implemented: the existing navigation feed has maneuver/distance text but no route geometry. A map/routing provider, geometry integration and route lifecycle handling are prerequisites. The sample line is not a racing/braking/lane-position recommendation.

No main merge, production deployment, Play rollout or signed release is included.
