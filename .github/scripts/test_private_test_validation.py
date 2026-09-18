"""Regression checks for private-delivery isolation; no SDK or credentials required."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("private_validation", Path(__file__).with_name("validate-private-test.py"))
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)


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
