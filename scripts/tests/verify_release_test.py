#!/usr/bin/env python3
"""Check release configuration parsing and signing without real credentials."""

import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
  "verify_release", Path(__file__).resolve().parents[1] / "verify-release.py"
)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class PropertiesTest(unittest.TestCase):
  def test_java_escapes_separators_and_continuations(self):
    with tempfile.TemporaryDirectory() as directory:
      path = Path(directory) / "sample.properties"
      path.write_text(
        "  # ignored\n! ignored\n"
        "escaped\\ key\\:part : path\\ with\\ spaces\\\\suffix\n"
        "continued=first\\\n  second\\\n\t#literal\n"
        "tabs = one\\ttwo\\nthree\\rfour\\ffive\n"
        "equals\\=key=value=more\n"
        "unicode=\\u00e9\\uD83D\\uDE80 café\n"
        "duplicate=old\nduplicate new\n"
        "empty:\nunknown=\\q\neven=two\\\\\nlast=end\\",
        encoding="utf-8",
      )
      self.assertEqual(VERIFY.read_properties(path), {
        "escaped key:part": "path with spaces\\suffix",
        "continued": "firstsecond#literal",
        "tabs": "one\ttwo\nthree\rfour\ffive",
        "equals=key": "value=more",
        "unicode": "é🚀 café",
        "duplicate": "new",
        "empty": "",
        "unknown": "q",
        "even": "two\\",
        "last": "end",
      })

  def test_invalid_unicode_does_not_expose_property_value(self):
    with tempfile.TemporaryDirectory() as directory:
      path = Path(directory) / "invalid.properties"
      path.write_text("password=PRIVATE\\u12xz\n", encoding="utf-8")
      with self.assertRaises(ValueError) as error:
        VERIFY.read_properties(path)
      self.assertNotIn("PRIVATE", str(error.exception))


class SigningConfigTest(unittest.TestCase):
  def setUp(self):
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    self.root = Path(self.directory.name)
    (self.root / "config").mkdir()
    (self.root / "local.properties").write_text(
      "sdk.dir=android\\ sdk\nkeystore.file=config/signing.properties\n", encoding="utf-8"
    )
    (self.root / "version.properties").write_text("version.build_tools=37.0.0\n")
    (self.root / "config/signing.properties").write_text(
      "keystore.file=release.jks\nkeystore.password=SECRET\nkey.alias=release\n"
    )
    (self.root / "release.jks").write_bytes(b"fake keystore")

  def test_certificate_password_uses_child_environment_and_root_relative_paths(self):
    previous_password = os.environ.get("CLIENT_KEYSTORE_PASSWORD")
    with patch.object(VERIFY.subprocess, "run") as run:
      run.return_value.stdout = b"certificate DER"
      build_tools, certificate = VERIFY.local_signing_config(self.root)
      arguments, = run.call_args.args
      options = run.call_args.kwargs
      self.assertNotIn("SECRET", " ".join(arguments))
      self.assertIn("-storepass:env", arguments)
      self.assertEqual(options["env"]["CLIENT_KEYSTORE_PASSWORD"], "SECRET")
      self.assertEqual(options["cwd"], self.root)
      self.assertEqual(arguments[arguments.index("-keystore") + 1],
                       str(self.root / "release.jks"))
      self.assertEqual(build_tools, self.root / "android sdk/build-tools/37.0.0")
      self.assertEqual(certificate, hashlib.sha256(b"certificate DER").hexdigest())
      self.assertEqual(options["capture_output"], True)
      self.assertEqual(os.environ.get("CLIENT_KEYSTORE_PASSWORD"), previous_password)

  def test_keytool_failure_hides_command_output(self):
    with patch.object(VERIFY.subprocess, "run") as run:
      run.side_effect = subprocess.CalledProcessError(
        1, ["keytool"], output=b"SECRET", stderr=b"SECRET"
      )
      with self.assertRaisesRegex(ValueError, "check signing configuration") as error:
        VERIFY.local_signing_config(self.root)
      self.assertNotIn("SECRET", str(error.exception))


class CommandLineTest(unittest.TestCase):
  def test_existing_ci_arguments_and_project_root_remain_supported(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "version.properties").write_text("version.app=1814\n")
      with patch.object(VERIFY, "signer", return_value="abc"), \
           patch.object(VERIFY, "manifest", return_value=("com.pulsargram.x", 1814001)), \
           patch.object(VERIFY, "verify_tdlib") as tdlib, \
           patch.object(VERIFY, "verify_profiles") as profiles, \
           patch.object(VERIFY, "local_signing_config") as local, \
           patch.object(VERIFY.subprocess, "run") as public_audit, \
           contextlib.redirect_stdout(io.StringIO()):
        VERIFY.main(["release.apk", "--build-tools", "sdk/build-tools/37.0.0",
                     "--expected-certificate", "ABC", "--project-root", str(root)])
        tdlib.assert_called_once_with(root, Path("release.apk"))
        profiles.assert_called_once_with(root, Path("release.apk"), "publicLatestArm64Release")
        local.assert_not_called()

  def test_explicit_signing_arguments_are_still_required_without_local_flag(self):
    with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
      VERIFY.main(["release.apk"])
    self.assertEqual(error.exception.code, 2)

  def test_local_flag_supplies_build_tools_and_certificate(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "version.properties").write_text("version.app=1814\n")
      tools = root / "sdk/build-tools/37.0.0"
      with patch.object(VERIFY, "local_signing_config", return_value=(tools, "abc")), \
           patch.object(VERIFY, "signer", return_value="abc") as signer, \
           patch.object(VERIFY, "manifest", return_value=("com.pulsargram.x", 1814001)), \
           patch.object(VERIFY, "verify_tdlib"), patch.object(VERIFY, "verify_profiles"), \
           patch.object(VERIFY.subprocess, "run"), \
           contextlib.redirect_stdout(io.StringIO()):
        VERIFY.main(["release.apk", "--project-root", str(root), "--local-signing-config"])
        signer.assert_called_once_with(tools, Path("release.apk"))


if __name__ == "__main__":
  unittest.main()
