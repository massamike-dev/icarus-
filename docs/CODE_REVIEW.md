# ICARUS code-review protocol

ICARUS reviews optimize for bugs prevented per comment, not comment volume.

## Review objective

Report only objective correctness issues introduced or exposed by the change: crashes, security or privacy weaknesses, data loss, incorrect behavior, broken fallbacks, missing required tests, and release regressions. Do not block on style, naming, speculative refactors, documentation polish, or optional optimization.

A valid finding must include:

1. severity: critical, high, medium, or low;
2. the changed file and narrow location;
3. the concrete runtime condition that triggers the problem;
4. the user or system impact;
5. the smallest safe correction or test that proves the fix.

If no blocking issue is found, say so plainly. Do not manufacture a comment.

## Context budget

Review in expanding rings:

1. Read the PR intent, generated review-context summary, and diff.
2. Read changed tests and the direct callers/callees of changed behavior.
3. Expand only when an invariant crosses a boundary: Android/WebView, native bridge, server/API, stored data, permissions, release/signing, or optional integrations.
4. Stop loading context once the behavior and its tests are understood.

Avoid rereading unchanged trees, generated outputs, binaries, lockfiles, or build artifacts unless the diff changes a dependency or generated contract. This is our practical equivalent of Macroscope's specialized-review approach: spend context on paths that can prove or disprove a bug.

## Risk routing

- **Low:** documentation, copy, tests, or static assets with no behavior or policy change. CI plus correctness review may be sufficient.
- **Medium:** localized runtime behavior with focused tests and no sensitive authority.
- **High:** authentication, privacy, stored data, permissions, billing, native commands, background/wake behavior, vehicle/wearable integrations, signing/releases, infrastructure, or cross-module refactors. Human review is mandatory.

The repository-specific approvability policy is in `.macroscope/approvability.md`.

## ICARUS invariants

Every applicable review verifies:

- Core assistant startup and Driving Mode still work when Meta, XREAL, or OBD-II is disabled or unavailable.
- No native phone/device action is reported as successful without native confirmation.
- Sensitive or irreversible actions preserve confirmation and permission boundaries.
- Continuous wake audio remains local; only the intended post-wake command may be transcribed or sent.
- Accounts, conversations, and memories remain isolated and deletable.
- Release signing material and API keys never enter source, logs, artifacts, or PR text.
- Offline/local-model behavior fails safely and does not masquerade as cloud success.
- Android permission denials, process recreation, force-stop, reboot, and disconnected accessories retain a usable fallback.
- Version codes never decrease and release artifacts correspond to the reviewed commit.

## Evidence standard

Prefer a focused regression test over a broad assertion that the build passed. CI is necessary but does not prove behavior. For high-risk changes, record the real-device or production-path check, expected fallback, and rollback route in the PR.

Track review quality by:

- confirmed defects caught before merge;
- false-positive findings;
- escaped defects;
- comments per confirmed defect;
- review duration and, when available, model token/step cost.

Do not claim a token reduction until measurements compare equivalent PRs and review quality.
