"""Preview upgrade verification regressions, without Android SDK or signing secrets."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location("preview_verification", Path(__file__).with_name("verify-xreal-preview.py"))
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
CERT = "a" * 64
COMMIT = "b" * 40


def metadata(preview=False):
    return {
        "package": verification.PACKAGE,
        "versionCode": verification.VERSION_CODE if preview else verification.BASELINE_VERSION_CODE,
        "versionName": verification.VERSION_NAME if preview else verification.BASELINE_VERSION_NAME,
        "debuggable": "false",
        "permissions": ["android.permission.INTERNET", "android.permission.RECORD_AUDIO"],
        "signerCertificateSha256": [CERT],
    }


def write_apk(path, index=None, include_script=True, extra=None):
    with zipfile.ZipFile(path, "w") as archive:
        if index is None:
            index = '<!doctype html><html><script type="module" src="./assets/index-test.js"></script></html>'
        archive.writestr("assets/web/index.html", index)
        if include_script:
            archive.writestr("assets/web/assets/index-test.js", "console.log('bundled');")
        if extra:
            archive.writestr(extra, "unexpected")


class PreviewVerificationTests(unittest.TestCase):
    def test_pinned_baseline_checksum_is_full_sha256(self):
        self.assertRegex(verification.BASELINE_SHA256, r"^[a-f0-9]{64}$")

    def test_upgrade_keeps_identity_without_added_permissions(self):
        verification.verify_upgrade(metadata(), metadata(True))
        fewer_permissions = metadata(True)
        fewer_permissions["permissions"].pop()
        verification.verify_upgrade(metadata(), fewer_permissions)

    def test_upgrade_rejects_identity_version_signing_and_permission_changes(self):
        changes = {
            "package": "com.icarusalmighty.app.test",
            "versionCode": 46,
            "versionName": "1.6.15",
            "debuggable": "true",
            "signerCertificateSha256": ["c" * 64],
            "permissions": ["android.permission.INTERNET", "android.permission.READ_SMS"],
        }
        for field, value in changes.items():
            preview = metadata(True)
            preview[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                verification.verify_upgrade(metadata(), preview)
        for field, value in {"package": "wrong", "versionCode": 45, "versionName": "1.6.14", "debuggable": "true"}.items():
            baseline = metadata()
            baseline[field] = value
            with self.subTest(baseline=field), self.assertRaises(ValueError):
                verification.verify_upgrade(baseline, metadata(True))

    def test_certificate_parser_requires_unambiguous_sha256(self):
        output = "Verifies\nSigner #1 certificate SHA-256 digest: " + CERT.upper() + "\n"
        self.assertEqual(verification.certificates(output), [CERT])
        for output in ("", "Signer #1 certificate SHA-1 digest: " + CERT, output + output):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verification.certificates(output)

    def test_permissions_fail_closed(self):
        self.assertEqual(verification.permissions("android.permission.INTERNET\nandroid.permission.CAMERA"),
                         ["android.permission.CAMERA", "android.permission.INTERNET"])
        for output in ("", "Error: unavailable", "android.permission.INTERNET\n\n", "INTERNET"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verification.permissions(output)

    def test_signature_tool_failure_is_not_ignored(self):
        with patch.object(verification.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "apksigner")), self.assertRaises(subprocess.CalledProcessError):
            verification.inspect_apk("preview.apk", "apksigner", "apkanalyzer")

    def test_bundle_requires_packaged_relative_application_script(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "preview.apk"
            write_apk(apk)
            verification.verify_bundle(apk)
            invalid = [
                {"index": "<html>empty</html>"},
                {"include_script": False},
                {"index": '<html><script src="https://icarusassistant.com/app.js"></script></html>'},
                {"index": '<html><script src="/assets/index-test.js"></script></html>'},
                {"index": '<html><script src="./assets/../secret.js"></script></html>'},
                {"extra": "assets/upload.jks"},
            ]
            for settings in invalid:
                write_apk(apk, **settings)
                with self.subTest(settings=settings), self.assertRaises(ValueError):
                    verification.verify_bundle(apk)

    def test_baseline_checksum_rejected_before_external_inspection(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline = Path(temp) / "baseline.apk"
            baseline.write_bytes(b"wrong")
            with patch.object(verification, "inspect_apk") as inspect, self.assertRaises(ValueError):
                verification.verify("preview.apk", baseline, "apksigner", "apkanalyzer", COMMIT, "output.json")
            inspect.assert_not_called()

    def test_delivery_is_bound_to_exact_source_and_apk_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            apk = folder / verification.APK_NAME
            write_apk(apk)
            baseline = folder / "baseline.apk"
            write_apk(baseline)
            with patch.object(verification, "BASELINE_SHA256", verification.sha256(baseline)), patch.object(verification, "inspect_apk", side_effect=[metadata(), metadata(True)]):
                result = verification.verify(apk, baseline, "apksigner", "apkanalyzer", COMMIT, folder / "verification.json")
                baseline.unlink()
                (folder / "SOURCE_COMMIT.txt").write_text(COMMIT + "\n")
                (folder / "SHA256SUMS").write_text(result["apkSha256"] + "  " + verification.APK_NAME + "\n")
                verification.verify_delivery(folder, COMMIT)
                with self.assertRaises(ValueError):
                    verification.verify_delivery(folder, "c" * 40)
                for field, value in (("sourceCommit", "c" * 40), ("apkSha256", "d" * 64), ("checks", {})):
                    changed = copy.deepcopy(result)
                    changed[field] = value
                    (folder / "verification.json").write_text(json.dumps(changed))
                    with self.subTest(field=field), self.assertRaises(ValueError):
                        verification.verify_delivery(folder, COMMIT)
                (folder / "verification.json").write_text(json.dumps(result))
                (folder / "extra-file").write_text("unapproved")
                with self.assertRaises(ValueError):
                    verification.verify_delivery(folder, COMMIT)
                (folder / "extra-file").unlink()
                apk.write_bytes(b"tampered")
                with self.assertRaises(ValueError):
                    verification.verify_delivery(folder, COMMIT)

    def test_workflow_has_only_isolated_publication_boundaries(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/android-xreal-preview.yml").read_text()
        self.assertIn("branches: [work/icarus-xreal-preview-20261014]", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("--latest=false", workflow)
        self.assertIn('pr.get("isDraft") is True', workflow)
        self.assertIn('pr.get("headRefOid") == os.environ["GITHUB_SHA"]', workflow)
        self.assertNotIn("git push", workflow)
        self.assertNotIn("--clobber", workflow)
        self.assertNotIn(":app:bundleRelease", workflow)
        self.assertNotIn("android-update-v2.json", workflow)
        self.assertEqual(workflow.count("contents: write"), 1)
        self.assertLess(workflow.index("id: native_validation"), workflow.index("id: signing"))


if __name__ == "__main__":
    unittest.main()
