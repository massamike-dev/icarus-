"""Artifact-only candidate workflow regressions; no SDK, network, or real secrets."""

import base64
import contextlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/android-play-candidate.yml").read_text()
BRANCH = "work/icarus-play-candidate-20261014"
COMMIT = "b" * 40
CERTIFICATE = "6b87a7031aa5273d50d0e692875d0d284fd7e9b905bb7dba8e05c3ab67d7e2c6"
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"


def step(name):
    steps = re.split(r"(?=^      - (?:name|uses): )", WORKFLOW, flags=re.MULTILINE)[1:]
    matches = [entry for entry in steps if entry.startswith("      - name: " + name + "\n")]
    if len(matches) != 1:
        raise AssertionError(f"Expected exactly one workflow step named {name!r}")
    return matches[0]


def python_script(delimiter):
    match = re.search(r"^          python3 - <<'" + delimiter + r"'\n(.*?)^          "
                      + delimiter + r"$", WORKFLOW, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError(f"Missing workflow Python block {delimiter}")
    return textwrap.dedent(match.group(1))


def draft_pr():
    return {
        "number": 100,
        "isDraft": True,
        "headRefOid": COMMIT,
        "headRefName": BRANCH,
        "headRepositoryOwner": {"login": "massamike-dev"},
        "isCrossRepository": False,
        "state": "OPEN",
    }


class CandidateWorkflowTests(unittest.TestCase):
    def test_only_explicit_candidate_push_and_manual_events(self):
        trigger = WORKFLOW.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(trigger.strip(),
                         f"push:\n    branches: [{BRANCH}]\n  workflow_dispatch:")
        jobs = re.findall(r"^  ([a-z][a-z-]*):\n", WORKFLOW.split("\njobs:\n", 1)[1], re.MULTILINE)
        self.assertEqual(jobs, ["validate-web", "build-candidate"])
        guard = ("if: github.repository == 'massamike-dev/icarus-' && github.ref == "
                 f"'refs/heads/{BRANCH}' && (github.event_name == 'push' || github.event_name == 'workflow_dispatch')")
        self.assertEqual(WORKFLOW.count(guard), len(jobs))
        self.assertIn("CANDIDATE_BRANCH: " + BRANCH, WORKFLOW)
        self.assertNotIn("pull_request:", WORKFLOW)
        self.assertNotIn("pull_request_target", WORKFLOW)

    def test_permissions_and_actions_are_read_only_except_artifact_upload(self):
        permissions = re.findall(r"^\s+(contents|pull-requests|actions|id-token|packages|deployments): (\S+)$",
                                 WORKFLOW, re.MULTILINE)
        self.assertEqual(permissions, [("contents", "read"), ("contents", "read"), ("pull-requests", "read")])
        self.assertNotRegex(WORKFLOW, r"(?m)^\s*[a-z-]+:\s*write\s*$")
        self.assertNotIn("write-all", WORKFLOW)
        actions = re.findall(r"uses: (\S+)", WORKFLOW)
        allowed = {"actions/checkout@v4", "actions/setup-node@v4", "actions/setup-java@v4",
                   "gradle/actions/setup-gradle@v4", "actions/upload-artifact@v4"}
        self.assertEqual(set(actions), allowed)
        forbidden = ("gh release", "gh api", "git push", "git tag", "git commit", "--clobber",
                     "android-update-v2.json", "r0adkll/upload-google-play", "fastlane", "supply ",
                     "androidpublisher", "service_account", "SERVICE_ACCOUNT", "deploy:", "publish:")
        for text in forbidden:
            with self.subTest(text=text):
                self.assertNotIn(text, WORKFLOW)

    def test_exact_checkout_and_noninterrupted_signing(self):
        self.assertEqual(WORKFLOW.count("uses: actions/checkout@v4"), 2)
        self.assertEqual(WORKFLOW.count("ref: ${{ github.sha }}"), 2)
        self.assertEqual(WORKFLOW.count("persist-credentials: false"), 2)
        self.assertIn("cancel-in-progress: false", WORKFLOW)
        self.assertIn("group: icarus-play-candidate-${{ github.ref }}", WORKFLOW)
        self.assertIn("cache-disabled: true", WORKFLOW)
        self.assertIn("defaults:\n  run:\n    shell: bash", WORKFLOW)

    def test_all_web_and_source_validation_precedes_native_signing(self):
        validation, candidate = WORKFLOW.split("\n  build-candidate:\n", 1)
        for command in ("python3 .github/scripts/test-play-candidate-workflow.py",
                        "python3 .github/scripts/test-play-candidate-verifier.py",
                        "python3 .github/scripts/test_xreal_preview_verification.py",
                        "python3 .github/scripts/test_private_test_validation.py",
                        "npm test", "node --test tests/*.test.mjs native/android/scripts/*.test.mjs",
                        "npm run build -- --base=./"):
            self.assertIn(command, validation)
        self.assertIn("needs: validate-web", candidate)
        self.assertEqual(WORKFLOW.count("run: npm ci --include=dev --no-audit"), 2)
        self.assertNotIn("npm audit", WORKFLOW)
        self.assertNotIn("continue-on-error", WORKFLOW)
        native = step("Run native release unit tests and lint before accessing signing secrets")
        for command in (":app:testReleaseUnitTest", ":app:lintRelease", ":xreal:testReleaseUnitTest", ":xreal:lintRelease"):
            self.assertIn(command, native)
        self.assertIn("gradle --no-daemon", native)
        self.assertLess(WORKFLOW.index("id: native_validation"), WORKFLOW.index("id: source_authorization"))
        self.assertLess(WORKFLOW.index("id: source_authorization"), WORKFLOW.index("id: signing"))
        self.assertNotIn("if:", native)
        java_regressions = step("Run verifier regressions with Java 17 before accessing signing secrets")
        self.assertIn("run: python3 .github/scripts/test-play-candidate-verifier.py", java_regressions)
        self.assertLess(WORKFLOW.index("uses: actions/setup-java@v4"), WORKFLOW.index(java_regressions))
        self.assertLess(WORKFLOW.index(java_regressions), WORKFLOW.index("id: native_validation"))

    def test_pinned_official_bundletool_download_is_checked_before_signer(self):
        self.assertIn('BUNDLETOOL_VERSION: "1.18.3"', WORKFLOW)
        self.assertIn("BUNDLETOOL_SHA256: " + BUNDLETOOL_SHA256, WORKFLOW)
        self.assertIn("EXPECTED_UPLOAD_CERTIFICATE: " + CERTIFICATE, WORKFLOW)
        self.assertRegex(BUNDLETOOL_SHA256, r"^[a-f0-9]{64}$")
        download = step("Download and verify pinned official bundletool before signing")
        self.assertIn("https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar", download)
        self.assertIn("--proto '=https' --proto-redir '=https'", download)
        self.assertIn("curl --fail", download)
        self.assertIn("sha256sum --check --strict", download)
        self.assertNotIn("secrets.", download)
        self.assertNotIn("/latest", download)
        self.assertLess(WORKFLOW.index(download), WORKFLOW.index("id: native_validation"))
        self.assertNotIn("preview-baseline", WORKFLOW)

    def test_open_draft_pr_gate_uses_exact_checked_out_source(self):
        gate = step("Require an open draft PR for this exact source commit")
        self.assertIn('test "$(git rev-parse HEAD)" = "$GITHUB_SHA"', gate)
        self.assertIn('gh pr list --repo "$GITHUB_REPOSITORY" --state open --head "$CANDIDATE_BRANCH"', gate)
        self.assertNotIn("if:", gate)
        self.assertNotIn("secrets.", gate)

    def check_prs(self, prs):
        with tempfile.TemporaryDirectory() as temporary:
            (Path(temporary) / "play-candidate-pr.json").write_text(json.dumps(prs))
            environment = {"RUNNER_TEMP": temporary, "GITHUB_SHA": COMMIT,
                           "CANDIDATE_BRANCH": BRANCH, "GITHUB_REPOSITORY_OWNER": "massamike-dev"}
            with patch.dict(os.environ, environment, clear=True):
                exec(compile(python_script("PYPR"), "candidate-pr-gate", "exec"), {})

    def test_pr_gate_accepts_only_one_current_same_repo_open_draft(self):
        self.check_prs([draft_pr()])
        unrelated = draft_pr()
        unrelated["headRefOid"] = "c" * 40
        self.check_prs([draft_pr(), unrelated])
        for prs in ([], [draft_pr(), draft_pr()], [{}]):
            with self.subTest(prs=prs), self.assertRaises(SystemExit):
                self.check_prs(prs)
        changes = {"isDraft": False, "headRefOid": "c" * 40, "headRefName": "main",
                   "headRepositoryOwner": {"login": "another-owner"}, "isCrossRepository": True,
                   "state": "CLOSED"}
        for field, value in changes.items():
            changed = draft_pr()
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(SystemExit):
                self.check_prs([changed])
        for field in draft_pr().keys() - {"number"}:
            changed = draft_pr()
            del changed[field]
            with self.subTest(missing=field), self.assertRaises(SystemExit):
                self.check_prs([changed])

    def test_signing_secrets_are_step_scoped_and_not_persisted(self):
        signing = step("Build release AAB using only the existing ICARUS upload signer")
        for name in ("ICARUS_KEYSTORE_BASE64", "ICARUS_SIGNING_PASSWORD_BASE64", "ICARUS_KEY_ALIAS"):
            self.assertEqual(WORKFLOW.count("secrets." + name), 1)
            self.assertIn("secrets." + name, signing)
        self.assertNotIn("GITHUB_ENV", WORKFLOW)
        self.assertNotIn("GITHUB_OUTPUT", WORKFLOW)
        self.assertNotIn("keytool", WORKFLOW)
        self.assertNotIn("build-apks", WORKFLOW)
        self.assertNotIn(":app:assemble", WORKFLOW)
        self.assertNotIn("if:", signing)
        self.assertIn("finally:", signing)
        self.assertIn("signing_file.unlink(missing_ok=True)", signing)

    def run_signing(self, temporary, change=None, failure=None):
        # Fixture bytes only. This test never reads repository or environment secrets.
        environment = {"RUNNER_TEMP": temporary, "KEYSTORE_B64": base64.b64encode(b"fixture-keystore").decode(),
                       "SIGNING_PASSWORD_B64": base64.b64encode(b"fixture%password").decode(),
                       "KEY_ALIAS": "fixture-alias", "ICARUS_WEB_URL": "https://icarusassistant.com"}
        environment.update(change or {})
        output = io.StringIO()

        def fake_gradle(command, *, cwd, env, check):
            self.assertEqual(command, ["gradle", "--no-daemon", ":app:bundleRelease",
                                       "-PICARUS_WEB_URL=https://icarusassistant.com", "--stacktrace"])
            self.assertEqual(cwd, "native/android")
            self.assertIs(check, True)
            signing_file = Path(env["ICARUS_KEYSTORE_FILE"])
            self.assertEqual(signing_file.read_bytes(), b"fixture-keystore")
            self.assertEqual(stat.S_IMODE(signing_file.stat().st_mode), 0o600)
            self.assertEqual(env["ICARUS_KEYSTORE_PASSWORD"], "fixture%password")
            self.assertEqual(env["ICARUS_KEY_PASSWORD"], "fixture%password")
            self.assertEqual(env["ICARUS_KEY_ALIAS"], "fixture-alias")
            for name in ("KEYSTORE_B64", "SIGNING_PASSWORD_B64", "KEY_ALIAS"):
                self.assertNotIn(name, env)
            self.assertEqual(output.getvalue(), "::add-mask::fixture%25password\n")
            if failure:
                raise failure

        with patch.dict(os.environ, environment, clear=True), contextlib.redirect_stdout(output), \
                patch("subprocess.run", side_effect=fake_gradle) as gradle:
            exec(compile(python_script("PYSIGN"), "candidate-signing", "exec"), {})
            return gradle.call_count

    def test_signer_is_masked_restricted_and_cleaned_on_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            self.assertEqual(self.run_signing(temporary), 1)
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_signer_is_cleaned_after_gradle_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_signing(temporary, failure=subprocess.CalledProcessError(1, "gradle"))
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_missing_or_invalid_signer_input_fails_closed(self):
        invalid = [{name: ""} for name in ("KEYSTORE_B64", "SIGNING_PASSWORD_B64", "KEY_ALIAS")]
        invalid.extend({"SIGNING_PASSWORD_B64": base64.b64encode(value).decode()}
                       for value in (b"", b"one\ntwo", b"one\rtwo", b"one\0two"))
        for change in invalid:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(SystemExit):
                    self.run_signing(temporary, change=change)
                self.assertEqual(list(Path(temporary).iterdir()), [])
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                self.run_signing(temporary, change={"KEYSTORE_B64": "not-valid-base64!"})
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_verified_delivery_is_exactly_four_files_and_uploaded_only_on_success(self):
        verify = step("Verify signed AAB, app identity, offline assets, and native alignment")
        for argument in ("--aab", "--bundletool", "--source-sha", "--expected-certificate", "--output", "--check-delivery"):
            self.assertIn(argument, verify)
        self.assertIn('sha256sum ICARUS-play-candidate.aab > SHA256SUMS', verify)
        self.assertIn('printf \'%s\\n\' "$GITHUB_SHA"', verify)
        upload = step("Save verified AAB and verification report as a CI artifact only")
        self.assertIn("uses: actions/upload-artifact@v4", upload)
        self.assertIn("name: ICARUS-play-candidate-${{ github.sha }}", upload)
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("retention-days: 14", upload)
        self.assertNotIn("if:", upload)
        self.assertNotIn("*", upload)
        files = re.findall(r"^            \$\{\{ runner.temp \}\}/play-candidate-delivery/(.+)$", upload, re.MULTILINE)
        self.assertEqual(files, ["ICARUS-play-candidate.aab", "verification.json", "SHA256SUMS", "SOURCE_COMMIT.txt"])
        self.assertLess(WORKFLOW.index("id: signing"), WORKFLOW.index(verify))
        self.assertLess(WORKFLOW.index(verify), WORKFLOW.index(upload))

    def test_reports_and_cleanup_run_after_failure_without_uploading_signer(self):
        reports = step("Save native validation reports")
        self.assertIn("if: always()", reports)
        self.assertNotIn("runner.temp", reports)
        self.assertNotIn(".jks", reports)
        cleanup = step("Always remove signing material and temporary bundles")
        self.assertIn("if: always()", cleanup)
        for path in ("icarus-play-candidate-signing.jks", "play-candidate-pr.json", "play-candidate-delivery",
                     "play-candidate-tools", "native/android/app/build/outputs/bundle/release"):
            self.assertIn(path, cleanup)
        self.assertTrue(WORKFLOW.rstrip().endswith(cleanup.rstrip()))

    def test_embedded_scripts_parse_without_executing_external_commands(self):
        for delimiter in ("PYPR", "PYSIGN"):
            compile(python_script(delimiter), delimiter, "exec")
        scripts = re.findall(r"^        run: \|\n((?:          .*\n|\n)+)", WORKFLOW, re.MULTILINE)
        self.assertGreaterEqual(len(scripts), 6)
        for script in scripts:
            subprocess.run(["bash", "-n"], input=textwrap.dedent(script), text=True, check=True,
                           capture_output=True)


if __name__ == "__main__":
    unittest.main()
