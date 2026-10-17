#!/usr/bin/env python3
"""Verify an artifact-only Play candidate; this never submits or publishes a release."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.icarusalmighty.app"
VERSION_CODE = 48
VERSION_NAME = "1.6.17"
MIN_SDK = 29
TARGET_SDK = 36
EXPECTED_CERTIFICATE = "6b87a7031aa5273d50d0e692875d0d284fd7e9b905bb7dba8e05c3ab67d7e2c6"
AAB_NAME = "ICARUS-play-candidate.aab"
COMMIT_RE = re.compile(r"[a-f0-9]{40}")
ANDROID = "{http://schemas.android.com/apk/res/android}"


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
    # Inspection of public build outputs only; no signing passwords in arguments.
    return subprocess.run(command, text=True, capture_output=True, check=True).stdout.strip()


def certificate_info(output):
    match = re.fullmatch(r"CERTIFICATE_SHA256=([0-9a-f]{64})\nSIGNED_PAYLOAD_ENTRIES=([1-9][0-9]*)", output)
    require(match is not None, "AAB signature verifier returned invalid metadata.")
    return {"certificateSha256": match.group(1), "signedPayloadEntries": int(match.group(2))}


def manifest_info(output):
    require("<!DOCTYPE" not in output.upper() and "<!ENTITY" not in output.upper(), "Unexpected manifest XML declaration.")
    try:
        root = ET.fromstring(output)
    except ET.ParseError as error:
        raise ValueError("AAB manifest XML is malformed.") from error
    require(root.tag == "manifest", "Unexpected manifest root.")
    sdk = root.find("uses-sdk")
    app = root.find("application")
    require(sdk is not None and app is not None, "Manifest SDK or application is missing.")
    def integer(element, name):
        value = element.get(ANDROID + name, "")
        require(bool(re.fullmatch(r"[0-9]+", value)), "Invalid manifest " + name + ".")
        return int(value)
    permissions = []
    for child in root:
        if child.tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            name = child.get(ANDROID + "name", "")
            require(bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", name)), "Invalid permission name.")
            permissions.append(name)
    result = {
        "package": root.get("package"),
        "versionCode": integer(root, "versionCode"),
        "versionName": root.get(ANDROID + "versionName"),
        "minSdk": integer(sdk, "minSdkVersion"),
        "targetSdk": integer(sdk, "targetSdkVersion"),
        "debuggable": app.get(ANDROID + "debuggable", "false"),
        "permissions": sorted(set(permissions)),
    }
    verify_identity(result)
    return result


def verify_identity(result):
    require(result.get("package") == PACKAGE, "Unexpected application ID.")
    require(result.get("versionCode") == VERSION_CODE and result.get("versionName") == VERSION_NAME, "Unexpected candidate version.")
    require(result.get("minSdk") == MIN_SDK and result.get("targetSdk") == TARGET_SDK, "Unexpected SDK levels.")
    require(result.get("debuggable") == "false", "Candidate must be non-debuggable.")
    require(bool(result.get("permissions")), "Candidate permission inventory is missing.")


def elf_info(data, path):
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64 library: " + path)
    require(struct.unpack_from("<H", data, 18)[0] == 183, "Expected AArch64 library: " + path)
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    require(entry_size >= 56 and count > 0 and offset + count * entry_size <= len(data), "Invalid ELF program-header table: " + path)
    headers = [struct.unpack_from("<IIQQQQQQ", data, offset + i * entry_size) for i in range(count)]
    loads = [h for h in headers if h[0] == 1]
    require(bool(loads), "ELF has no LOAD segments: " + path)
    require(all(h[2] + h[5] <= len(data) and h[6] >= h[5] for h in loads), "ELF LOAD segment is truncated: " + path)
    aligned = all(h[7] >= 16384 and h[7] & (h[7] - 1) == 0 and (h[3] - h[2]) % 16384 == 0 for h in loads)
    require(aligned, "Native LOAD segment is not 16 KB aligned: " + path)
    relro = [h for h in headers if h[0] == 0x6474E552]
    return {"path": path, "sha256": hashlib.sha256(data).hexdigest(), "loadAlignments": [h[7] for h in loads],
            "loadSegments16kAligned": aligned, "relroEnds16kAligned": all((h[3] + h[6]) % 16384 == 0 for h in relro)}


def bundle_contents(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "AAB has duplicate ZIP entries.")
        require(all(not n.startswith("/") and ".." not in n.split("/") for n in names), "AAB has unsafe entry paths.")
        require(archive.testzip() is None, "AAB ZIP integrity check failed.")
        require("BundleConfig.pb" in names and "base/manifest/AndroidManifest.xml" in names, "AAB is missing mandatory bundle files.")
        require(not any(n.lower().endswith((".jks", ".keystore")) for n in names), "AAB contains signing material.")
        prefix = "base/assets/web/"
        require(prefix + "index.html" in names, "Bundled local UI is missing.")
        index = archive.read(prefix + "index.html").decode("utf-8")
        scripts = re.findall(r'<script\b[^>]*\bsrc=["\']([^"\']+)["\']', index, re.IGNORECASE)
        require(bool(scripts), "Bundled UI has no application scripts.")
        for script in scripts:
            require(script.startswith("./assets/") and ".." not in script[2:].split("/") and prefix + script[2:] in names, "Bundled UI script is missing or nonlocal.")
        graphics = {}
        for name in ("icarus-app-icon.png", "icarus-companion.png"):
            payload = archive.read(prefix + "brand/" + name)
            require(payload.startswith(b"\x89PNG\r\n\x1a\n"), "Dashboard graphic is invalid: " + name)
            graphics[name] = hashlib.sha256(payload).hexdigest()
        libraries = []
        for name in names:
            if "/lib/" in name and name.endswith(".so"):
                require(name.startswith("base/lib/arm64-v8a/"), "Unexpected native ABI or module: " + name)
                libraries.append(elf_info(archive.read(name), name))
        require(bool(libraries), "Expected packaged native libraries.")
        return {"nativeLibraries": libraries, "dashboardGraphicsSha256": graphics}


def config_16k(output):
    # bundletool's documented dump config format has changed between releases.
    # Check the exact enum, not presentation-oriented indentation or field case.
    require("PAGE_ALIGNMENT_16K" in output and "PAGE_ALIGNMENT_4K" not in output, "Bundle does not request 16 KB APK ZIP alignment.")


def verify(aab, bundletool, source_sha, expected_certificate, output):
    require(bool(COMMIT_RE.fullmatch(source_sha)), "Expected an exact source commit SHA.")
    require(expected_certificate == EXPECTED_CERTIFICATE, "Unexpected expected upload certificate.")
    helper = Path(__file__).with_name("AabCertificateInfo.java")
    signer = certificate_info(run_tool(["java", str(helper), str(aab)]))
    require(signer["certificateSha256"] == expected_certificate, "AAB signer does not match the registered Play upload certificate.")
    run_tool(["java", "-jar", str(bundletool), "validate", "--bundle=" + str(aab)])
    identity = manifest_info(run_tool(["java", "-jar", str(bundletool), "dump", "manifest", "--bundle=" + str(aab), "--module=base"]))
    config_16k(run_tool(["java", "-jar", str(bundletool), "dump", "config", "--bundle=" + str(aab)]))
    contents = bundle_contents(aab)
    relro_flags = [x["path"] for x in contents["nativeLibraries"] if not x["relroEnds16kAligned"]]
    result = {"schemaVersion": 1, "sourceCommit": source_sha, "aabFilename": AAB_NAME, "aabSha256": sha256(aab),
              "aabBytes": Path(aab).stat().st_size, "manifest": identity, "signer": signer, **contents,
              "checks": {"signedPayloadVerified": True, "registeredUploadCertificateMatches": True, "bundletoolValidation": True,
                         "expectedReleaseIdentity": True, "bundledOfflineUiPresent": True, "bundleRequests16kZipAlignment": True,
                         "nativeLoadSegments16kAligned": True},
              "readiness": {"playSubmitted": False, "productionReady": False, "deviceTested": False,
                            "relroEndAlignmentFlags": relro_flags, "generatedApk16kRuntimeTested": False},
              "limitations": ["Artifact-only candidate; no Play track upload or tester enrollment has occurred.",
                              "Play distribution uses a different app-signing certificate from the upload/sideload certificate.",
                              "Native RELRO flags need vendor/runtime investigation; LOAD alignment alone is not full 16 KB compatibility.",
                              "In-app AI-output reporting and operational moderation are not implemented.",
                              "Physical phone/XREAL testing remains pending; XREAL is stationary evaluation only."]}
    Path(output).write_text(json.dumps(result, indent=2) + "\n")
    return result


def verify_delivery(directory, source_sha):
    require(bool(COMMIT_RE.fullmatch(source_sha)), "Expected an exact source commit SHA.")
    directory = Path(directory)
    require({p.name for p in directory.iterdir()} == {AAB_NAME, "verification.json", "SHA256SUMS", "SOURCE_COMMIT.txt"}, "Candidate artifact has missing or unexpected files.")
    result = json.loads((directory / "verification.json").read_text())
    require(result.get("schemaVersion") == 1 and result.get("sourceCommit") == source_sha, "Candidate report source mismatch.")
    require((directory / "SOURCE_COMMIT.txt").read_text().strip() == source_sha, "Candidate source file mismatch.")
    require(result.get("aabFilename") == AAB_NAME and result.get("aabSha256") == sha256(directory / AAB_NAME), "Candidate checksum mismatch.")
    require(result.get("aabBytes") == (directory / AAB_NAME).stat().st_size, "Candidate byte count mismatch.")
    require((directory / "SHA256SUMS").read_text() == f"{result['aabSha256']}  {AAB_NAME}\n", "Candidate checksum file mismatch.")
    verify_identity(result.get("manifest", {}))
    require(result.get("signer", {}).get("certificateSha256") == EXPECTED_CERTIFICATE, "Candidate upload certificate mismatch.")
    require(result.get("checks") == {name: True for name in ("signedPayloadVerified", "registeredUploadCertificateMatches", "bundletoolValidation", "expectedReleaseIdentity", "bundledOfflineUiPresent", "bundleRequests16kZipAlignment", "nativeLoadSegments16kAligned")}, "Candidate checks are incomplete.")
    require(result.get("readiness", {}).get("playSubmitted") is False and result.get("readiness", {}).get("productionReady") is False, "Artifact must not claim Play publication or production readiness.")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aab", type=Path)
    parser.add_argument("--bundletool", type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--expected-certificate")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-delivery", type=Path)
    args = parser.parse_args()
    if args.check_delivery:
        verify_delivery(args.check_delivery, args.source_sha)
    else:
        parser.error("--aab, --bundletool, --expected-certificate and --output are required") if not all((args.aab, args.bundletool, args.expected_certificate, args.output)) else None
        verify(args.aab, args.bundletool, args.source_sha, args.expected_certificate, args.output)
    print("ICARUS candidate artifact checks passed; store and hardware readiness remain separate.")
