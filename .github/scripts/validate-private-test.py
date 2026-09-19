#!/usr/bin/env python3
"""Fail closed before signing or distributing an isolated ICARUS test APK."""

import argparse
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit


PRODUCTION_HOSTS = {"icarusassistant.com", "www.icarusassistant.com", "icarus-assistant.onrender.com"}
ANDROID_APP_GRADLE = Path(__file__).resolve().parents[2] / "native/android/app/build.gradle.kts"


def validated_origin(value):
    if not isinstance(value, str) or value != value.strip() or any(ord(c) < 33 for c in value):
        raise ValueError("Private test webUrl must be a nonempty HTTPS origin.")
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.port not in (None, 443) or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("Private test webUrl must be an HTTPS origin without credentials, path, or query.")
    hostname = parsed.hostname.lower()
    if (hostname in PRODUCTION_HOSTS or hostname.endswith(".icarusassistant.com")
            or hostname.endswith(".base44.app") or hostname.endswith(".base44.com")
            or hostname in {"localhost", "example.com", "example.org", "example.net"}
            or hostname.endswith((".localhost", ".invalid", ".example", ".test", ".local"))
            or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+", hostname)
            or re.fullmatch(r"[\d.]+", hostname)):
        raise ValueError("Private test webUrl must use a real, separate test hostname.")
    return f"https://{hostname}"


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise ValueError("Private test health checks must not redirect to another service.")


def load_config(path, check_health, expected_commit=None):
    config = json.loads(Path(path).read_text())
    origin = validated_origin(config.get("webUrl"))
    if check_health:
        if not isinstance(expected_commit, str) or not re.fullmatch(r"[a-f0-9]{40}", expected_commit):
            raise ValueError("An exact commit SHA is required to verify the test deployment.")
        request = urllib.request.Request(f"{origin}/api/health", headers={"Accept": "application/json"})
        opener = urllib.request.build_opener(NoRedirects())
        for attempt in range(4):
            try:
                with opener.open(request, timeout=30) as response:
                    health = json.load(response)
                break
            except urllib.error.HTTPError as error:
                if attempt == 3 or (error.code != 429 and not 500 <= error.code < 600):
                    raise
            except (TimeoutError, urllib.error.URLError) as error:
                if attempt == 3 or isinstance(getattr(error, "reason", None), ssl.SSLCertVerificationError):
                    raise
            # A free Render instance may need time to wake; only transient transport
            # errors are retried. Redirect, identity, and commit failures fail closed.
            time.sleep(5)
        if not isinstance(health, dict) or health.get("privateTest") is not True:
            raise ValueError("The endpoint did not identify itself as the restricted private test service.")
        if health.get("commitSha") != expected_commit:
            raise ValueError("The private service is running a different commit. Manually deploy this exact branch commit, then rerun delivery.")
    return origin


def smali_string(code, name):
    match = re.search(r"^\.field\s+[^\n]*\b" + re.escape(name) + r":Ljava/lang/String;\s*=\s*(\"(?:[^\"\\]|\\.)*\")\s*$", code, re.MULTILINE)
    if not match:
        raise ValueError(f"Cannot verify compiled BuildConfig.{name}.")
    return json.loads(match.group(1))


def expected_apk_version():
    # Read the exact native app source used by the private workflow, never a root
    # compatibility project, environment fallback, or hardcoded release number.
    source = ANDROID_APP_GRADLE.read_text()
    source = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/',
                    lambda token: token.group() if token.group().startswith('"') else "\n" * token.group().count("\n"), source)

    def literal(name, pattern):
        assignments = re.findall(r"^\s*" + name + r"\s*=\s*([^\n]*)$", source, re.MULTILINE)
        if len(assignments) != 1 or not re.fullmatch(pattern, assignments[0].strip()):
            raise ValueError(f"Cannot unambiguously verify private APK {name} from native Gradle configuration.")
        return assignments[0].strip()

    version_code = literal("versionCode", r"[1-9][0-9]*")
    version_name = json.loads(literal("versionName", r'"[A-Za-z0-9._+\-]+"'))
    suffix_literal = literal("versionNameSuffix", r'"[A-Za-z0-9._+\-]+"')
    # Fail closed if suffix configuration becomes dynamic or moves to another
    # build type. This deliberately supports only the repository's literal DSL.
    private_blocks = re.findall(r'\bcreate\("privateTest"\)\s*\{([^{}]*)\}', source)
    if len(private_blocks) != 1 or not re.search(
            r"^\s*versionNameSuffix\s*=\s*" + re.escape(suffix_literal) + r"\s*$",
            private_blocks[0], re.MULTILINE):
        raise ValueError("Cannot verify the versionNameSuffix belongs to the privateTest build type.")
    return version_code, version_name + json.loads(suffix_literal)


def validate_apk(apk, analyzer, origin):
    def inspect(*args):
        return subprocess.check_output([analyzer, *args, str(apk)], text=True).strip()

    if inspect("manifest", "application-id") != "com.icarusalmighty.app.test":
        raise ValueError("Refusing to deliver an APK that can replace the public ICARUS app.")
    if inspect("manifest", "debuggable").lower() != "false":
        raise ValueError("Refusing to deliver a debuggable APK.")
    version_code, version_name = expected_apk_version()
    if inspect("manifest", "version-code") != version_code:
        raise ValueError("APK versionCode does not match the native Gradle build; refusing to deliver the wrong version.")
    if inspect("manifest", "version-name") != version_name:
        raise ValueError("APK versionName does not match the private Gradle build; refusing to deliver the wrong version.")
    code = inspect("dex", "code", "--class", "com.icarusalmighty.app.BuildConfig")
    if smali_string(code, "ICARUS_WEB_URL") != origin:
        raise ValueError("Compiled APK endpoint does not match the verified private test service.")
    if smali_string(code, "UPDATE_NOTES_URL"):
        raise ValueError("Private test APK still has an update-manifest endpoint.")
    if smali_string(code, "ICARUS_LINK_SCHEME") != "icarus-test":
        raise ValueError("Private test APK is not isolated from public ICARUS links.")
    if not re.search(r"^\.field\s+[^\n]*\bPRIVATE_TEST:Z\s*=\s*(?:true|0x1|1)\s*$", code, re.MULTILINE):
        raise ValueError("Compiled APK is missing the private-test runtime flag.")


def prepare_workflow(path):
    config_file = Path(path)
    config = json.loads(config_file.read_text()) if config_file.exists() else {}
    ready = bool(config.get("webUrl"))
    # Reserved origin is used ONLY for compilation/lint/unit tests, never assembly.
    origin = validated_origin(config["webUrl"]) if ready else "https://icarus-test.invalid"
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"ready={str(ready).lower()}\n")
    with open(os.environ["GITHUB_ENV"], "a") as output:
        output.write(f"ICARUS_TEST_WEB_URL={origin}\n")
    if not ready:
        print("::notice::Private APK delivery is disabled: configure the restricted test service URL first. Only unit tests and lint will run.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="config/private-test.json")
    parser.add_argument("--check-health", action="store_true")
    parser.add_argument("--expected-commit")
    parser.add_argument("--prepare-workflow", action="store_true")
    parser.add_argument("--github-env", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apkanalyzer")
    args = parser.parse_args()
    if args.prepare_workflow:
        prepare_workflow(args.config)
        return
    origin = load_config(args.config, args.check_health, args.expected_commit)
    if args.apk:
        if not args.apkanalyzer:
            parser.error("--apkanalyzer is required with --apk")
        validate_apk(args.apk, args.apkanalyzer, origin)
    if args.github_env:
        with open(os.environ["GITHUB_ENV"], "a") as output:
            output.write(f"ICARUS_TEST_WEB_URL={origin}\n")
    print("Private test endpoint and requested package checks passed.")


if __name__ == "__main__":
    main()
