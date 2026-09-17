# Settings repair

The Settings tab previously rendered an overview placeholder. Leaving Memory could also throw during unmount because its effect returned the data-loading Promise instead of a cleanup function. No screen boundary contained the failure.

Changes:
- Give Settings a dedicated, mobile-friendly page with voice controls, account information, sign out, and existing privacy/support links.
- Correct the Memory effect lifecycle and keep navigation available through a per-screen error boundary.
- Render voice controls through React instead of mutating React-owned DOM with a MutationObserver.
- Require explicit listening consent through an accessible dialog. Disable Android controls in browser mode and distinguish request dispatch, acknowledgment, timeout, and reported listener state.
- Run the web workflow on main as well as web-migration so checks-based deployment can observe web checks after merge.

Verification: 13 tests pass, including bundled React UI regression tests for Memory → Settings, repeated switching through every tab, web-only controls, and native stop-action dispatch. Production build passes. These are DOM simulation tests, not physical Android tests.

The live app requires sign-in for browser verification. Render's connector requires explicit workspace selection before service inspection/deployment; the available workspace is Mike's workspace (itsmike4you@gmail.com). Deployment and physical-device verification remain pending until confirmed.

Design: reuse web/DESIGN.md and its canonical styles.css tokens; no rebrand. Settings and Overview share VoiceControls. New settings controls use semantic buttons, inline status, and the platform dialog focus model. The static premium audit also found existing form-validation/textarea declarations in main.jsx outside the Settings repair; full-app design compliance is not claimed.
# Production build follow-up

The production install was reproduced in a clean checkout: with
`NODE_ENV=production`, `npm ci` omitted `jsdom`, and the navigation test failed
with `ERR_MODULE_NOT_FOUND`. Added `web/.npmrc` with `include=dev` because the
existing Render build runs tests before bundling. This works with the existing
build command and does not require a Blueprint configuration sync. GitHub web
checks now also run with `NODE_ENV=production`.

Validation: clean production install, all 13 tests, and production bundle passed.
Render's actual build logs were unavailable; this reproduces a concrete build
blocker but does not yet establish successful deployment or device verification.
