# Private ICARUS phone test

The `privateTest` Android variant installs as **ICARUS Test** (`com.icarusalmighty.app.test`) beside the regular app. It uses a separate web origin, app storage, and `icarus-test` links. The public updater is disabled. Do not distribute a normal debug APK as a substitute: it targets the public website.

## Before APK delivery

1. Provision the restricted test service using `render-private-test.yaml` from `work/icarus-private-test`, with its own data store and session secret. Configure the provisioned tester credentials and private account restrictions described in the test service configuration. Do not copy production user data. The free test service has automatic deploys disabled and uses ephemeral storage: test conversations and accounts may disappear after a restart or deploy.
2. Verify the test service and set `config/private-test.json` to `{"webUrl":"https://YOUR-ACTUAL-TEST-SERVICE.onrender.com"}`. This file contains only the public-facing origin, never credentials. The service must return `privateTest: true` from `/api/health`. Redirects, production domains, localhost, and placeholder domains are rejected by delivery validation.
3. Push the completed configuration to `work/icarus-private-test`. Its workflow runs web tests/build plus Android private-variant unit tests and lint.
4. While signed in as a repository owner or collaborator with release access, create a **draft** release named/tagged `private-test-<FULL_COMMIT_SHA>`, targeting that exact full SHA. Leave it unpublished. If delivery already failed because the draft did not exist, rerun the failed job after creating it.

The workflow intentionally does not create releases: GitHub's default Actions token cannot create a release for a commit containing unpublished workflow changes. It can upload assets to the already authorized draft. Do not publish the draft to work around access problems. [GitHub release API documentation](https://docs.github.com/en/rest/releases/releases#create-a-release)

Missing configuration on a branch push runs validation only, against a reserved `.invalid` origin; it produces no APK. A manual delivery request without configuration reports an actionable failure. A configured delivery fails before decoding signing secrets if health or draft checks fail.

## What delivery verifies

- All web checks, private Android unit tests, and lint pass first.
- The existing upload signing secrets are used without being logged; signing files are removed even on failure.
- Android `apksigner` verifies the built APK. APK inspection checks the `.test` package, nondebuggable manifest, private runtime flag, exact test endpoint, test-only link scheme, and empty update feed.
- The APK and SHA-256 checksum are attached only to the verified **draft** for this commit. No APK is uploaded to Actions artifacts, no release is published, and no production manifest or branch is edited.

The normal Android workflow skips this branch so its public debug-artifact step cannot distribute an unintended build. The repository's source code and CI logs remain public. A draft is private to repository users with sufficient permissions, **not exclusively one person**. GitHub documents that draft releases are visible to users with push access. [GitHub release visibility](https://docs.github.com/en/rest/releases/releases#list-releases)

## Install on Michael's phone

1. Sign in to GitHub in the phone's browser using the owner account for `massamike-dev/icarus-`.
2. Open the draft release from the repository's Releases page and download `ICARUS-Test.apk` from its assets. An unauthenticated download may return 404; that is expected for a private draft.
3. Open the downloaded APK, allow Android's install permission for that browser when prompted, and install **ICARUS Test**. Keep the regular ICARUS installed.
4. Open **ICARUS Test** and sign in with the provisioned private tester credentials. Registration is disabled. Production accounts and conversations are not copied into the test environment.
5. Check native connection, voice permissions, Settings/Memory navigation, Chat spacing, temporary turns, and action confirmation/cancellation. Use harmless targets for initial phone-control tests. An Android intent being accepted does not prove a call or third-party task completed.

This setup does not establish that a test service has been deployed or an APK has been built. A verified workflow run and authenticated draft download are still required. Always-on wake behavior, Bluetooth/glasses support, live AI-provider calls, and real-device behavior need separate physical testing before public release.
