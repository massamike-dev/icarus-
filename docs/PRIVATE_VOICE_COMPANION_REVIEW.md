# Private voice and companion update — 1.6.7-test

Public release remains held. Deliver only through `work/icarus-private-test`, the restricted test service, and the signed private APK workflow. Do not merge the private endpoint or delivery configuration into the public release.

## Changes

- Initialize Android speech before command capture and acknowledge a wake with “Yes?”. Report speech-engine, recognition, microphone and service errors instead of silently rearming.
- Release wake microphone resources before handing capture to Android speech recognition. Keep decoder lifetime owned by its worker; reject stale callbacks and duplicate recognition results.
- Make wake sensitivity effective by removing per-keyword overrides. Hold a bounded wake lock during command/network/speech work after the wake recorder closes.
- Add explicit Talk now controls and observable voice diagnostics: audio samples, sound activity, Android microphone silencing, detections, current stage, media volume and last error. Keep wake audio local and do not add transcript logging.
- Replace the missing head presentation with a compact full-body ICARUS companion across authenticated screens. Show actual native status, provide deliberate Talk now, move/hide/restore controls, and tuck the companion while editing. Respect reduced motion and keyboard focus.
- Include mobile containment fixes and in-app patch notes in the same private update.

## Verification and limits

Local web tests/build, native bridge contract tests and private-delivery boundary tests are required before pushing. Android compilation, unit tests, lint, signature checks and endpoint/isolation checks are enforced in CI before private delivery.

The actual Sherpa engine initialized with the configured Hey ICARUS tokens. An official positive fixture verified keyword inference; silence and unrelated speech fixtures produced no Hey ICARUS detection. These checks do not establish recognition of Michael's voice. His supplied screen recording contains no audio track.

Browser visual verification was blocked by browser URL policy; no alternate browser route was used. Existing unrelated form-validation and textarea audit findings remain outside this update. Phone layout, sound output and screen-off operation still require device testing.

The private service still needs an AI provider key for live AI and web search. Direct native commands such as battery do not require that key. Android speech recognition availability and network requirements depend on the installed recognition service.

## Phone acceptance check

1. Stop hands-free listening in the regular ICARUS app so it does not compete for microphone input.
2. Install the signed update over ICARUS Test, using the existing private account.
3. Enable hands-free, select Talk now, wait for “Yes?”, then say “battery”. Check the spoken response and Voice diagnostics.
4. Repeat using “Hey ICARUS” with the screen on, then off. Check whether the wake count increases and whether capture and speech stages follow.
5. Check the companion across tabs, including hide/restore and text entry. Keep the public release held until the phone checks pass.

Android reference: https://developer.android.com/media/platform/sharing-audio-input

TTS package visibility: https://developer.android.com/training/package-visibility/use-cases#tts-service
