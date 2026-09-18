# Unified assistant batch — held for coordinated release

Do not merge/deploy this batch piecemeal. Michael requested one tested batch with
in-app patch notes. Main and the live deployment remain unchanged while this
branch is validated. Version reserved: Android 1.6.6 / build 37.

## Implemented

- Chat uses a validated phone-action proposal tool, visible confirmation, a
  correlated native response, and honest request-accepted/unknown/failure states.
- New host protocol gate blocks execution on older APKs. Exact contact/app
  matching prevents silently selecting a partial match. Native launch failures
  return to Chat rather than escaping on the UI thread.
- Explicit Search web mode uses OpenAI Responses web_search and clickable
  citation URLs. It is read-only and cannot propose phone actions. Other
  providers correctly report search unavailable. Set ICARUS_WEB_SEARCH=false
  to disable; existing OpenAI key is reused. Provider availability/billing must
  still be verified in the actual deployment environment.
- Voice commands fall back to short conversational answers with saved Memory.
  “Search the web …” / “Look up …” explicitly invokes read-only search. Voice
  replies are currently truncated by the native host to 500 characters.
- Settings and the Android update manifest contain current patch notes. Drafts survive request failure; retries
  restore a draft and never automatically re-execute an action.
- Cloud voice turns share the selected Chat conversation. Native session
  snapshots reject late replies after account or conversation changes. The
  temporary selection also applies to later wake turns and excludes saved
  history, Memory, conversation IDs, and result uploads.
- Server-backed action reports are saved once with their owning conversation
  and labeled device-reported rather than independently verified. Report-upload
  retry never repeats device execution. Direct offline commands have no server
  proposal and remain outside cloud history; uploads are best effort.
- Completed chat/voice turns are saved atomically and retried with stable IDs.
  Provider failure does not leave orphan user messages. Conversation deletion
  retains only a content-free turn-ID/fingerprint tombstone to stop stale retry
  resurrection; account deletion also removes these tombstones.

## Verification and release gates

All 47 local web tests and the production bundle pass. Coverage includes
auth/privacy, navigation, shared voice/Chat history, private temporary turns,
atomic persistence, idempotent turn/result retries, deleted conversations,
invalid proposals, old hosts, read-only search/citations, Android errors/timeouts,
confirmation, callback cleanup, and late responses after navigation or sign-in
changes. Six pure Kotlin session-state tests also pass locally, including an
explicit empty New chat reset invalidating an in-flight first voice response.

Full Android build/lint and web CI for this revision must pass before release;
the current results are recorded on the draft PR. At the earlier commit 52625bf,
GitHub web run 35287896496, Android test/lint/debug run 35287896441 and canonical
debug run 35287896417 all passed. The signed release job was skipped. No release
has been published from the branch.

The cloud browser refused the local preview with ERR_BLOCKED_BY_CLIENT; DOM
interaction tests passed, but a real-browser visual check remains unverified.

Still required before production: real-provider search/tool-call smoke tests,
Android 1.6.6 device checks for permission denial, exact/ambiguous contacts,
unavailable target apps, locked screen and headset/wake behavior. Mocked tests do
not establish these. Review narrow-screen layout on an actual browser/device.

## Remaining product gaps (not claimed complete)

- No general account connector registry, email/purchases, or multi-step durable
  task runner. Result upload has no persistent offline delivery queue.
- Failed Chat report uploads can be retried while Chat remains open. Navigating
  away or reloading loses that unsaved retry queue. Already-dispatched actions
  still attempt to save their result after navigation; a failed upload after
  unmount is not retained for a later retry.
- This batch does not establish reliable screen-off wake, Bluetooth routing,
  interruption/barge-in, default-assistant behavior, or automatic follow-up.
- Search is explicit, not automatic for every time-sensitive question.
- Existing static design audit findings in legacy auth/memory forms remain.

The batch improves real execution but is not the entire all-encompassing
assistant release. Keep it held until remaining agreed fixes and checks finish.
