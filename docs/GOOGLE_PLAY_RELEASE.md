# ICARUS Google Play release packet

Release candidate: **1.6.3 (34)**
Package: `com.icarusalmighty.app`  
Target SDK: 36  
Support: `wennigworks@gmail.com`

## Public URLs

- Privacy policy: `https://icarusassistant.com/privacy-policy`
- Account deletion: `https://icarusassistant.com/account-deletion`
- Service health: `https://icarusassistant.com/api/health`

Do not submit until those URLs are served by the independent production deployment.

## Suggested Data safety inventory

Use this as an implementation inventory, then answer the Play Console form exactly as shipped.

| Data | Why | Handling |
| --- | --- | --- |
| Email/name/account ID | Authentication and account management | Stored, user-associated, encrypted in transit |
| Conversations and selected memories | Assistant and memory features | Stored, user-associated, deletable |
| Voice transcript after wake | Execute the requested assistant turn | Sent only after wake/explicit capture; provider processing may apply |
| Continuous wake audio | Local wake detection | Not intentionally uploaded or retained |
| Contacts | Resolve an explicit call/text recipient | Accessed on device for the requested action |
| Precise/approximate location | Active Driving Mode and navigation | Feature-specific; no background-location permission |
| Camera/media | User-requested capture or montage | User initiated; originals are not overwritten |
| Bluetooth/device identifiers | Audio, OBD-II, Meta, or XREAL connection | Optional integration functionality |
| Vehicle telemetry | Display read-only OBD-II status | Read-only; no ECU control commands |
| Purchases | Subscription entitlement if billing is enabled | Google Play plus server verification required before enabling |

## App-content declarations

- Ads: declare **No** unless advertising is added before release.
- App access: provide a working reviewer account and exact steps for Chat, wake listening, Driving Mode, and optional integrations.
- Content rating: complete the questionnaire from actual assistant behavior.
- Target audience: choose the real intended audience; do not select children unless the product is redesigned for Families compliance.
- Data safety: use the inventory above and include every enabled third-party SDK.
- Account deletion: submit the public deletion URL above.
- Foreground service: describe user-enabled local wake-word listening, persistent notification, and explicit stop control.

## Closed-test script

Each tester should install from the Play closed-test link and record device/Android version plus pass/fail for:

1. Fresh install, account creation, sign-in, sign-out, and account deletion.
2. Permission accepted and denied for microphone, notifications, contacts, camera, location, and nearby devices.
3. Wake phrase in quiet, TV/music, outdoors, vehicle idle, road noise, windows down, phone near/far, and Bluetooth connected/disconnected.
4. Chat and memory creation/deletion; second account cannot see the first account's data.
5. Driving Mode without accessories, then optional OBD-II/XREAL/Meta disconnect fallback.
6. Force-stop, reboot, offline mode, background/locked screen, and return to foreground.
7. In-place update from the preceding Play build without data loss or signing error.

Record false wakes and missed wakes. Production access requires meaningful tester feedback, not only opt-ins.

## Release gates

- [ ] Independent service deployed and all three public URLs return correctly.
- [ ] Play App content and Data safety forms completed.
- [ ] Signed `1.6.3 (34)` AAB uploaded to internal testing.
- [ ] Purchase buttons remain disabled unless server-side Play verification passes.
- [ ] Real-device wake and permission matrix completed.
- [ ] Two-user isolation and deletion verified against production.
- [ ] Required closed-test duration/tester count completed if Play Console requires it.
- [ ] Pre-launch report has no blocking crashes, ANRs, security, or accessibility findings.
- [ ] Staged production rollout begins at a small percentage and is monitored before expansion.
