#!/usr/bin/env python3
"""Fail-closed upgrade checks for the separately published ICARUS XREAL preview."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET


PACKAGE = "com.icarusalmighty.app"
VERSION_CODE = 49
VERSION_NAME = "1.6.18"
BASELINE_VERSION_CODE = 46
BASELINE_VERSION_NAME = "1.6.15"
BASELINE_TAG = "v1.6.15-test"
BASELINE_SHA256 = "215fe3d5b3b6e01fc2db2dba93d3c0f3fbd4d7eda9e3eed836a740c62a50f913"
APK_NAME = "ICARUS-xreal-preview.apk"
SHA_RE = re.compile(r"[a-f0-9]{64}")
COMMIT_RE = re.compile(r"[a-f0-9]{40}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def run_tool(command):
    # These commands inspect public APK metadata only. Do not use this helper
    # for signing or invoke commands with passwords on their command lines.
    result = subprocess.run(command, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def certificates(output):
    # This machine-readable line is produced by our tiny helper using Android's
    # ApkVerifier API. CLI labels vary between build-tools versions.
    values = output.splitlines()
    require(len(values) == 1, "Expected exactly one verified APK signer fingerprint.")
    match = re.fullmatch(r"CERTIFICATE_SHA256=([0-9a-f]{64})", values[0])
    require(match is not None, "APK verifier returned an invalid certificate SHA-256 fingerprint.")
    return [match.group(1)]


def signer_fingerprints(path, apksigner):
    jar = Path(apksigner).resolve().parent / "lib" / "apksigner.jar"
    require(jar.is_file(), "Android apksigner API jar is unavailable.")
    helper = Path(__file__).with_name("ApkCertificateInfo.java")
    require(helper.is_file(), "APK certificate helper is unavailable.")
    return certificates(run_tool(["java", "--class-path", str(jar), str(helper), str(path)]))


def permissions(output):
    # Parse the documented XML output, not the SDK's presentation-oriented list
    # (which can include maxSdkVersion annotations and formatting changes).
    require("<!DOCTYPE" not in output.upper() and "<!ENTITY" not in output.upper(),
            "Unexpected declaration in APK manifest XML.")
    try:
        manifest = ET.fromstring(output)
    except ET.ParseError as error:
        raise ValueError("APK manifest XML is malformed.") from error
    require(manifest.tag == "manifest", "APK manifest XML has an unexpected root.")
    values = []
    android_name = "{http://schemas.android.com/apk/res/android}name"
    for element in manifest:
        if element.tag not in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            continue
        name = element.get(android_name, "")
        require(bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", name)),
                "Missing or invalid permission name in APK manifest.")
        values.append(name)
    require(bool(values), "APK permission list is empty or unavailable.")
    return sorted(set(values))


def inspect_apk(path, apksigner, apkanalyzer):
    run_tool([apksigner, "verify", str(path)])
    def manifest(field):
        return run_tool([apkanalyzer, "manifest", field, str(path)])
    code = manifest("version-code")
    require(bool(re.fullmatch(r"[0-9]+", code)), "Invalid APK version code.")
    return {
        "package": manifest("application-id"),
        "versionCode": int(code),
        "versionName": manifest("version-name"),
        "debuggable": manifest("debuggable"),
        "permissions": permissions(manifest("print")),
        "signerCertificateSha256": signer_fingerprints(path, apksigner),
    }


def verify_upgrade(baseline, preview):
    require(baseline["package"] == PACKAGE and preview["package"] == PACKAGE,
            "Both APKs must use the existing ICARUS application ID.")
    require(baseline["versionCode"] == BASELINE_VERSION_CODE and baseline["versionName"] == BASELINE_VERSION_NAME,
            "Unexpected baseline APK version.")
    require(preview["versionCode"] == VERSION_CODE and preview["versionName"] == VERSION_NAME,
            "Unexpected preview APK version.")
    require(preview["versionCode"] > baseline["versionCode"], "Preview is not a higher-version upgrade.")
    require(preview["debuggable"] == "false" and baseline["debuggable"] == "false",
            "Only non-debuggable release APKs can be used for upgrade verification.")
    require(preview["signerCertificateSha256"] == baseline["signerCertificateSha256"],
            "Preview signer certificate does not match the published ICARUS APK.")
    additions = sorted(set(preview["permissions"]) - set(baseline["permissions"]))
    require(not additions, "Preview adds Android permissions: " + ", ".join(additions))


def verify_bundle(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "APK contains ambiguous duplicate ZIP entries.")
        require("assets/web/index.html" in names, "Bundled offline web index is missing.")
        index = archive.read("assets/web/index.html").decode("utf-8")
        require("<html" in index.lower() and "<script" in index.lower(), "Bundled web index is incomplete.")
        scripts = re.findall(r'<script\b[^>]*\bsrc=["\']([^"\']+)["\']', index, re.IGNORECASE)
        require(bool(scripts), "Bundled web index has no application script.")
        for script in scripts:
            require(script.startswith("./assets/") and ".." not in script[2:].split("/"),
                    "Bundled application script must use a relative packaged asset path.")
            require("assets/web/" + script[2:] in names, "Bundled application script is missing from the APK.")
        require(not any(name.lower().endswith((".jks", ".keystore")) for name in names),
                "APK unexpectedly contains a signing keystore.")


def verify(apk, baseline_apk, apksigner, apkanalyzer, source_sha, output):
    require(bool(COMMIT_RE.fullmatch(source_sha)), "Expected an exact source commit SHA.")
    require(sha256(baseline_apk) == BASELINE_SHA256, "Published baseline APK checksum mismatch.")
    baseline = inspect_apk(baseline_apk, apksigner, apkanalyzer)
    preview = inspect_apk(apk, apksigner, apkanalyzer)
    verify_upgrade(baseline, preview)
    verify_bundle(apk)
    result = {
        "schemaVersion": 1,
        "sourceCommit": source_sha,
        "apkFilename": APK_NAME,
        "apkSha256": sha256(apk),
        "baselineRelease": BASELINE_TAG,
        "baselineApkSha256": BASELINE_SHA256,
        "baseline": baseline,
        "preview": preview,
        "checks": {
            "apkSignaturesValid": True,
            "sameSignerCertificate": True,
            "sameApplicationId": True,
            "higherVersionCode": True,
            "noAddedPermissions": True,
            "bundledOfflineUiPresent": True,
        },
        "limitations": [
            "Hardware and in-place installation have not been validated on a device.",
            "XREAL preview is for stationary testing only; physical output is unverified.",
        ],
    }
    Path(output).write_text(json.dumps(result, indent=2) + "\n")
    return result


def verify_delivery(directory, source_sha):
    """Recheck exact workflow artifact identity before release publication."""
    require(bool(COMMIT_RE.fullmatch(source_sha)), "Expected an exact source commit SHA.")
    directory = Path(directory)
    expected_files = {APK_NAME, "verification.json", "SHA256SUMS", "SOURCE_COMMIT.txt"}
    require({path.name for path in directory.iterdir()} == expected_files,
            "Delivery artifact has missing or unexpected files.")
    result = json.loads((directory / "verification.json").read_text())
    require(result.get("schemaVersion") == 1 and result.get("sourceCommit") == source_sha,
            "Artifact was built from a different source commit.")
    require((directory / "SOURCE_COMMIT.txt").read_text().strip() == source_sha,
            "Source commit file does not match this workflow run.")
    require(result.get("apkFilename") == APK_NAME and result.get("apkSha256") == sha256(directory / APK_NAME),
            "Delivery APK checksum mismatch.")
    require(result.get("baselineRelease") == BASELINE_TAG and result.get("baselineApkSha256") == BASELINE_SHA256,
            "Delivery used an unexpected baseline release.")
    verify_upgrade(result["baseline"], result["preview"])
    require(result.get("checks") == {name: True for name in (
        "apkSignaturesValid", "sameSignerCertificate", "sameApplicationId", "higherVersionCode",
        "noAddedPermissions", "bundledOfflineUiPresent")}, "Delivery verification is incomplete.")
    expected_sum = f"{result['apkSha256']}  {APK_NAME}\n"
    require((directory / "SHA256SUMS").read_text() == expected_sum, "Checksum file is inconsistent.")
    verify_bundle(directory / APK_NAME)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk")
    parser.add_argument("--baseline")
    parser.add_argument("--apksigner")
    parser.add_argument("--apkanalyzer")
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--output")
    parser.add_argument("--check-delivery")
    args = parser.parse_args()
    if args.check_delivery:
        verify_delivery(args.check_delivery, args.source_sha)
    else:
        require(all((args.apk, args.baseline, args.apksigner, args.apkanalyzer, args.output)), "APK verification arguments are required.")
        verify(args.apk, args.baseline, args.apksigner, args.apkanalyzer, args.source_sha, args.output)
    print("ICARUS preview upgrade and delivery checks passed.")


if __name__ == "__main__":
    main()
