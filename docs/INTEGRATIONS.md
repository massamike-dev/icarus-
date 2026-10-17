# ICARUS account integrations

## What is implemented

ICARUS has ten server-side, read-only adapters and an authenticated Integrations panel. These are real HTTP dispatch paths, tested with synthetic provider responses. The code being present does **not** mean an account is connected or that its production OAuth configuration has been verified.

| Integration | Implemented read operation | Permission |
| --- | --- | --- |
| Gmail | Search messages; fetch headers and snippets | `gmail.readonly` |
| Google Calendar | Search primary-calendar events within a bounded date range | `calendar.events.readonly` |
| Google Drive | Search file names and metadata; no file download | `drive.metadata.readonly` |
| Outlook Email | Search headers and body previews | `Mail.Read` |
| Outlook Calendar | Read a bounded calendar view, filter returned subjects/locations | `Calendars.Read` |
| Microsoft Teams | List/filter joined teams; no message access | `Team.ReadBasic.All` |
| SharePoint | Search site metadata; no document download | `Sites.Read.All` |
| Dropbox | Search file names and metadata; no download | `files.metadata.read` |
| GitHub | Search public issues and pull requests; no private repository or code access | `read:user` OAuth login, no repository-write scope |
| Slack | Search messages visible to the authorizing user | `search:read` **user** token |

Google scopes are prefixed `https://www.googleapis.com/auth/`. Microsoft also uses `offline_access` to support refresh. These scopes can require provider review or tenant-admin consent. Teams and SharePoint require an eligible work/school account. Slack search availability depends on workspace policies and the user's search visibility.

Reads use the provider's REST API directly. They do not depend on the chat model or an OpenAI API key. OAuth tokens are never supplied to the language model. No ChatGPT/dot grant is copied or reused. Additional image generation, full file-content search, sending messages, modifying events/files, code changes, and purchases are not implemented by these adapters. Existing Android actions and approval barriers are unchanged.

## Security and status

- Every endpoint requires an ICARUS session. The server determines the user ID, never the request body.
- Tokens are stored per user and integration, encrypted with AES-256-GCM and authenticated against the record identity. Browser code receives safe connection metadata, never access/refresh tokens or client secrets.
- OAuth state is short-lived, one-time, and bound to user plus integration. Authorization codes are exchanged only on the server. Supported providers use PKCE; consult the auth implementation for Slack's explicit PKCE opt-in.
- Provider origins, read operations, scope sets, and HTTP methods are fixed in code. No arbitrary URLs, redirect-following, methods, external tool names, or credentials are accepted from a model/browser.
- Results are limited to 1–10 items (default five), queries to 1,000 characters, individual result text to 1,200 characters, provider response bodies to 1 MB, and each provider request to 20 seconds. Calendar default range is the next 30 days, maximum 366 days.
- Pagination is deliberately not followed. Truncation is disclosed. Outlook calendar filtering examines only its first bounded page (100 events); Teams filtering likewise examines only its returned page. Zero matches are not proof that the account contains no matching information.
- Provider failures return generic error keys, never raw OAuth/provider error responses. Results are untrusted text, not instructions to execute device or account actions.
- `supported` means an adapter exists. `configured` means the required server OAuth configuration is present. `connected` means a usable stored authorization exists. `liveVerified` means this user/integration had a successful read after its most recent connection. It is historical evidence, not a promise that the token remains usable. `lastVerifiedAt` identifies when.
- Disconnect removes local encrypted credentials and pending authorization state. It does not claim to revoke the provider-side grant: users can revoke that separately in the provider's connected-app settings. Account deletion also removes integration credentials, OAuth state, and verification metadata.

## Deployment setup (not performed by this change)

1. Register your own confidential OAuth apps with Google, Microsoft, Dropbox, GitHub, and Slack. Request only the scopes above. Enabling APIs, changing app permissions, granting tenant consent, and creating persistent credentials require the account owner's approval.
2. Choose the app's HTTPS OAuth callback URL, typically `https://YOUR-ICARUS-HOST/`, and register that exact URI with every provider. Match it in `ICARUS_OAUTH_REDIRECT_URI`. The frontend callback completes the exchange through an authenticated POST. The current ICARUS session must still be available in the same browser; signing in to a different ICARUS account will fail state validation.
3. Set server-only configuration in the hosting secret store. Do not put any of these in Vite/browser variables, source control, URLs, logs, or chat:

   - `ICARUS_INTEGRATION_KEY`: a securely generated 32-byte key encoded as base64, separate from the session secret.
   - `ICARUS_OAUTH_REDIRECT_URI`: exact registered redirect URI.
   - `ICARUS_GOOGLE_CLIENT_ID`, `ICARUS_GOOGLE_CLIENT_SECRET`
   - `ICARUS_MICROSOFT_CLIENT_ID`, `ICARUS_MICROSOFT_CLIENT_SECRET`
   - `ICARUS_DROPBOX_CLIENT_ID`, `ICARUS_DROPBOX_CLIENT_SECRET`
   - `ICARUS_GITHUB_CLIENT_ID`, `ICARUS_GITHUB_CLIENT_SECRET`
   - `ICARUS_SLACK_CLIENT_ID`, `ICARUS_SLACK_CLIENT_SECRET`
   - `ICARUS_SLACK_PKCE=true` only when enabled for the registered Slack app.

   Missing/invalid key or client configuration fails closed. Keep the encryption key backed up separately; replacing it makes existing encrypted credentials unreadable and requires reconnecting. Use HTTPS in production. Restrict access to the JSON data file and backup copies. This store is single-process: multi-instance deployments need a transactional shared database and equivalent encryption/state-consumption guarantees.
4. For Slack, request `search:read` as `user_scope`, not bot scope. The broker uses `authed_user.access_token`; a bot token cannot serve this operation. Do not add write scopes.
5. With the owner present, choose Connect in the Integrations panel, review the provider's consent screen, and complete only the requested authorization. No account grants have been made by this implementation.
6. Run a harmless read for each connected account. Confirm the correct account/tenant, returned source links, token refresh, expired/revoked-token behavior, and cross-user isolation. Until then, only mocked test verification is established.

## Authenticated API

- `GET /api/integrations`: safe catalog and per-user status.
- `POST /api/integrations/:id/connect`: starts bounded OAuth, returns `{url}`.
- `POST /api/integrations/:id/callback` with `{code,state}`: validates and consumes state, exchanges code, returns safe metadata.
- `DELETE /api/integrations/:id`: local disconnect.
- `POST /api/integrations/:id/query` with `{query,limit?,timeMin?,timeMax?}`: returns `{integration,results,sources,reply,readOnly,liveVerified,checkedAt,truncated}`. Date range fields apply only to calendars.

These endpoints do not silently enable integrations for ordinary chats. Read results are displayed in the explicit Integrations controls and are not automatically saved as Memory.

## Validation

Run `cd web && npm test` and `npm run build`. Focused tests are `node --test server/integration-auth.test.js server/integration-service.test.js` plus the integration UI/route tests. All provider responses used by tests are synthetic. Tests never create grants or use real credentials.

## Official API references

- [Gmail message search](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list)
- [Google Calendar event list](https://developers.google.com/workspace/calendar/api/v3/reference/events/list)
- [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Microsoft mail search](https://learn.microsoft.com/en-us/graph/search-query-parameter)
- [Calendar view](https://learn.microsoft.com/en-us/graph/api/calendar-list-calendarview?view=graph-rest-1.0)
- [Joined Teams](https://learn.microsoft.com/en-us/graph/api/user-list-joinedteams?view=graph-rest-1.0)
- [SharePoint site search](https://learn.microsoft.com/en-us/graph/api/site-search?view=graph-rest-1.0)
- [Dropbox search](https://docs.dropboxapi.com/dropbox-api/api-reference/user-endpoints/files/search-v-2)
- [GitHub issue search](https://docs.github.com/en/rest/search/search#search-issues-and-pull-requests)
- [Slack message search](https://docs.slack.dev/reference/methods/search.messages/)
- [Slack OAuth](https://docs.slack.dev/authentication/installing-with-oauth/)

OpenAI Responses connectors and GitHub MCP were researched but are not used by this implementation. Direct read adapters avoid exposing provider access tokens to an additional model service and avoid legacy connector/model compatibility constraints.
