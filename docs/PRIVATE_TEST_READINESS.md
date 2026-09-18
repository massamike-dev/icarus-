# ICARUS private test readiness

Status recorded September 18, 2026. This document describes a private phone test, not a public release approval.

## Prepared

- The held ICARUS fixes are on `work/icarus-private-test`, separate from `main` and the public release.
- Android's `privateTest` variant installs as **ICARUS Test** with package `com.icarusalmighty.app.test`, separate storage, a separate HTTPS endpoint, and `icarus-test` links. Public updates and Play billing are disabled for this variant.
- The server provisions one private tester, disables registration, separates sessions and storage from production, and rejects inconsistent private-mode configuration.
- The private delivery workflow validates the web application and Android variant before signing. An unset test URL runs validation only and cannot produce a deliverable APK.
- The Render integration can now read the existing `icarus-assistant` service in its actual workspace. Browser password retries are not needed for Render MCP operations.

## Required before an install link exists

1. Approval to create the separate **icarus-private-test** Render web service on the **Free** plan. Automatic approval review rejected the initial creation request because the service has an internet-accessible URL and the public release is on hold. No test service was created by that rejected request.
2. Deploy the private branch with a new session secret, isolated data path, and private tester credentials. The web URL is reachable on the internet; account data, actions, and APK downloads require authentication.
3. Record the real service URL in `config/private-test.json`, deploy the same commit used for the APK, and pass the deployment and signed-delivery checks.
4. Verify the APK signature, test package, test endpoint, disabled updater, and checksum. Retrieve the APK through the private tester account and deliver the file privately.

## Validation evidence

Commit `65a92560a862dcb671918f5fd47772b0b7d0fa60` passed web validation and Android private-variant unit tests and lint in [workflow run 35291855563](https://github.com/massamike-dev/icarus-/actions/runs/35291855563). Its signed delivery job correctly skipped because no test server URL was configured. This evidence does not establish that a signed APK exists or that later delivery changes have passed their checks.

The subsequent private APK delivery implementation passed all **65 web tests**, the **two Android/web source-contract tests**, and a production web build locally. These checks include signed identity validation, unauthorized-download rejection, atomic uploads, checksum/size failures, and disabled delivery routes in public mode. Live GitHub OIDC delivery and the signed APK still require the approved test service.

## Checks requiring a configured service or physical phone

- A separate AI provider key must be configured to test live AI responses and web search. The current service template does not supply or copy a production key.
- Microphone permissions, hands-free listening, wake words, Bluetooth/glasses behavior, and actual Android app actions require testing on Michael's phone.
- Settings and Memory navigation, Chat spacing, shared voice/Chat context, temporary conversations, and action approval/cancellation need a phone smoke test of the signed build.
- The Free service can sleep and its filesystem is ephemeral. Test conversations and temporary APK storage can disappear after a restart or deploy; keep the delivered APK file. Stop the regular app's hands-free listener while testing the second app.

## Public release remains held

No public merge, public Render deployment, public GitHub release, or production updater change is authorized by this private test setup. Publish the accumulated fixes and in-app patch notes only after the remaining checks are complete and the release hold is lifted.
