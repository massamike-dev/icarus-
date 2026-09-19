# Private ICARUS phone test

The `privateTest` Android variant installs as **ICARUS Test** (`com.icarusalmighty.app.test`) beside the regular app. It uses a separate web origin, app storage, and `icarus-test` links. The public updater is disabled. Do not distribute a normal debug APK as a substitute: it targets the public website.

## Before APK delivery

1. Provision the restricted test service using `render-private-test.yaml` from `work/icarus-private-test`, with its own data store and session secret. Configure the provisioned tester credentials and private account restrictions described in the test service configuration. Do not copy production user data. The free test service has automatic deploys disabled and uses ephemeral storage: test conversations and accounts may disappear after a restart or deploy.
2. Verify the test service and set `config/private-test.json` to `{"webUrl":"https://YOUR-ACTUAL-TEST-SERVICE.onrender.com"}`. This file contains only the public-facing origin, never credentials. The service must return `privateTest: true` and `commitSha` from `/api/health`. Redirects, production domains, localhost, and placeholder domains are rejected by delivery validation.
3. Push the completed configuration to `work/icarus-private-test`. Its workflow runs web tests/build plus Android private-variant unit tests and lint. Manually deploy this exact commit to the private Render service; automatic deploys stay disabled.
4. If the signing job ran before the matching service deployment became healthy, rerun that job. The workflow checks that the deployed commit exactly equals its build SHA both before signing and immediately before upload.

The signing job requests a short-lived GitHub Actions OpenID Connect token with audience `icarus-private-apk:massamike-dev/icarus-`. It streams the signed APK to `POST /api/private-apk` on the restricted service, including its SHA-256 checksum. The identity token stays in process memory and TLS headers; it is never printed, saved, or placed in a step output. Only this job has `id-token: write`; repository permissions remain read-only. [GitHub OIDC reference](https://docs.github.com/en/actions/reference/security/oidc)

Missing configuration on a branch push runs validation only, against a reserved `.invalid` origin; it produces no APK. A manual delivery request without configuration reports an actionable failure. A configured delivery fails before decoding signing secrets if the private health or exact-commit checks fail.

Health checks allow a free Render instance to wake using at most four 30-second requests with five seconds between transient failures. Redirects, unexpected service identity, and commit mismatches fail immediately. APK uploads are limited to 256 MiB on both client and server.

## What delivery verifies

- All web checks, private Android unit tests, and lint pass first.
- The existing upload signing secrets are used without being logged; signing files are removed even on failure.
- Android `apksigner` verifies the built APK. APK inspection checks the `.test` package, nondebuggable manifest, private runtime flag, exact test endpoint, test-only link scheme, and empty update feed.
- The service verifies GitHub's token signature, issuer, audience, expiry, repository/owner IDs, trusted branch/workflow, and exact deployed commit before accepting the upload. It verifies the uploaded bytes against the supplied checksum; CI checks the returned checksum, size, and commit.
- The APK is stored outside the web root and can be downloaded only through the provisioned tester's authenticated session. No APK is uploaded to Actions artifacts or GitHub releases, and no production manifest or branch is edited.

The normal Android workflow skips this branch so its public debug-artifact step cannot distribute an unintended build. The repository's source code and CI logs remain public. The free Render service's APK storage is ephemeral: a restart or deployment can remove the uploaded APK. After delivery, retrieve it through the tester session, verify the checksum from `GET /api/private-apk/metadata`, and save a durable private copy for Michael.

## Install on Michael's phone

1. The assistant retrieves `GET /api/private-apk` using the provisioned tester's authenticated session, checks the saved file against the recorded SHA-256, and saves `ICARUS-Test.apk` privately in Michael's ChatGPT Library.
2. Open the resulting file link on the phone and download the APK. No GitHub release or public download URL is needed. An unauthenticated request to the service's APK route is rejected.
3. Open the downloaded APK, allow Android's install permission for that browser when prompted, and install **ICARUS Test**. Keep the regular ICARUS installed.
4. Open **ICARUS Test** and sign in with the provisioned private tester credentials. Registration is disabled. Production accounts and conversations are not copied into the test environment.
5. Check native connection, voice permissions, Settings/Memory navigation, Chat spacing, temporary turns, and action confirmation/cancellation. Use harmless targets for initial phone-control tests. An Android intent being accepted does not prove a call or third-party task completed.

This setup does not establish that a test service has been deployed or an APK has been built. A verified workflow run and authenticated private download are still required. Always-on wake behavior, Bluetooth/glasses support, live AI-provider calls, and real-device behavior need separate physical testing before public release.
