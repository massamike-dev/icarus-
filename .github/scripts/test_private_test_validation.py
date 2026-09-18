"""Regression checks for private-delivery isolation; no SDK or credentials required."""

import importlib.util
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("private_validation", Path(__file__).with_name("validate-private-test.py"))
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)
upload_spec = importlib.util.spec_from_file_location("private_upload", Path(__file__).with_name("upload-private-apk.py"))
uploader = importlib.util.module_from_spec(upload_spec)
upload_spec.loader.exec_module(uploader)


class PrivateDeliveryTests(unittest.TestCase):
    def test_only_real_separate_https_origins(self):
        self.assertEqual(validation.validated_origin("https://icarus-private-example.onrender.com/"), "https://icarus-private-example.onrender.com")
        invalid = ["", None, "https://icarusassistant.com", "https://x.icarusassistant.com",
                   "https://icarus-assistant.onrender.com", "https://icarus-test.invalid",
                   "http://test.onrender.com", "https://user:pw@test.onrender.com",
                   "https://test.onrender.com?token=x", "https://test.onrender.com/path",
                   "https://127.0.0.1", "https://test.onrender.com\nready=true"]
        for target in invalid:
            with self.subTest(target=target), self.assertRaises(ValueError):
                validation.validated_origin(target)

    def test_missing_config_is_validation_only(self):
        with tempfile.TemporaryDirectory() as temp:
            env = Path(temp) / "env"
            output = Path(temp) / "output"
            with patch.dict(os.environ, {"GITHUB_ENV": str(env), "GITHUB_OUTPUT": str(output)}):
                validation.prepare_workflow(Path(temp) / "absent.json")
            self.assertEqual(output.read_text(), "ready=false\n")
            self.assertEqual(env.read_text(), "ICARUS_TEST_WEB_URL=https://icarus-test.invalid\n")

    def test_bad_config_does_not_enable_delivery(self):
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / "private.json"
            config.write_text(json.dumps({"webUrl": "https://icarusassistant.com"}))
            with self.assertRaises(ValueError):
                validation.prepare_workflow(config)

    def test_health_cannot_redirect(self):
        with self.assertRaises(ValueError):
            validation.NoRedirects().redirect_request(None, None, 302, "", {}, "https://icarusassistant.com")

    def test_health_requires_the_exact_deployed_commit(self):
        commit = "a" * 40
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / "private.json"
            config.write_text(json.dumps({"webUrl": "https://private.onrender.com"}))
            with self.assertRaises(ValueError):
                validation.load_config(config, True)
            for health in ({"privateTest": True, "commitSha": "b" * 40}, {"privateTest": True}, {"privateTest": False, "commitSha": commit}):
                with self.subTest(health=health), patch.object(validation.urllib.request, "build_opener") as opener, patch.object(validation.time, "sleep") as sleep:
                    opener.return_value.open.return_value = io.BytesIO(json.dumps(health).encode())
                    with self.assertRaises(ValueError):
                        validation.load_config(config, True, commit)
                    self.assertEqual(opener.return_value.open.call_count, 1)
                    sleep.assert_not_called()
            with patch.object(validation.urllib.request, "build_opener") as opener:
                opener.return_value.open.return_value = io.BytesIO(json.dumps({"privateTest": True, "commitSha": commit}).encode())
                self.assertEqual(validation.load_config(config, True, commit), "https://private.onrender.com")

    def test_health_retries_only_transient_cold_start_failures(self):
        commit = "a" * 40
        transient = [TimeoutError(), urllib.error.URLError("temporary transport failure"),
                     urllib.error.HTTPError("https://private.onrender.com/api/health", 503, "starting", {}, None),
                     urllib.error.HTTPError("https://private.onrender.com/api/health", 429, "busy", {}, None)]
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / "private.json"
            config.write_text(json.dumps({"webUrl": "https://private.onrender.com"}))
            for error in transient:
                with self.subTest(error=error), patch.object(validation.urllib.request, "build_opener") as opener, patch.object(validation.time, "sleep") as sleep:
                    response = io.BytesIO(json.dumps({"privateTest": True, "commitSha": commit}).encode())
                    opener.return_value.open.side_effect = [error, response]
                    self.assertEqual(validation.load_config(config, True, commit), "https://private.onrender.com")
                    self.assertEqual(opener.return_value.open.call_count, 2)
                    sleep.assert_called_once_with(5)
            with patch.object(validation.urllib.request, "build_opener") as opener, patch.object(validation.time, "sleep") as sleep:
                opener.return_value.open.side_effect = TimeoutError()
                with self.assertRaises(TimeoutError):
                    validation.load_config(config, True, commit)
                self.assertEqual(opener.return_value.open.call_count, 4)
                self.assertEqual(sleep.call_count, 3)
            for error in (ValueError("redirect rejected"), urllib.error.HTTPError("https://private.onrender.com/api/health", 404, "missing", {}, None)):
                with self.subTest(error=error), patch.object(validation.urllib.request, "build_opener") as opener, patch.object(validation.time, "sleep") as sleep:
                    opener.return_value.open.side_effect = error
                    with self.assertRaises(type(error)):
                        validation.load_config(config, True, commit)
                    self.assertEqual(opener.return_value.open.call_count, 1)
                    sleep.assert_not_called()

    def test_oversized_apk_is_rejected_before_reading_file_or_requesting_identity(self):
        with patch.object(Path, "stat", return_value=SimpleNamespace(st_size=uploader.MAX_APK_BYTES + 1)), patch.object(Path, "open") as source, patch.object(uploader, "read_response") as network:
            with self.assertRaises(ValueError):
                uploader.upload(Path("large.apk"), "https://private.onrender.com", "a" * 40)
            source.assert_not_called()
            network.assert_not_called()

    def test_oidc_requests_use_the_expected_github_provider_and_audience(self):
        url = uploader.oidc_url("https://example.actions.githubusercontent.com/idtoken?api-version=2.0&audience=old")
        self.assertEqual(parse_qs(urlsplit(url).query)["audience"], [uploader.AUDIENCE])
        for endpoint in ("https://attacker.example/token", "http://example.actions.githubusercontent.com/token", "https://user:secret@example.actions.githubusercontent.com/token"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                uploader.oidc_url(endpoint)

    def test_upload_streams_only_to_test_service_and_verifies_receipt(self):
        commit, content = "a" * 40, b"fixture APK contents"
        checksum = hashlib.sha256(content).hexdigest()
        for valid_receipt in (True, False):
            requests = []
            class Opener:
                def open(self, request, timeout):
                    requests.append(request)
                    if len(requests) == 1:
                        return io.BytesIO(json.dumps({"value": "synthetic.oidc.token"}).encode())
                    self_test.assertEqual(request.full_url, "https://private.onrender.com/api/private-apk")
                    self_test.assertEqual(request.get_header("Authorization"), "Bearer synthetic.oidc.token")
                    self_test.assertEqual(request.get_header("X-icarus-apk-sha256"), checksum)
                    self_test.assertEqual(request.get_header("Content-length"), str(len(content)))
                    self_test.assertEqual(request.data.read(), content)
                    return io.BytesIO(json.dumps({"sha256": checksum, "size": len(content), "commit": commit if valid_receipt else "b" * 40}).encode())
            self_test = self
            with tempfile.TemporaryDirectory() as temp:
                apk = Path(temp) / "test.apk"
                apk.write_bytes(content)
                with patch.dict(os.environ, {"ACTIONS_ID_TOKEN_REQUEST_URL": "https://example.actions.githubusercontent.com/idtoken?api-version=2.0", "ACTIONS_ID_TOKEN_REQUEST_TOKEN": "synthetic-request-token"}):
                    if valid_receipt:
                        self.assertEqual(uploader.upload(apk, "https://private.onrender.com", commit, Opener()), {"sha256": checksum, "size": len(content), "commit": commit})
                    else:
                        with self.assertRaises(ValueError):
                            uploader.upload(apk, "https://private.onrender.com", commit, Opener())
            self.assertEqual(requests[0].get_header("Authorization"), "Bearer synthetic-request-token")
            self.assertEqual(len(requests), 2)

    def test_actual_apk_values_must_all_match_private_configuration(self):
        fields = "\n".join([
            '.field public static final ICARUS_WEB_URL:Ljava/lang/String; = "https://private.onrender.com"',
            '.field public static final UPDATE_NOTES_URL:Ljava/lang/String; = ""',
            '.field public static final ICARUS_LINK_SCHEME:Ljava/lang/String; = "icarus-test"',
            '.field public static final PRIVATE_TEST:Z = true',
        ])
        for change in ("package", "debug", "url", "update", "flag", "scheme"):
            package, debug, code = "com.icarusalmighty.app.test", "false", fields
            if change == "package":
                package = "com.icarusalmighty.app"
            if change == "debug":
                debug = "true"
            if change == "url":
                code = code.replace("https://private.onrender.com", "https://icarusassistant.com")
            if change == "update":
                code = code.replace('UPDATE_NOTES_URL:Ljava/lang/String; = ""', 'UPDATE_NOTES_URL:Ljava/lang/String; = "https://public.example/feed"')
            if change == "flag":
                code = code.replace("PRIVATE_TEST:Z = true", "PRIVATE_TEST:Z = false")
            if change == "scheme":
                code = code.replace('= "icarus-test"', '= "icarus"')
            with self.subTest(change=change), patch.object(validation.subprocess, "check_output", side_effect=[package, debug, code]), self.assertRaises(ValueError):
                validation.validate_apk("app.apk", "apkanalyzer", "https://private.onrender.com")
        with patch.object(validation.subprocess, "check_output", side_effect=["com.icarusalmighty.app.test", "false", fields]):
            validation.validate_apk("app.apk", "apkanalyzer", "https://private.onrender.com")


if __name__ == "__main__":
    unittest.main()
