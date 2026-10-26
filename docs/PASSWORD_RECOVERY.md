# ICARUS password recovery — local reconstruction, not deployed

Reconstructed October 21, 2026 from main `3402a416cd787f2e14f33efa587ec027bb6a99cf`.
The historical October 20 patch was not present in the current workspace or located in Library. This is a new implementation, not a claim that the original 210-test artifact was recovered.

## Two-word recovery update — October 26, 2026 (LOCAL ONLY)

This replaces the unpublished reset-link implementation. No production source,
configuration, API key, phone service, email delivery or deployment was changed.
The actual production schema and host must be inspected before activation.

## Behavior
- Public `/forgot-password` and `/reset-password` start an email → two-word code → new password flow. Opening the page sends nothing. Old token URLs are scrubbed and cannot reset a password.
- Codes contain two independent uniformly chosen Greek dictionary words written with Latin letters, separated by a hyphen. There are exactly 2,048 unique canonical words and 4,194,304 ordered pairs (22 bits). Words can repeat. Case and whitespace/hyphen separators normalize, but fuzzy spelling, Greek characters and reordered pairs do not. Dictionary provenance, license and a deterministic generator are included; known sensitive terms identified during independent review are excluded. Review is limited automated/AI linguistic review, not native-speaker certification; uncommon inflections remain. User typing preview and qualified Greek usability review are recommended before release, with activation approval retained by the owner.
- Five-minute code expiry. Server-keyed HMAC (domain-separated using the existing session secret) binds code to user ID, challenge ID, purpose and existing email. No plaintext code persists. A 32-hex challenge ID is returned for every request, including missing/ineligible/throttled accounts; code remains private to email delivery.
- Verification atomically consumes the code and issues a 256-bit, five-minute reset-only grant. Only its digest is stored. The grant is not an app session and cannot log in or access account APIs. Final password change consumes it atomically, increments session version, deletes recovery state, and preserves user ID/chats/memory/entitlements/purchases. Existing app and billing sessions are invalidated; no automatic login.
- Existing link tokens are invalid in this release. A new delivered challenge supersedes older challenges and reset grants; server-enforced 60-second resend cooldown and durable account/destination quotas apply. Five failed code checks in 15 minutes block recovery checks across resends, channels and handler restarts, without disabling ordinary login. Full counter storage fails closed.
- Generic request replies and asynchronous provider dispatch avoid account-existence and provider-latency disclosure. No email addresses, words or grants are logged. Code/credential/grant values remain component memory, never URL/local/session storage. Repeated clicks, cancellation, navigation and stale responses are guarded.
- Settings has a current-password-gated enrollment flow to prove control of the account's existing email. It cannot add/change an address. `recoveryEmailVerifiedAt` is a valid timestamp and `recoveryEmailVerifiedFor` must match the current stored email. No historical address is silently labeled verified.
- Legacy accounts without this metadata are ineligible by default. A separately approved `ICARUS_RECOVERY_ALLOW_LEGACY_EMAIL=true` policy permits proof of possession of the immutable existing account email for recovery. Only after successful password replacement is that address marked verified now. This can serve an already-locked-out legacy user but entails a reviewed pre-registration/typo-email ownership risk; do not enable silently. No claimed identity-proofing assurance or NIST compliance certification.
- Phone signup, SMS, linked identity recovery and MFA are not implemented or advertised as available. No new contact can be added from a recovery request. These require separate verified-contact enrollment, provider configuration and security review, and must not introduce weaker MFA bypasses.
- Successful reset queues a generic security notification if the shared mail budget has capacity. This is best effort; live provider delivery and failures must be checked at staging. No secret/password is included.
- Private Test disables all recovery/enrollment/password mutations. Do not modify private tester credentials to test regular recovery.

## Configuration — no values have been changed by this work
- `ICARUS_RECOVERY_ORIGIN`: exact actual HTTPS ICARUS host, no path or trailing slash. Verify the live production host first; purchasing `icarusassistant.com` or verifying its mail subdomain does NOT establish that its root serves this app. Do not blindly set the purchased-domain root.
- `ICARUS_RECOVERY_FROM`: plain sender email. Intended: `recovery@accounts.icarusassistant.com`.
- `RESEND_API_KEY`: securely provision Sending access restricted to `accounts.icarusassistant.com` in the verified wennigworks Resend workspace. Never put its value in chat, notes, git or artifacts.
- Retain existing `ICARUS_SESSION_SECRET` (at least 32 characters), data disk, billing configuration and deployment resources. The existing secret derives the code HMAC; no additional persistent credential is needed. Missing session key disables emailed recovery.
- `ICARUS_RECOVERY_ALLOW_LEGACY_EMAIL`: default absent/false. Only set exactly `true` after explicit policy approval and actual production account-schema inspection; it is not an email-verification migration script.
- Missing/invalid origin disables both changes and recovery. Missing sender/key disables emailed recovery. Browser Origin, when present, must equal the exact configured origin.

## Rate limiting / hosting gate
Current conservative request limit is 40 requests per socket address / 15 minutes in a bounded process-local map. Behind Render this can aggregate users under an edge socket IP; it is intentionally fail-closed and can temporarily throttle legitimate recovery requests together. No forwarded header is trusted and there is no proxy-trust toggle to approve for initial low-volume use.

Email/account counters now persist in the existing single-writer JsonStore: 3 issue requests per normalized email / 15 minutes, 5 failed code checks per destination / 15 minutes, 5 password-change/enrollment password checks per account / 15 minutes, plus a persistent 60-second resend cooldown. Failed password checks commit their counter. Hashes identify emails; no raw IPs are persisted. Counters expire and are bounded to 10,000 entries. An independent durable rolling budget caps actual mail attempts at 80 per 24 hours for this application, regardless of socket/header values. Provider failures count toward that safety budget; suppressed requests retain the generic forgot response. This does not reserve the provider account's allowance against other services using that account.

Read-only review on October 21: Render production uses one `node server/index.js` process, one instance and an existing persistent `/var/data` disk. Render says that disk-backed services cannot scale to multiple instances, and stops the old instance before starting the new one. Thus this deployment matches JsonStore's single-writer assumption; no new datastore or billing resource is needed. Never run multiple writers against this JSON file. Future scaling requires a shared transactional datastore and shared limit counters; that is outside this release.

Render documents that sockets see proxy IPs and recommends X-Forwarded-For, but the reviewed article does not define a complete sanitization/trusted-hop contract suitable for blindly choosing the first or last entry. Cloudflare documents forwarded-chain/Worker exceptions. Therefore do not add `trust proxy=true` or arbitrary XFF-first parsing. A future per-client optimization requires verified edge-only ingress and forged-header tests on both custom and onrender domains; it is not a security prerequisite for the current conservative durable budget.

Sources: [Render edge and client-IP guidance](https://render.com/articles/how-render-handles-ddos-attacks), [Render persistent-disk constraints](https://render.com/docs/disks), [Cloudflare request-header semantics](https://developers.cloudflare.com/fundamentals/reference/http-headers/).

## Activation checklist — ALL pending unless independently verified
1. DNS completed October 26: public Google/Cloudflare CNAME `rsend.accounts` → `rsend.forge.rmta.net` and live Resend accounts.icarusassistant.com Verified, as independently reported by the DNS verification worker. Recheck before later release if status changes.
2. Verify correct business Resend workspace, scoped sending key and secure Render storage; do not assume previous user instructions prove completion.
3. Inspect actual production schema and host; approve legacy-email eligibility policy if needed for the already-locked-out tester. Review dictionary license and native-Greek word-list usability/content. Approve exact origin/sender for activation. Initial low-volume operation uses conservative socket throttling plus durable email/account/global budgets; verify expected usability during staging. Retain billing and all existing secrets.
4. Test in isolated regular-mode staging with an approved recipient and no production data. Existing private Test mode deliberately rejects public recovery; do not loosen it.
5. Verify real email arrival/spam, exact word readability, expiry, reuse, resend/attempt lockouts and newest-code behavior, actual mobile browser UI, Back/refresh/repeated submit, code-to-grant binding, old-session revocation in app and billing, new login and preserved data. Confirm ineligible legacy accounts behave generically and approved policy works only at the existing email. Current automated tests mock email and use jsdom, not live inbox/mobile browser.
6. Review patch and backup/restore plan. Snapshot existing protected data via approved operator process before deploying; never publish the snapshot.
7. Obtain/verify targeted production security/deployment authorization. Render main is `autoDeploy=yes` with `checksPass`; pushing main can deploy automatically. Do not push main as a supposedly harmless source backup.
8. Publish via reviewed release process, check exact remote commit, CI and live deploy SHA, then verify public page, configuration and an approved end-to-end test.
9. Only after success give `https://icarusassistant.com/forgot-password` as a working shareable page. Do not share an individual's secret token URL; each account holder requests their own email.

## Validation
Run from repository root:
```
npm ci --prefix web --ignore-scripts --cache /tmp/icarus-npm-cache
npm test --prefix web
npm run build --prefix web
git diff --check
```
Final October 26 validation: 205/205 tests passed (including 19 UI tests and 13 independent security-review tests), production build passed, and git diff --check passed. Existing bundle-size warning remains. Test/build logs are included with the artifact. Earlier 179-test result applied to the superseded link-based implementation and must not be used as evidence for this code-based revision. No live mail, hosted staging or production browser QA has run.

## Recovery of this source artifact
The accompanying `icarus-recovery-two-word.patch` includes new and modified source/tests plus this checklist; apply only to the exact base above (use `git apply --check` before applying). Preserve this artifact in Library, not solely an ephemeral workspace. No production credentials or user data are included. Re-run tests after any subsequent edit and persist an updated artifact version.
