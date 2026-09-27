# ICARUS billing handoff

Prepared September 27, 2026. Candidate: **1.6.14 / version code 45**. Changes are in PR #42. This guide distinguishes implemented app behavior from Google account setup that still requires the owner. Compilation and simulated automated tests do not establish that real Google payments work.

## Start here

1. Confirm the three Google subscriptions and their base plans below.
2. Give the ICARUS backend a Google Play service account, securely in Render.
3. Upload the validated build-45 AAB to Play Internal testing, install it through Play, and use a Google license-test payment method.
4. Configure subscription-change notifications and approve the actual paid benefits before enabling real checkout.

**Do not turn on real-money checkout just because the app builds.** Default billing mode is `disabled`. Existing free assistant features remain free until the owner explicitly chooses a paywall.

## 1. Play subscription catalog

Open the existing ICARUS app in Play Console. Go to **Monetize with Play → Products → Subscriptions**. Reuse any products already created under these IDs; do not create duplicates or rename the IDs.

| Name | Product ID | Suggested base-plan ID | Auto-renewing period | U.S. base price |
| --- | --- | --- | --- | --- |
| ICARUS Ascend | `icarus_pro_monthly` | `monthly` | 1 month | $10.00 |
| ICARUS Zenith | `icarus_pro_quarterly` | `quarterly` | 3 months | $17.42 |
| ICARUS Crest | `icarus_pro_annual` | `annual` | 1 year | $69.69 |

Each product needs **one Active auto-renewing base plan at the matching period**, with new subscriptions available in the United States. The app discovers the actual base-plan ID from Google, so an existing valid ID does not need to be replaced by the suggestions above. This implementation sells the standard base plan, not a free-trial or introductory offer. All three names describe the same membership with different billing periods.

The backend validates the active status, U.S. price and billing period for all three products before enabling the purchase buttons. A missing product or incorrect price keeps checkout disabled. Country availability and currency conversions must be reviewed separately before expanding outside the U.S.

These are the requested base prices, not guaranteed tax-inclusive checkout totals everywhere. Google controls local tax treatment and the final payment screen. The app adds no separate ICARUS processing surcharge. Do not advertise “all taxes included” globally. Google fees reduce developer proceeds; the final tax-inclusive total can vary with the buyer's jurisdiction. Four quarterly payments total $69.68, one cent less than the selected annual price; the requested figures are preserved.

Check that the Google payments profile is complete and linked to the developer account. Creating a subscription draft alone does not make it purchasable. [1, 2, 6]

## 2. Google Play API access for the server

Use an existing Google Cloud project or create one. **The Cloud Project ID is not the Play app ID or Developer ID.** Its exact value is not known from the supplied Play Console URL.

In Google Cloud, enable **Google Play Android Developer API**. Under **IAM & Admin → Service Accounts**, create an account such as `icarus-play-billing`. Copy its service-account email.

In **Play Console → Users and permissions → Invite new users**, enter that service-account email. Limit app access to ICARUS. Allow viewing the app and the billing permissions **View financial data, orders, and cancellation survey responses** and **Manage orders and subscriptions**. Do not grant account-wide administrator access merely to make a permission error disappear. [1]

For this Render implementation, create a JSON key for that service account and put its complete JSON into Render's private environment-variable editor as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. **Never paste the key into chat, GitHub, a screenshot, the AAB, or the APK.** Keep a secured backup. This release uses service-account JSON credentials, not a browser session or consumer Google sign-in.

## 3. Render configuration

Service: **icarus-assistant** (`srv-dakkmjfqj5pc73bhe280`). Open its **Environment** settings. Merge these entries into the current environment; do not replace unrelated settings.

| Variable | Initial setting |
| --- | --- |
| `ICARUS_BILLING_MODE` | `disabled` |
| `ICARUS_BILLING_LIVE_APPROVED` | `false` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Full private JSON key from step 2 |
| `ICARUS_BILLING_SECRET` | Stable, newly generated random 64-character hex secret |
| `ICARUS_BILLING_TEST_USER_IDS` | Comma-separated ICARUS account IDs allowed to test checkout |
| `ICARUS_PREMIUM_GRANT_USER_IDS` | Optional comma-separated account IDs receiving complimentary Premium |
| `ICARUS_PREMIUM_FEATURES` | Leave empty until the owner chooses the paid benefits |

Generate the billing secret in a trusted terminal with `openssl rand -hex 32`, then save it directly in Render. Do not replace an already configured billing secret: it binds purchases to accounts and encrypts stored purchase tokens. Rotation requires a migration, not simply entering a new value.

Keep the existing `ICARUS_DATA_FILE` value and verify its location is on the persistent `/var/data` disk. Do not switch to a new empty filename without migrating existing account data. Keep one service instance with this JSON-store implementation; multiple independent instances require a shared transactional database first.

Once the updated web service is deployed, open **ICARUS → Settings → ICARUS Membership**. Each signed-in user can see their permanent **Account ID for developer grants**. Use that ID in Render, not a self-entered email address. The old email-only grant variable is no longer honored because registration does not prove email ownership. Adding an ID grants Premium; removing it revokes that complimentary grant after the updated server configuration takes effect. A separate valid paid subscription can still confer access.

**For a clean purchase test, do not give that test account a complimentary grant at the same time.** A developer-granted account already has Premium and the purchase buttons intentionally stay disabled.

## 4. Install and test safely

In Play Console, put the validated **1.6.14 / 45 AAB** in Internal testing. Use **Add from library** if code 45 is already uploaded. Do not include obsolete bundles in the same release. The `.aab` is for uploading, not opening on a phone.

Add the testing Google account both to the **Internal testing tester list** and to the developer account's **License testing** list. Opt in and install ICARUS through Google Play. Internal-track membership by itself does not make purchases free. Google must show a test payment method. Cancel immediately if a real payment method or real charge appears. [4]

Use the regular package `com.icarusalmighty.app`, not the isolated `com.icarusalmighty.app.test` app. Build 44 does not implement the new billing-screen protocol; build 45 is required.

After credentials and all three base plans are ready, set `ICARUS_BILLING_MODE` to `test`, keep live approval `false`, and list the testing ICARUS account ID in `ICARUS_BILLING_TEST_USER_IDS`. Reload Settings and tap **Refresh prices**. The prices shown for enabled offers must be Google's actual returned prices, not the disabled “Planned U.S. price” placeholders.

Run and record these checks before authorizing paid use:

- Cancel Google's payment sheet: no new Premium grant.
- Complete a license-test purchase: backend verification succeeds and Premium becomes active only after server acknowledgement.
- Pending payment: no Premium until payment completes and Google confirms it.
- Close and reopen the app, then restore: same account retains its verified purchase without duplicate billing.
- Sign in with another ICARUS account: the original purchase must not transfer.
- Test renewal, cancellation at period end, expiry, grace period, account hold, and revocation/refund using Google's testing tools.
- Grant and revoke complimentary Premium for a separate account ID.

The UI intentionally prevents overlapping subscriptions. This release does not offer in-app prorated switches between the three separate products. Manage existing billing in Google Play; do not buy another membership to resolve a restore failure.

## 5. Subscription-change notifications

Enable the **Cloud Pub/Sub API** in the chosen Cloud project. Create a topic such as `icarus-play-billing`. Give `google-play-developer-notifications@system.gserviceaccount.com` **Pub/Sub Publisher** access on that topic. [2]

Create an **authenticated push subscription** to that topic:

- Endpoint: `https://icarusassistant.com/api/billing/rtdn`
- Enable authentication and choose a push-identity service account, for example `icarus-play-push@YOUR_ACTUAL_PROJECT_ID.iam.gserviceaccount.com`.
- Set the JWT audience to exactly `https://icarusassistant.com/api/billing/rtdn`.
- Grant the Pub/Sub service agent the required token-creation permission for that selected push identity, following Google's authenticated-push instructions. Do not use an unauthenticated public webhook. [3]

Set these additional Render variables to match the real configuration:

```
ICARUS_RTDN_AUDIENCE=https://icarusassistant.com/api/billing/rtdn
ICARUS_RTDN_SERVICE_ACCOUNT=the-actual-push-service-account-email
```

Verify the custom domain reaches the updated service before using it as the endpoint. Then, under **Play Console → Monetization setup → Real-time developer notifications**, enter the full topic name `projects/YOUR_ACTUAL_PROJECT_ID/topics/icarus-play-billing`. Choose notifications for subscriptions and voided purchases, save, and send the test message. Confirm that Pub/Sub actually delivers it and the endpoint returns HTTP 204. Google's “test publish succeeded” alone does not prove backend delivery. [2, 3]

The receiver verifies Google's signature and identity, then looks up the purchase's current status from Google. It never grants Premium just because a notification says “purchased.”

## 6. Owner decision before real-money launch

Decide which existing capabilities Premium purchases will unlock. No new feature promises have been invented and no previously-free feature has been automatically paywalled. Supported server-side gates in this release are:

| Setting token | Behavior when selected |
| --- | --- |
| `cloud-chat` | Requires Premium for cloud-assisted Chat requests |
| `cloud-voice` | Requires Premium for cloud voice command/assistant requests |
| `saved-memory` | Requires Premium to read/add saved memories |

Set `ICARUS_PREMIUM_FEATURES` to the comma-separated approved tokens only after reviewing the customer impact and updating the Play benefit text. The membership UI displays the configured benefits. Account deletion and deleting stored memories remain available. These gates do not monetize every native offline/phone function.

After real-device tests and notification delivery pass, set `ICARUS_BILLING_MODE=live` and `ICARUS_BILLING_LIVE_APPROVED=true`. Live checkout also requires configured benefits and matching active Google plans. These configuration checks do not themselves prove a successful real-world checkout or Google policy approval.

Review the privacy policy and Google Data safety answers for the purchase history, encrypted purchase tokens, account associations, third-party processing and minimal anti-replay token-hash retention introduced by billing. Deleting an ICARUS account removes token ciphertext and account associations but retains unlinked replay-prevention hashes. **Deleting the ICARUS account does not cancel the Google Play subscription.** Show users where to cancel in Google Play. [5]

## Recovery notes

Missing credentials, unknown product IDs, wrong periods/prices, unavailable plans, or the wrong Android version leave checkout disabled. A verification timeout is not evidence that payment was canceled: restore or check Google before retrying. Entitlement checks refresh saved purchase status after five minutes and stop trusting it after ten minutes without successful revalidation; temporary Google outages can therefore interrupt paid feature access rather than leave stale access enabled indefinitely.

Existing legacy purchases without this app's server-issued account binding require manual account-ownership review. Do not simply attach a purchase token to whichever user submits it. No real customer purchase was performed as part of the automated tests.

## Suggested Play release notes

```
<en-US>
Added ICARUS Ascend, Zenith and Crest membership options.
Google Play prices, purchase review, restore and subscription management.
Server-verified Premium access and developer-controlled complimentary grants.
Billing remains gated until account setup and license testing are complete.
</en-US>
```

## Official references

[1] Google Play Developer API access: https://developers.google.com/android-publisher/getting_started

[2] Product setup and real-time notifications: https://developer.android.com/google/play/billing/getting-ready

[3] Authenticated Pub/Sub push: https://cloud.google.com/pubsub/docs/authenticate-push-subscriptions

[4] Google Play Billing testing: https://developer.android.com/google/play/billing/test

[5] Purchase verification and entitlement security: https://developer.android.com/google/play/billing/security

[6] Google Play tax-inclusive pricing: https://support.google.com/googleplay/android-developer/answer/138000
