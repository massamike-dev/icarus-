# ICARUS migration status

The production code path is repository-owned. Base44 is no longer required by the web client, API, authentication, conversation store, memory store, or Android host.

## Replaced services

| Former Base44 capability | ICARUS replacement |
| --- | --- |
| Auth | `/api/auth/register`, `/api/auth/login`, signed 30-day sessions |
| Conversation + Message entities | user-isolated JSON persistence behind `/api/chat` and `/api/conversations` |
| Memory entity | user-isolated `/api/memories` CRUD |
| `nativeConversationTurn` | compatible `/api/functions/nativeConversationTurn` route |
| `InvokeLLM` | server-side OpenAI-compatible provider request |
| Base44 hosting | production Node service serving the Vite bundle and API |

## Cutover

1. Deploy `web/` as a persistent Node service with `npm ci && npm run build` and `npm start`.
2. Attach persistent storage and set `ICARUS_DATA_FILE` to its mounted path.
3. Set `ICARUS_SESSION_SECRET`, `ICARUS_AI_API_KEY`, `ICARUS_AI_BASE_URL`, and `ICARUS_AI_MODEL` in the host secret manager.
4. Point `icarusassistant.com` at the service and verify `/api/health` returns `base44: false`.
5. Keep the Android build variable `ICARUS_WEB_URL=https://icarusassistant.com` and build through GitHub Actions.
6. Export existing user data from Base44 only if those test accounts must be retained; password hashes cannot be ported and users must reset credentials.

Base44 may remain online temporarily for rollback, but no source-controlled production component requires it after DNS cutover.
