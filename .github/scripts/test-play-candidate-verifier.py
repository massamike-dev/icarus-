#!/usr/bin/env python3
"""Deterministic candidate verifier regressions; creates no signing credentials."""
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("candidate", Path(__file__).with_name("verify-play-candidate.py"))
candidate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(candidate)


def xml(**changes):
    values = dict(package=candidate.PACKAGE, code="48", version="1.6.17", min="29", target="36", debug="false")
    values.update(changes)
    return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" '
            'android:versionCode="{code}" android:versionName="{version}">'
            '<uses-sdk android:minSdkVersion="{min}" android:targetSdkVersion="{target}"/>'
            '<uses-permission android:name="android.permission.INTERNET"/>'
            '<application android:debuggable="{debug}"/></manifest>').format(**values)


def elf(alignment=16384, machine=183, relro_end=16384):
    data = bytearray(256)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 256, 256, alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 0, 0, 0, 128, relro_end, 1)
    return bytes(data)


class VerifierTests(unittest.TestCase):
    def test_valid_certificate_metadata(self):
        self.assertEqual(candidate.certificate_info("CERTIFICATE_SHA256=" + candidate.EXPECTED_CERTIFICATE + "\nSIGNED_PAYLOAD_ENTRIES=123")["signedPayloadEntries"], 123)

    def test_certificate_metadata_rejects_empty_extra_or_unverified(self):
        for value in ("", "CERTIFICATE_SHA256=" + "a" * 64, "CERTIFICATE_SHA256=" + "a" * 64 + "\nSIGNED_PAYLOAD_ENTRIES=0", "warning\nCERTIFICATE_SHA256=" + "a" * 64 + "\nSIGNED_PAYLOAD_ENTRIES=1"):
            with self.subTest(value=value), self.assertRaises(ValueError): candidate.certificate_info(value)

    def test_expected_release_identity_and_permissions(self):
        result = candidate.manifest_info(xml())
        self.assertEqual(result["targetSdk"], 36)
        self.assertEqual(result["permissions"], ["android.permission.INTERNET"])

    def test_rejects_wrong_identity_sdk_or_debuggable(self):
        for changes in ({"package":"com.other"}, {"code":"45"}, {"version":"1.6.17-test"}, {"min":"28"}, {"target":"35"}, {"debug":"true"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError): candidate.manifest_info(xml(**changes))

    def test_manifest_rejects_xml_entities_and_missing_application(self):
        for value in ('<!DOCTYPE manifest>'+xml(), '<!ENTITY x "y">'+xml(), '<manifest/>', 'not XML'):
            with self.subTest(value=value), self.assertRaises(ValueError): candidate.manifest_info(value)

    def test_manifest_default_debuggable_is_false(self):
        self.assertEqual(candidate.manifest_info(xml().replace(' android:debuggable="false"', ''))["debuggable"], "false")

    def test_elf_load_alignment_and_relro_warning_are_distinct(self):
        self.assertTrue(candidate.elf_info(elf(), "test.so")["relroEnds16kAligned"])
        flagged = candidate.elf_info(elf(relro_end=4096), "test.so")
        self.assertTrue(flagged["loadSegments16kAligned"])
        self.assertFalse(flagged["relroEnds16kAligned"])

    def test_elf_rejects_wrong_abi_truncation_and_low_alignment(self):
        for value in (b"bad", elf(machine=62), elf(alignment=4096), elf()[:100]):
            with self.subTest(length=len(value)), self.assertRaises(ValueError): candidate.elf_info(value, "test.so")

    def test_bundle_requires_16k_enum(self):
        candidate.config_16k('{"alignment":"PAGE_ALIGNMENT_16K"}')
        for value in ("", "PAGE_ALIGNMENT_4K", "PAGE_ALIGNMENT_16K PAGE_ALIGNMENT_4K"):
            with self.subTest(value=value), self.assertRaises(ValueError): candidate.config_16k(value)

    def test_bundle_rejects_missing_files_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "bad.aab"
            with zipfile.ZipFile(path, "w") as archive: archive.writestr("nothing", "test")
            with self.assertRaises(ValueError): candidate.bundle_contents(path)

    def test_delivery_rejects_missing_and_unexpected_files(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError): candidate.verify_delivery(folder, "a" * 40)
            for name in (candidate.AAB_NAME, "verification.json", "SOURCE_COMMIT.txt", "SHA256SUMS", "private.jks"):
                (Path(folder) / name).write_text("test")
            with self.assertRaises(ValueError): candidate.verify_delivery(folder, "a" * 40)

    def test_delivery_validates_exact_hash_source_and_unready_status(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            (directory / candidate.AAB_NAME).write_bytes(b"fixture")
            digest = candidate.sha256(directory / candidate.AAB_NAME)
            report = {"schemaVersion":1,"sourceCommit":"a"*40,"aabFilename":candidate.AAB_NAME,"aabSha256":digest,"aabBytes":7,
                      "manifest":candidate.manifest_info(xml()),"signer":{"certificateSha256":candidate.EXPECTED_CERTIFICATE},
                      "checks":{key:True for key in ("signedPayloadVerified","registeredUploadCertificateMatches","bundletoolValidation","expectedReleaseIdentity","bundledOfflineUiPresent","bundleRequests16kZipAlignment","nativeLoadSegments16kAligned")},
                      "readiness":{"playSubmitted":False,"productionReady":False}}
            (directory / "verification.json").write_text(json.dumps(report))
            (directory / "SOURCE_COMMIT.txt").write_text("a"*40+"\n")
            (directory / "SHA256SUMS").write_text(f"{digest}  {candidate.AAB_NAME}\n")
            candidate.verify_delivery(directory,"a"*40)
            report["readiness"]["productionReady"] = True
            (directory / "verification.json").write_text(json.dumps(report))
            with self.assertRaises(ValueError): candidate.verify_delivery(directory,"a"*40)

    @unittest.skipUnless(shutil.which("java"), "Java is required for unsigned JAR rejection")
    def test_java_verifier_rejects_unsigned_aab_without_creating_a_key(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"unsigned.aab"
            with zipfile.ZipFile(path,"w") as archive:
                archive.writestr("BundleConfig.pb",b"fixture")
                archive.writestr("base/manifest/AndroidManifest.xml",b"fixture")
            result=subprocess.run(["java",str(Path(__file__).with_name("AabCertificateInfo.java")),str(path)],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertIn("unsigned",result.stderr)
            self.assertNotIn("CERTIFICATE_SHA256=",result.stdout)


if __name__ == "__main__": unittest.main()
