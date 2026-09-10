# ICARUS Public Release Readiness

ICARUS is a public, multi-user assistant. Personal developer accounts may be used for testing, but production features must be isolated per signed-in user unless they are explicitly administrative.

## Product priority

1. Core assistant: hands-free wake, voice, conversations, reminders, scheduling, memory, phone actions.
2. Driving Mode: map/GPS/navigation/voice with optional OBD-II.
3. Optional integrations: XREAL, Meta, calendar, email, files, smart-home and other connected services.

## Release invariants

- Every personal-data integration is per-user by default.
- A developer/admin OAuth credential must never be used as the identity for ordinary users.
- Conversations, messages, memory, usage, preferences and confirmations remain user-isolated.
- Admin routes/actions require server-side authorization, not only hidden UI.
- Meta, XREAL and OBD-II are optional and cannot block core assistant startup.
- Core assistant works without ARCore.
- Signed releases are published only from `main`.
- `native/android` is the canonical Android app; `/app` is legacy only.
- No secrets, API keys, OAuth refresh tokens or signing credentials are committed to the repo.

## Front-to-back release gates

### P0 Identity and data isolation

- [x] Protected authenticated application routes.
- [x] Admin-only UI routes guarded by role.
- [x] Conversation, Message, Memory, UsageRecord and ConfirmationRequest RLS scoped to creator.
- [x] UserPreference scoped to owner with admin support access.
- [ ] Verify public registration/login policy in deployed Base44 auth config.
- [ ] Add automated negative RLS tests for cross-user reads/writes.
- [ ] Audit every backend function for `auth.me()` and server-side role/ownership checks.

### P0 Per-user integrations

- [ ] Replace shared Google Calendar test connection with an app-user Google Calendar connector.
- [ ] Add user-facing Connect/Disconnect Calendar flow.
- [ ] Resolve the current user's connector token only inside authenticated backend functions.
- [ ] Add Calendar create/read tests for two separate users and prove no cross-account access.
- [ ] Apply the same pattern to future Gmail/Drive/Tasks integrations.

Base44 requires a workspace-registered OAuth connector ID for app-user connectors. That one-time workspace configuration is an admin setup dependency; it is not a reason to use the developer's shared connector in production.

### P0 Assistant reliability

- [x] Wake routes to Core Assistant by default.
- [x] Native speech transcripts reach the assistant UI.
- [x] Protected actions support spoken confirm/cancel.
- [x] Barge-in stops TTS before new native capture.
- [x] 100 deterministic wake/command control-path simulations pass.
- [ ] Real-device wake-word calibration: quiet room, TV/music, outdoors, vehicle idle, road noise, windows down, near/far microphone positions.
- [ ] Record false-accept and false-reject rates before public release.

### P0 Scheduling and reminders

- [ ] Per-user Calendar event creation end-to-end.
- [ ] Per-user upcoming-calendar query end-to-end.
- [ ] Reminder phrasing and timezone tests.
- [ ] Clarification flow for missing date/time.
- [ ] Confirm destructive/high-impact scheduling changes before execution.

### P0 Android stability and security

- [x] `native/android` canonicalized.
- [x] Meta/XREAL optional integration gates.
- [x] Phone GPS fallback.
- [x] Signed Android CI runs unit tests/lint/build.
- [x] Release publication restricted to `main`.
- [ ] Startup/crash test on representative supported Android versions.
- [ ] Permission-denied tests for microphone, contacts, camera, location, Bluetooth and notifications.
- [ ] Verify update/signing lineage on Play-installed and sideloaded builds.
- [ ] Add persistent diagnostic/crash breadcrumbs that contain no sensitive user content.

### P1 Billing and entitlement

- [ ] Verify Play Billing purchase, restore, cancel, grace/hold and expiry flows.
- [ ] Server-side purchase verification and replay protection.
- [ ] Ensure premium/admin grant state cannot be self-edited by a normal user.
- [ ] Remove test-only grants/data before production launch.

### P1 Privacy, policy and store readiness

- [x] Privacy policy route exists.
- [x] Account deletion route exists.
- [x] Android permission rationale documented.
- [ ] Audit policy text against actual data collection and retention.
- [ ] Complete Google Play Data Safety answers from implementation, not guesses.
- [ ] Confirm microphone/background-service disclosure and foreground-service declarations.
- [ ] Prepare store screenshots, description, support contact and tester instructions.

### P1 Product cleanup

- [x] Remove stale v1.2.x label from dashboard.
- [ ] Remove or archive legacy `/app` after final dependency check.
- [ ] Replace hardcoded version download URLs in web UI with release-manifest-driven links.
- [ ] Remove or gate every unfinished control advertised as working.
- [ ] Resolve current lint/typecheck debt in active production surfaces.

## Definition of release-ready

A candidate is release-ready only when P0 items are complete, CI is green, a signed internal-test build installs and updates correctly, two-user isolation tests pass, hands-free assistant functionality survives real-device testing, and privacy/store declarations match the shipped behavior.
