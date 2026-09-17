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
- Settings contains in-app patch notes. Drafts survive request failure; retries
  restore a draft and never automatically re-execute an action.

## Verification and release gates

Local web tests cover auth/privacy, navigation, invalid proposals, old hosts,
read-only search/citations, Android errors/timeouts, and confirm-before-execution
with duplicate-click protection. Run CI Android unit tests, lint and debug build
on this branch before declaring native changes verified. No signed release is
published from the branch.

Still required before production: real-provider search/tool-call smoke tests,
Android 1.6.6 device checks for permission denial, exact/ambiguous contacts,
unavailable target apps, locked screen and headset/wake behavior. Mocked tests do
not establish these. Review narrow-screen layout on an actual browser/device.

## Remaining product gaps (not claimed complete)

- No general account connector registry, email/purchases, or multi-step durable
  task runner. Device results remain local to the current Chat view.
- Voice has saved Memory but no shared Chat conversation ID or voice history.
- This batch does not establish reliable screen-off wake, Bluetooth routing,
  interruption/barge-in, default-assistant behavior, or automatic follow-up.
- Search is explicit, not automatic for every time-sensitive question.
- Existing static design audit findings in legacy auth/memory forms remain.

The batch improves real execution but is not the entire all-encompassing
assistant release. Keep it held until remaining agreed fixes and checks finish.
