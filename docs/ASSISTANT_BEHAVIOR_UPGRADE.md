# ICARUS assistant behavior upgrade

Prepared against main `3402a416cd787f2e14f33efa587ec027bb6a99cf`.
This is a local implementation, not a deployed release.

## Behavior

- Answers lead with the useful result, match requested depth, ask focused
  questions only for missing essentials, and avoid repeated filler.
- Follow-through means completing useful work within the current turn and
  giving a concrete next step when blocked. It does not claim a background
  runner, reminder scheduler, or automatic memory saving exists.
- Voice guidance prioritizes a short spoken answer before the existing Android
  500-character clipping boundary.
- Action proposals, device reports, cancelled/failed/unknown outcomes, and
  accepted requests remain distinct. No extra execution permissions are added.

## Context

The latest 30 messages remain intact. Up to six earlier user excerpts provide
the original request and relevant older constraints, in chronological order.
Excerpts are capped at 2,000 characters and labeled when truncated. Old assistant
proposals/reports are not retrieved out of their result context.

Saved Memory retains the newest ten entries plus up to ten older entries ranked
against the latest user request, with newer entries winning ties. This preserves
the existing 20-entry budget while recovering relevant older preferences. It is
lexical retrieval, not semantic recall or a guarantee that every relevant fact
will be found. Newer corrections take precedence. Data is explicitly labeled as
historical reference outside system instructions; it cannot grant action access.

Temporary chat/voice and account/conversation isolation remain unchanged. No
new memory or task records are silently created.

## Current information

Chat now recognizes explicit requests such as "search the web for …", "look up
…", and "could you please search the web for …", just as voice supports explicit
search. Search mode remains read-only, with no device proposal tools. Merely
mentioning "latest" or "current" does not automatically send a private chat to
a search service.

Search receives only the current explicit query, not saved Memory or earlier
conversation. A purely referential request asks for a standalone topic. This
deliberately trades contextual search convenience for control over disclosure.
Results retain source URLs and require a completed search result; a failed or
incomplete lookup is never described as verified current information.

Changing a prompt cannot change training knowledge. The default model remains
unchanged pending an explicit model selection; no unsupported cutoff is claimed.

## Provider compatibility

Existing OpenAI-compatible Chat Completions remains the default. Set
`ICARUS_AI_API_MODE=responses` to use Responses for ordinary replies, structured
command interpretation, and reviewed device-action proposals. This is needed
for models whose tool support is exposed through Responses. The existing
`ICARUS_AI_MODEL`, `ICARUS_AI_BASE_URL`, and `ICARUS_AI_API_KEY` settings still
apply. Search already uses Responses.

The adapter sets `store:false`, keeps strict command validation, rejects partial
or malformed Responses output, and preserves multiple tool calls for the
existing single-action rejection. It does not automatically retry actions.

Provider references:
- https://developers.openai.com/api/docs/guides/migrate-to-responses
- https://developers.openai.com/api/docs/guides/function-calling

## Verification and rollout

Run `cd web && npm ci && npm test && npm run build`, then from the repository root
`node --test tests/native-contract.test.mjs tests/private-hud-contract.test.mjs`.
Tests cover context budgets, corrections, temporary isolation, natural search,
read-only search, provider protocol compatibility, and native review contracts.

Mocked tests do not prove real-provider model access, billing, tool support or
behavior quality. Before rollout, smoke-test the selected model with an ordinary
chat, a short voice answer, one reviewed device proposal, a cancelled action,
an unknown result, a temporary chat, and a cited live search. Use a staging
configuration first; retain the prior model/API-mode configuration for rollback.
Review wording quality with representative prompts rather than treating prompt
substring assertions as proof of model behavior.

No native permission, wake-word, driving, Bluetooth, wearable, device execution,
or Android confirmation code is changed by this behavior upgrade. It does not
replace the canonical Android build/lint/device validation required for a release.
