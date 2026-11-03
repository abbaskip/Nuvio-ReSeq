import struct
import tempfile
import unittest
from pathlib import Path
import zipfile

from verify_apk_services import REQUIRED, verify, verify_backend


def fixture_dex(strings):
    header = bytearray(112)
    header[:8] = b"dex\n035\0"
    struct.pack_into("<II", header, 56, len(strings), 112)
    table = bytearray(4 * len(strings))
    payload = bytearray()
    for index, value in enumerate(strings):
        struct.pack_into("<I", table, index * 4, 112 + len(table) + len(payload))
        encoded = value.encode()
        length = len(value)
        while length >= 128:
            payload.append((length & 127) | 128)
            length >>= 7
        payload.append(length)
        payload.extend(encoded + b"\0")
    return bytes(header + table + payload)


class ApkServicesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.values = {name: "fixture-" + name.lower() for name in REQUIRED}
        for name in ("NUVIO_SUPABASE_URL", "TV_LOGIN_WEB_BASE_URL", "DEVICE_LOGIN_WEB_BASE_URL"):
            self.values[name] = "https://services.test/" + name.lower()
        self.values["NUVIO_SUPABASE_ANON_KEY"] = "fixture-backend-key-for-testing-only"

    def apks(self, values=None):
        result = []
        for index in range(5):
            path = self.root / f"variant-{index}.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", fixture_dex(values or list(self.values.values())))
            result.append(path)
        return result

    def test_finished_artifacts_with_all_services_pass(self):
        result = verify(self.apks(), self.values)
        self.assertEqual(5, len(result))
        self.assertTrue(all(row["backend_key_configured"] for row in result))
        self.assertNotIn(self.values["NUVIO_SUPABASE_ANON_KEY"], str(result))

    def test_correct_properties_cannot_mask_missing_packaged_key(self):
        packaged = [value for name, value in self.values.items() if name != "NUVIO_SUPABASE_ANON_KEY"]
        with self.assertRaisesRegex(ValueError, "Actual APK is missing"):
            verify(self.apks(packaged), self.values)

    def test_one_wrong_variant_cannot_be_hidden_by_a_valid_universal_apk(self):
        apks = self.apks()
        with zipfile.ZipFile(apks[-1], "w") as archive:
            archive.writestr("classes.dex", fixture_dex(list(self.values.values()) + ["unexpected code variant"]))
        with self.assertRaisesRegex(ValueError, "DEX payloads differ"):
            verify(apks, self.values)

    def test_placeholder_backend_is_rejected(self):
        self.values["NUVIO_SUPABASE_URL"] = "https://your-project.invalid"
        with self.assertRaisesRegex(ValueError, "placeholder"):
            verify(self.apks(), self.values)

    def test_personal_tmdb_key_in_properties_is_rejected(self):
        self.values["TMDB_API_KEY"] = "fixture-personal-key"
        with self.assertRaisesRegex(ValueError, "Personal TMDB"):
            verify(self.apks(), self.values)

    def test_personal_tmdb_key_in_finished_artifact_is_rejected(self):
        forbidden = "fixture-excluded-personal-key"
        with self.assertRaisesRegex(ValueError, "Excluded personal TMDB"):
            verify(self.apks(list(self.values.values()) + [forbidden]), self.values, forbidden)

    def test_multiple_excluded_tmdb_credentials_are_checked_without_exposing_values(self):
        forbidden = ["fixture-personal-key", "fixture-personal-token"]
        with self.assertRaisesRegex(ValueError, "Excluded personal TMDB"):
            verify(self.apks(list(self.values.values()) + [forbidden[1]]), self.values, forbidden)

    def test_instrumentation_harness_has_its_actual_backend_checked(self):
        url = self.values["NUVIO_SUPABASE_URL"]
        key = self.values["NUVIO_SUPABASE_ANON_KEY"]
        apks = self.apks([url, key])[:1]
        self.assertTrue(verify_backend(apks, self.values, [])[0]["backend_key_configured"])
        with self.assertRaisesRegex(ValueError, "missing configured backend"):
            verify_backend(self.apks([url])[:1], self.values, [])

    def test_underscore_placeholder_backend_key_is_rejected(self):
        self.values["NUVIO_SUPABASE_ANON_KEY"] = "your_supabase_anon_key_here"
        with self.assertRaisesRegex(ValueError, "placeholder"):
            verify(self.apks(), self.values)

    def test_missing_architecture_output_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "all five"):
            verify(self.apks()[:-1], self.values)
