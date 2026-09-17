# ICARUS 1.6.5 / build 36 readiness audit

Status: local repair candidate, NOT a verified Android release. Google Play rollout remains paused.

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
- Android parser regression tests added; not run here because Android SDK/Gradle are unavailable locally.
- Android compile, unit tests, lint, signing, and APK/AAB publication: pending GitHub publication authorization and CI.
- Real-device screen-off wake, microphone handoff, speech recognition, call/navigation launching, contact permissions and battery consumption: NOT verified.

## Remaining release blockers / limitations

1. Obtain a green Android build and signed installable artifact. Do not describe this source candidate as a tested APK.
2. Test on Michael's actual device: enable, lock screen, wake, battery, flashlight, confirmation accepted/refused, ambiguous contact, disable, reopen, network loss, microphone interruption and process restart.
3. Android can suppress background activity launches even when startActivity returns normally. Call/navigation/timer intent dispatch is deliberately not labeled successful completion. Default-assistant role or a supported user-visible handoff needs design and device testing.
4. Speech recognition availability/offline behavior depends on the installed Android recognizer. A fixed offline command parser does not make speech transcription itself offline.
5. Voice barge-in during spoken output is not implemented. The notification stop control and web stop control can cancel a turn. Spoken stop works when command capture is active.
6. Bluetooth-specific microphone routing, reconnection and audio-focus behavior require further implementation/testing. The current source label alone does not guarantee headset routing.
7. SMS remains composer-based in the legacy native bridge. Notifications, hands-free message sending, email/calendar/music account connections, and a general MCP/OAuth connector registry are not implemented by this candidate.
8. Existing activity-based bridge actions and legacy capture code remain for compatibility. The repaired wake path bypasses them; they are not evidence of complete hands-free support for every advertised action.
9. No Play production-readiness claim: privacy/declarations, billing verification, account isolation, Play pre-launch checks and optional glasses gates still apply.

## Publication blocker

Automatic approval review rejected the attempted push to massamike-dev/icarus- because explicit destination authorization was required. No workaround attempted. Candidate remains local on fix/hands-free-build36, based on the prior local companion-quality commit. Approval is needed to publish, run Android CI and produce the new app link. Build 35 on GitHub/Play does not contain these fixes.
