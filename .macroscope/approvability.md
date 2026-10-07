# ICARUS approvability policy

Suggest approval without human review only when all of the following are true:

- The change is small, localized, and fully explained by the pull request.
- Automated checks pass.
- The diff changes documentation, copy, tests, static assets, or an implementation that follows an already-tested pattern.
- No objective correctness, security, privacy, or regression issue is found.
- The change does not broaden permissions, data collection, device authority, or external side effects.

Always require human review for changes involving:

- authentication, account deletion, secrets, session handling, privacy, or user data;
- billing, subscriptions, signing, releases, Play Console configuration, or production deployment;
- Android permissions, foreground services, wake-word behavior, background execution, direct calling/texting, camera, contacts, location, Bluetooth, OBD-II, Meta, or XREAL;
- AI-provider routing, system prompts, native-command authorization, or claims that a device action completed;
- database or stored-data formats, destructive operations, migrations, dependency or lockfile changes;
- manifests, Gradle configuration, GitHub Actions, Render configuration, branch protection, or this policy;
- broad refactors, cross-module behavior, public API changes, or changes lacking focused tests.

A human reviewer may approve a high-risk change after validating the relevant threat, failure, permission, and rollback paths. Never auto-approve merely because CI is green.
