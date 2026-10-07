## Intent

<!-- State the user-visible outcome in one or two sentences. -->

## Review context

- Risk tier: <!-- low / medium / high -->
- Primary path changed:
- Related tests:
- Rollback or fallback:
- User data or permissions changed: <!-- no, or explain -->
- External side effects changed: <!-- no, or explain -->

## Objective verification

<!-- List commands/checks actually run and their results. -->

- [ ] The changed behavior was exercised on its real execution path.
- [ ] Failure and fallback behavior were checked.
- [ ] Tests cover the regression or new invariant.
- [ ] No unrelated generated files, secrets, debug code, or test-only bypasses are included.

## Reviewer focus

<!-- Name the 1–3 invariants most likely to break. Avoid style requests and speculative rewrites. -->

1.
2.

## Approva­bility

- [ ] This PR is small and low-risk under `.macroscope/approvability.md`.
- [ ] Human review is required because:
