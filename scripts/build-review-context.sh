#!/usr/bin/env bash
set -euo pipefail

review_base_sha="${BASE_SHA:?BASE_SHA is required}"
review_head_sha="${HEAD_SHA:?HEAD_SHA is required}"
review_output="${REVIEW_CONTEXT_OUTPUT:-review-context.md}"

changed_files="$(git diff --name-only "$review_base_sha" "$review_head_sha")"
file_count="$(printf '%s\n' "$changed_files" | sed '/^$/d' | wc -l | tr -d ' ')"
line_stats="$(git diff --numstat "$review_base_sha" "$review_head_sha")"
added="$(printf '%s\n' "$line_stats" | awk '{ if ($1 ~ /^[0-9]+$/) total += $1 } END { print total + 0 }')"
deleted="$(printf '%s\n' "$line_stats" | awk '{ if ($2 ~ /^[0-9]+$/) total += $2 } END { print total + 0 }')"

risk="low"
reasons="Small or non-runtime change"
if printf '%s\n' "$changed_files" | grep -Eq '(^|/)(auth|security|privacy|billing|payment|release|signing)(/|\.|$)|AndroidManifest\.xml$|(^|/)render\.yaml$|^\.github/workflows/|WakeWord|NativeCommand|IcarusNativeBridge|Account'; then
  risk="high"
  reasons="Sensitive authority, release, infrastructure, privacy, or device-control path changed"
elif printf '%s\n' "$changed_files" | grep -Eq '\.(kt|java|js|jsx|ts|tsx)$|(^|/)(server|native|app)/|gradle|package(-lock)?\.json$'; then
  risk="medium"
  reasons="Runtime implementation or dependency surface changed"
fi

tests="$(printf '%s\n' "$changed_files" | grep -Ei '(^|/)(test|tests|__tests__)(/|$)|Test\.(kt|java)$|\.(test|spec)\.(js|jsx|ts|tsx)$' || true)"

{
  echo "# Review context"
  echo
  printf -- '- Base: `%s`\n' "$review_base_sha"
  printf -- '- Head: `%s`\n' "$review_head_sha"
  echo "- Risk: **$risk**"
  echo "- Scope: $file_count files, +$added / -$deleted lines"
  echo "- Reason: $reasons"
  echo
  echo "## Changed files"
  echo
  printf '%s\n' "$changed_files" | sed '/^$/d; s/^/- `/; s/$/`/'
  echo
  echo "## Changed tests"
  echo
  if [ -n "$tests" ]; then
    printf '%s\n' "$tests" | sed 's/^/- `/; s/$/`/'
  else
    echo "- None detected"
  fi
  echo
  echo "## Review instruction"
  echo
  echo 'Review objective correctness, security, privacy, tests, and regressions introduced by this diff. Expand beyond changed files only to direct dependencies needed to prove a finding. Ignore style-only and speculative suggestions. Follow `docs/CODE_REVIEW.md` and `.macroscope/approvability.md`.'
} > "$review_output"

cat "$review_output"
