#!/usr/bin/env python3
"""Stream a verified APK to the restricted test service using short-lived GitHub OIDC."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

spec = importlib.util.spec_from_file_location("private_validation", Path(__file__).with_name("validate-private-test.py"))
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)

AUDIENCE = "icarus-private-apk:massamike-dev/icarus-"
MAX_APK_BYTES = 256 * 1024 * 1024


def oidc_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname
            or not parsed.hostname.endswith(".actions.githubusercontent.com")
            or parsed.username or parsed.password or parsed.port not in (None, 443) or parsed.fragment):
        raise ValueError("GitHub did not provide a valid Actions OIDC request endpoint.")
    query = [(key, value) for key, value in parse_qsl(parsed.query, keep_blank_values=True) if key != "audience"]
    query.append(("audience", AUDIENCE))
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), ""))


def read_response(opener, request, phase, timeout=30):
    try:
        with opener.open(request, timeout=timeout) as response:
            payload = response.read(16_385)
        if len(payload) > 16_384:
            raise ValueError("Response exceeded its metadata limit.")
        return json.loads(payload)
    except urllib.error.HTTPError as error:
        # Do not echo server bodies, tokens, or request headers into public CI logs.
        raise ValueError(f"{phase} rejected the request (HTTP {error.code}).") from None
    except (urllib.error.URLError, json.JSONDecodeError) as error:
        raise ValueError(f"{phase} did not return valid metadata.") from None


def upload(apk, origin, commit, opener=None):
    origin = validation.validated_origin(origin)
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Private APK delivery requires an exact commit SHA.")
    size = apk.stat().st_size
    if size <= 0:
        raise ValueError("Private APK is empty.")
    if size > MAX_APK_BYTES:
        raise ValueError("Private APK exceeds the private service's 256 MiB upload limit.")
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    checksum = digest.hexdigest()
    opener = opener or urllib.request.build_opener(validation.NoRedirects())
    request_token = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN")
    if not request_token:
        raise ValueError("This GitHub job needs id-token: write permission to request delivery identity.")
    token_request = urllib.request.Request(
        oidc_url(os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL", "")),
        headers={"Authorization": f"Bearer {request_token}", "Accept": "application/json"},
    )
    identity = read_response(opener, token_request, "GitHub OIDC")
    if not isinstance(identity, dict):
        raise ValueError("GitHub OIDC did not return token metadata.")
    token = identity.get("value")
    if not isinstance(token, str) or not token or any(c.isspace() for c in token):
        raise ValueError("GitHub OIDC did not return a token.")
    # Token remains only in this process and the TLS request. No files or step outputs.
    with apk.open("rb") as source:
        request = urllib.request.Request(
            f"{origin}/api/private-apk",
            data=source,
            method="POST",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Length": str(size),
                "X-ICARUS-APK-SHA256": checksum,
                "Accept": "application/json",
            },
        )
        metadata = read_response(opener, request, "Private APK upload", timeout=240)
    if not isinstance(metadata, dict) or metadata.get("sha256") != checksum or metadata.get("size") != size or metadata.get("commit") != commit:
        raise ValueError("Private service receipt does not match the uploaded APK and commit.")
    return {"sha256": checksum, "size": size, "commit": commit}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="config/private-test.json")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    # Repeat deployment verification immediately before requesting short-lived identity.
    origin = validation.load_config(args.config, True, args.commit)
    metadata = upload(args.apk, origin, args.commit)
    print(f"Private APK stored for the approved tester: SHA-256 {metadata['sha256']}; {metadata['size']} bytes; commit {metadata['commit']}.")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as output:
            output.write("Signed ICARUS Test was stored in the restricted test service for the approved tester. No public release, Actions artifact, or updater was changed.\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        # Error messages above are intentionally bounded and never contain tokens.
        raise SystemExit(str(error)) from None
