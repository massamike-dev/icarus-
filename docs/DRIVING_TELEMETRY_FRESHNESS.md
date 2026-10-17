# Stationary HUD prototype: speed evidence and control lockout

## Scope and acceptance status

This is a focused source-level repair of the stale-zero control gate. It is a stationary software prototype, not approval to operate a vehicle while wearing XREAL glasses and not an automotive safety system. Hardware warnings and operating restrictions still apply. A telemetry deadline does not prove that a vehicle is parked, in park gear, or safe to interact with.

The previous implementation retained cached GPS zero indefinitely, reused a prior speed when a new fix lacked speed, ignored provider disable, and allowed a blocked OBD worker to leave its last speed marked live. The activity's stopped-speed check therefore could unlock detailed controls using old evidence.

## Implemented policy

- `DrivingSpeedFreshness` is a pure Kotlin policy with an explicit monotonic clock input. Values are fresh for strictly less than 3,000 ms from measurement time. At the exact deadline they are unavailable. Missing, negative, NaN and infinite speeds do not become zero. Missing/zero/future timestamps and overflow-prone deadlines are rejected.
- GPS uses `Location.elapsedRealtimeNanos`, converted to elapsed milliseconds. `getLastKnownLocation` is subjected to that same age check; fetching or redelivering a cached fix never gives it a new timestamp. Fresh incoming fixes without speed discard that provider's speed rather than retaining its old zero.
- GPS and network providers have independent evidence. A network zero or missing network speed cannot replace a GPS provider's fresh moving evidence. The display prefers a fresh positive measurement when sources disagree, otherwise OBD before available GPS-provider measurements.
- Only an actual finite raw zero can create stopped evidence. Positive speed locks controls, including 0.01 MPH that rounds to a displayed zero. This intentionally replaces the old rounded `>= 2 MPH` moving threshold. GPS noise and slow movement may keep details locked; voice and exit behavior are unchanged.
- Fresh positive movement is also retained as a conservative veto, with its original deadline, across missing/invalid samples, conflicting same-time packets, provider/permission loss or disconnect. It cannot become stopped evidence, cannot refresh its age, and prevents another source's zero from unlocking controls. A valid newer zero from the same input may clear its veto; a subsequently delivered still-fresh contradictory positive may conservatively relock until its original expiry or a newer valid zero. Out-of-order zero cannot overwrite newer motion or missing data.
- Explicit invalidation records a monotonic watermark. A queued fix or OBD batch measured before permission/provider loss cannot restore a previous zero after recovery. This includes the first callback from a provider not previously present in the policy.

## Controller integration

- A main-thread watchdog checks providers, permission state, and measurement expiry every 500 ms while the controller runs, independently of GPS callbacks and the OBD executor. No new permissions or permission prompts are introduced. Revoked location permission, provider disable/unavailable status, and revoked Bluetooth permission clear the affected usable evidence. Valid data from a separate source may remain available, subject to the movement veto.
- Foreground location requests retain the existing 750 ms minimum interval and change the distance filter from 1 m to 0 m so stationary speed fixes can refresh. This is a requested cadence, not a guarantee Android will supply speed-bearing fixes. Coarse-only or unreliable sources can leave speed unknown and controls locked.
- OBD uses the elapsed time immediately before `ObdManager.snapshot()`. Inspection confirmed that the current manager reads six PIDs plus adapter voltage sequentially, with speed second; its returned wall-clock timestamp is generated after those reads. The batch-start timestamp deliberately overestimates measurement age. A batch taking 3 seconds or more cannot restore speed merely by returning later. Fresh GPS can supply fallback; otherwise speed and movement become unknown.
- OBD RPM, coolant, fuel, load and voltage also clear when the batch expires. `obdConnected` now means a transport with a fresh snapshot, rather than merely a successful historical connect call. Source labels distinguish stale OBD and unknown speed. Existing alert text can remain as historical context; it is not a freshness guarantee.
- All policy input updates and state delivery are serialized on the main thread. Queued worker results are evaluated against current time/permissions when delivered, not against the time they were queued. Stopped controllers drop queued results and remove their watchdog callbacks. This does not redesign the adapter's blocking I/O or establish a hard transport timeout.
- `DrivingHudState` carries `speedValidUntilElapsedRealtimeMs`. `parkedControlsAllowed` checks the elapsed clock at the moment of access as well as confirmed zero/stopped fields. Thus an old UI snapshot cannot grant parked permission after its deadline even if the watchdog has been delayed. Visual expiry can lag by up to one watchdog interval on a responsive main thread; a stalled UI has no hard rendering-latency guarantee.
- Rendering stays event-driven: the watchdog publishes only changed states. An explicit `refreshSnapshot()` rechecks source registration, permission and measurement freshness before republishing even an unchanged state. The active XREAL lease uses this API for a bounded 1-second transport heartbeat so valid route-only guidance is not mistaken for a dead sender. A heartbeat never renews speed/diagnostic observation timestamps or their 3-second deadlines; receiver transport liveness has its separate 5-second bound. The XREAL host owns cancelling its heartbeat on close, mode switch, failure or lease release. No renderer, activity action, camera, background-location, ECU-write or voice capability is added by this patch.

## Verification actually performed

- `node --test tests/driving-speed-freshness-contract.test.mjs`: 10 source-contract checks passed. They check the real Kotlin wiring for timestamps, invalidation, watchdog/lifecycle handling, independent providers, movement vetoes and action-time deadline; they do not execute the policy or Android callbacks.
- Focused `git diff --check`: passed.
- `DrivingSpeedFreshnessTest.kt` adds deterministic JUnit regression source covering exact boundaries, old cached zero, no callbacks, raw creeping motion, invalid/future times and numbers, GPS/OBD disagreement, provider separation, invalidation and queued samples, slow/stuck OBD batches, both same-timestamp conflict orders, delayed positive packets, fallback expiry, and old UI snapshots. It calls `parkedControlsAllowedAt(now)` so tests do not invoke Android's unmocked `SystemClock`.
- Existing native tests were searched for calls to the production getter; none were found. Existing telemetry conversion/heading tests remain unchanged.
- Independent code review identified and prompted fixes for movement-evidence erasure and arrival-order gaps. Code inspection is not execution evidence.

Android SDK, Gradle and Kotlin compiler are absent from this workspace. **Android compilation, JUnit execution, lint and device tests have not run. No release or hardware-readiness claim is made.**

## Required validation before accepting the prototype

1. In the canonical `native/android` build, run `:app:testDebugUnitTest`, `:app:lintDebug` and `:app:assembleDebug` with the repository's documented prerequisites. Run all applicable aggregate/source checks too.
2. On a stationary test setup, inject a fresh zero, stop all fixes, and verify unknown speed and locked detail controls at expiry. Delay the main loop past expiry, then attempt a detail action before visual refresh; it must remain locked.
3. Exercise GPS and network providers independently and together: stale cached zero, missing-speed fix, malformed/nonfinite input through test seams, approximate-only permission, provider off/on, temporary unavailable status, fine-to-coarse downgrade, denial and revocation. Verify restored access requires newly valid evidence and no new permission prompt appears unexpectedly.
4. With an adapter simulator, test fresh zero, positive sub-1 MPH, a moving/zero disagreement, missing speed PID, disconnect, Bluetooth revocation, indefinitely blocked snapshot and a batch finishing after 3 seconds. Speed and all OBD instruments must expire; only independently fresh GPS may provide fallback.
5. Test out-of-order and identical-time packets, including zero then positive, positive then zero, missing then delayed positive, provider recovery and queued callbacks after controller stop. Positive evidence must never create parked permission.
6. Verify phone and shared XREAL scene receive the cleared fields without resurrecting launch-time/cache values. Check voice/exit, navigation-access flow, recreation, close/reopen and two session leases. These tests must respect device safety restrictions and remain stationary.

This source fix supersedes the description of *unchanged telemetry behavior* in the earlier graphics-only review; its native/device verification gate remains open.
