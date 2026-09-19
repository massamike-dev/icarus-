# Private settings restoration — 1.6.8-test

Public release remains held. This change is for the restricted ICARUS Test service and signed test package only.

## Confirmed regressions

The migrated Settings page retained listening controls, account and privacy information but omitted configurable voice, wake sensitivity and local-model controls. Android's app-speech path had a hard-coded deep voice profile, while hands-free replies initialized the default English voice without applying that profile.

## Restoration contract

- One saved Android voice configuration must govern app speech and hands-free replies. The actual installed English voice catalog, speed, pitch, Deep & warm/Standard/Custom profiles and a preview let the user choose the sound.
- Deep & warm describes general vocal qualities. Installed engines do not reliably expose voice gender, and no specific person's identity or voice is promised.
- Saves and previews must report the correlated Android result. An unsupported older build must show a clear update requirement, not pretend a preference was applied.
- Wake sensitivity must preserve unrelated settings. Saved values take effect on the wake engine's next start; changes must not silently interrupt an active command.
- Local-model status, download and removal must reflect Android's actual result. Downloaded but unverified is distinct from ready. Model management does not imply automatic offline cloud-chat or voice fallback.
- Android speech and app-permission settings remain reachable through explicit shortcuts. User consent for hands-free listening remains unchanged.

The private Android package has its own preferences. It does not automatically inherit preferences from the regular ICARUS installation.

## Checks before delivery

Run web tests/build, native bridge contracts, private-delivery boundary tests, Android unit tests/lint and signed-package isolation checks. Test voice save success/failure, unsupported builds and local-model confirmation behavior. Review mobile control width, labels, focus, pending/error states and typing protection.

Phone acceptance: choose an installed voice, save and preview; reopen the app and check persistence; request battery through Talk now and then Hey ICARUS; confirm both use the selected voice. Speech output depends on the phone's installed engine and voice data.

## AI key status

OpenAI connector authorization was accepted, but OpenAI rejected the request to list available API-key project targets. No new key was created or configured from that attempt. The private server still needs a valid provider key and a successful temporary-chat smoke check before cloud responses can be declared ready.
