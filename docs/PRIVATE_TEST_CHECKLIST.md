# Private testing checklist

The authenticated private cloud Home screen displays a testing checklist only for the provisioned private tester whose immutable ID matches the store bootstrap marker. Public accounts and anonymous on-device sessions do not receive this capability. Every checklist API request checks the private configuration and account identity server-side.

Ten initial implementation/verification tasks start unchecked. Add tasks with test notes, mark them Testing, complete them, and reopen them from collapsed completion history. Completion waits for a successful save; failures retain the active item. Account deletion removes the checklist.

This change is source preparation, not a released APK or deployed service. Base: ICARUS Test 1.6.22 build 53. No version bump, production deployment or signing changes.

Delivery requires deploying the updated private web service, then opening the Test app's private cloud sign-in and Home screen. The bundled anonymous local core intentionally does not expose this account-owned list. Phone/TalkBack acceptance still needs device verification.

Storage follows ICARUS_DATA_FILE. The existing render-private-test.yaml uses ephemeral /tmp storage; durable hosting storage is required before promising survival across server redeployment. Ordinary reloads use the saved server records. Do not silently change the service's storage path or provision paid hosting.
