# Driving HUD reference restoration

## Source and intent

The supplied windshield concept (`5345(3).png`, re-uploaded unchanged as `6291.png`) was inspected as pixels before implementation. This change restores its visual hierarchy in the shared native `CinematicDrivingHudView` used by the actual XREAL vehicle Activity and phone adapter, using native-drawn instruments plus a bundled transparent pickup texture. It does not replace the native screen with a concept-image background.

The original concept combines a large blue/gold speed dial at left, a high turn card, an unobstructed central roadway, low glass telemetry panels, and a detailed blue pickup wireframe with restrained gold accents. The previous implementation reduced these to small uniform arcs, a flat grid, generic rounded rectangles, and a basic side-profile vehicle. It also drew a fixed road ribbon without receiving route geometry.

## Implemented mapping

| Reference element | Native implementation | Truth/safety boundary |
| --- | --- | --- |
| Blue/gold left speed cluster | Four concentric arcs, graduated ticks, measured-speed fill, large light-weight numerals, gold divider and RPM | Unknown speed/RPM stays a dash; fill depends on measured speed |
| Elevated turn card | Clipped glass card, explicit-direction glyph, distance, instruction and source | No inferred turn from a road name; no road-aligned ribbon |
| Open center roadway | Main design-plane rectangle x470–1208, y340–550 is free of normal panels | No synthetic roadway, traffic, weather, or lane confidence |
| Translucent lower instrumentation | Chamfered blue glass, layered borders, blue meters with quiet gold outlines | Fuel/coolant meters fill only for non-null real readings |
| Detailed pickup and OBD scan | High-resolution blue holographic pickup texture with visible internal/mechanical detail; native three-quarter wire mesh retained as decode fallback; coolant-linked accent drawn separately | Explicitly illustrative; available PID count is not a health/pass score |
| Warm metallic brand details | Light, spaced gold wordmark, fine dividers and bounded accents | No new semantic success colors |
| Optional optical/phone display | One aspect-preserving 1600×900 plane, transparent instrument layer over black optical host, transformed touches and minimum 48dp action targets | No new XREAL/Meta dependency or availability claim |

The static OBD ring reports the number of non-null RPM/coolant/load/voltage readings (0–4), not a percent-complete vehicle scan. There are no checkmarks or claims about engine, transmission, emissions, sensors, road safety, or weather. The illustrative pickup does not claim to identify the user's vehicle. Existing controller-provided road/status text remains authoritative.

## Preserved behavior

- Existing EXIT, VOICE, SHOW_ENGINE, TOGGLE_DIAGNOSTICS and TOGGLE_NAVIGATION actions remain connected to the same controller/activity boundaries.
- Phone detailed controls now use bounded speed freshness and a tap-time deadline. The former stale-GPS weakness has a source-level fix; native/device acceptance remains outstanding. XREAL remains read-only. Navigation-access permission routing and voice permission handling are unchanged.
- The native view is event-driven; no timer, shimmer, moving scan, or perpetual animation was added.
- Phone rendering is self-contained. The XREAL-first follow-through shares its graphics, preserves permissions, and adds separately documented telemetry freshness and HUD lifecycle fixes.
- Long source, route, alert and status text is ellipsized within its own area.

## Verification and release gates

`node --test tests/driving-hud-visual-contract.test.mjs tests/private-hud-contract.test.mjs` checks source/geometry contracts: normal panel bounds avoid the reserved center, aspect/coordinate round-trips, nullable measurements, no fabricated ribbon or pass states, bounded guidance, no directional glyph from a road name, existing actions and event-driven drawing. These are not native compile or pixel tests.

Android SDK/Gradle/Kotlin tooling was unavailable in this workspace. Android compilation, lint, native unit tests, GPU render checks and actual device/XREAL screenshots were **not run**. The source is not signed off for release or pixel parity. Companion SVGs are static design previews constructed from the native palette/geometry and embed the exact same unchanged pickup PNG; they are not app screenshots and their sample-data version is explicitly labeled.

Before acceptance on a phone and optional mirrored display:

1. Compile the canonical Android app, run unit tests and lint.
2. Capture 16:9 and ultrawide landscape frames for all-null telemetry, real GPS only, connected OBD, adapter disconnect, and active guidance with a long road/source name.
3. Check actual glyph/text metrics, optical brightness, chamfer strokes, pickup detail, color balance, center clearance, inset/cutout safety and absence of shimmer.
4. Verify EXIT/VOICE and transformed hit targets on both aspect ratios; test repeat taps, denied microphone/location/navigation access, stopped/moving/unknown-speed locks and activity recreation.
5. Confirm null and stale values stay unavailable, coolant highlighting requires a real reading, no unavailable sensor acquires a diagnostic checkmark, and dropping wearables leaves phone Driving Mode usable.

The October 13 road-visibility follow-through also changes native host composition, activity-specific black themes and the separate XREAL vehicle speed layout. Permissions and the phone action boundaries are unchanged; telemetry freshness and XREAL lifecycle handling now have focused follow-through fixes. See `HUD_ROAD_VISIBILITY.md` for display-mode limits and the stale-GPS release blocker.


## Detailed vehicle texture follow-through

The first native vector restoration improved hierarchy but was still too schematic to match the supplied cinematic pickup. A generated transparent, all-blue holographic pickup derived from the user's exact reference now supplies the rich vehicle surface. It is an illustrative asset, not camera input or a real scan. The native detailed mesh remains the fallback when texture decoding returns null or throws; that fallback does not block the HUD.

- Resource: `native/android/xreal/src/main/res/drawable-nodpi/icarus_hud_pickup.png`
- PNG: 1774 × 887 pixels, RGBA, 2,110,918 packaged bytes; copied unchanged from the reviewed generated image.
- SHA-256: `0f95b942440203d0b77ab0188d8ea60fe977d5e6ef250a1f25f8aef3364fcda9`
- Destination design-space bounds: x474–882, y565–769 (408 × 204), wholly inside the lower band. Decoded bitmap dimensions are aspect-fitted within those bounds; the model never stretches or covers the reserved center.
- One decode per view instance during initialization, with `inScaled = false`, `inSampleSize = 2`, `ARGB_8888`. Expected decoded dimensions are about 887 × 443/444 and memory about 1.50 MiB plus object/row/GPU overhead, versus about 6.00 MiB at full resolution. These are calculated estimates, not measured device allocations.
- No decode is performed in `onDraw`. The bitmap is cached for the view lifetime. Filtering is enabled for scaled rendering.
- The texture contains no gold/hot-engine state. A subtle gold/warm accent is drawn separately only when the current state has a coolant reading; it is not a diagnostic pass/health claim. The engine touch target follows the illustrated hood; the existing SHOW_ENGINE action remains unchanged.
- Both static SVG previews embed the exact same PNG and use the same 408 × 204 target rectangle. The no-data preview adds no engine accent. Captions continue to state that previews are not device screenshots.

An additional source/asset contract checks the exact PNG hash, RGBA format/dimensions, lower-band bounds, one-time bounded decode, aspect fitting and vector fallback. Device QA must additionally inspect decode-failure fallback, actual bitmap allocation, and optical texture brightness/performance.

## XREAL-first follow-through

The actual bundled XREAL VEHICLE route now selects the shared cinematic renderer; it no longer uses a different simplified layout. Phone actions are adapted to the same renderer, and the pickup resource is owned by `:xreal`. Typed live-state mapping preserves nulls rather than falling back to stale launch metadata. Logical-display diagnostics never claim physical glasses detection. See `HUD_ROAD_VISIBILITY.md` for lifecycle, Maps fallback, manufacturer restrictions and stationary-only acceptance.
