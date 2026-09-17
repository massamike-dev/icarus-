# ICARUS 1.6.5 / build 36 readiness audit

Status: signed test APK published on September 17, 2026; automated checks pass. Ready for device testing, NOT verified for complete hands-free operation or production rollout. Google Play rollout remains paused.

## Implemented in this candidate

- Wake detection hands the microphone to a service-owned voice session instead of launching MainActivity and depending on WebView events.
- Native sequence: capture, interpret, validate, confirm when needed, execute, speak, rearm after speech completion. Timeouts cancel stale turns rather than executing them later.
- Persisted listening opt-in. Stop-listening notification and spoken disable survive app resume and restart. Fresh installs require explicit enablement.
- Offline deterministic interpretation for battery, flashlight, media volume, timers, calls, app launching, navigation, cancellation and disabling listening.
- Authenticated cloud interpretation for alternate phrasings of the same restricted actions. This endpoint does not execute actions. Native confirmation cannot be disabled by the model.
- Spoken confirmation for calls, navigation, app launching and timers; ambiguous contacts are rejected rather than guessed.
- The lock screen is respected. Actions requiring an external activity ask for unlock. A submitted Android intent is reported as a request, not proof of completion.
- Voice-session authentication synchronized from the trusted web session; logout clears it and disables listening.
- Web enable, disable and stop controls; pre-build-36 voice events receive an upgrade explanation instead of being silently discarded.
- Wake-worker read errors report failure instead of leaving a false listening status after a worker exception.

## Verification

- Local web/API tests: 11 passing, including authentication, command validation, unsupported actions, range checks, and preventing fabricated execution/confirmation.
- Production web build: passes.
- Android debug compile, unit tests (including command parser regression tests), lint and APK assembly: passed in GitHub Actions before merge.
- Android release unit tests, lint, APK/AAB assembly and workflow signature verification: passed. Signed APK and AAB published.
- Downloaded APK: 89,204,742 bytes; ZIP integrity check passed; SHA-256 matches GitHub's asset digest: `f849ee20c1cebb9c04cfb0214848ccbbce8a7691dbf5adbb84b0a0ede5bb9b79`.
- Live website check after merge still returned the earlier JavaScript bundle without `configure_voice_session` or the new hands-free controls. Deployment of the new web controls and cloud endpoint is not verified.
- Real-device screen-off wake, microphone handoff, speech recognition, call/navigation launching, contact permissions and battery consumption: NOT verified.

## Remaining release blockers / limitations

1. Deploy and verify the merged web frontend/backend. The APK loads a hosted web interface, so publishing the APK alone does not deliver the new web controls or prove the cloud endpoint is live.
2. Test on Michael's actual device: enable, lock screen, wake, battery, flashlight, confirmation accepted/refused, ambiguous contact, disable, reopen, network loss, microphone interruption and process restart.
3. Android can suppress background activity launches even when startActivity returns normally. Call/navigation/timer intent dispatch is deliberately not labeled successful completion. Default-assistant role or a supported user-visible handoff needs design and device testing.
4. Speech recognition availability/offline behavior depends on the installed Android recognizer. A fixed offline command parser does not make speech transcription itself offline.
5. Voice barge-in during spoken output is not implemented. The notification stop control and web stop control can cancel a turn. Spoken stop works when command capture is active.
6. Bluetooth-specific microphone routing, reconnection and audio-focus behavior require further implementation/testing. The current source label alone does not guarantee headset routing.
7. SMS remains composer-based in the legacy native bridge. Notifications, hands-free message sending, email/calendar/music account connections, and a general MCP/OAuth connector registry are not implemented by this candidate.
8. Existing activity-based bridge actions and legacy capture code remain for compatibility. The repaired wake path bypasses them; they are not evidence of complete hands-free support for every advertised action.
9. No Play production-readiness claim: privacy/declarations, billing verification, account isolation, Play pre-launch checks and optional glasses gates still apply.

## Published build and evidence

- User explicitly authorized publication to `massamike-dev/icarus-`, merge after checks, and generation of the signed test APK.
- [PR #28](https://github.com/massamike-dev/icarus-/pull/28) merged after all three PR workflows passed. Release source commit: `e84af1875dc9559fc6bd6522d5599751292f313e`.
- [Signed release workflow](https://github.com/massamike-dev/icarus-/actions/runs/35188228603).
- [ICARUS 1.6.5 test release](https://github.com/massamike-dev/icarus-/releases/tag/v1.6.5-test).
- [Download signed build 36 APK](https://github.com/massamike-dev/icarus-/releases/download/v1.6.5-test/ICARUS-latest.apk).
- Build 35 on Play does not contain these fixes. No Play rollout was performed.
