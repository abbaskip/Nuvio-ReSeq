import base64
import unittest

from ci_app_config import excluded_tmdb_values, sanitized_config


class CiAppConfigTest(unittest.TestCase):
    def test_personal_credentials_never_reach_build_properties(self):
        raw = "\ufeff# ignored\n sdk.dir=C:/old/sdk\nNUVIO_RELEASE_STORE_FILE=old.jks\nTMDB_API_KEY=fixture-personal-key\nTMDB_READ_ACCESS_TOKEN=fixture-personal-token\nNUVIO_SUPABASE_URL=https://backend.test\nNUVIO_SUPABASE_ANON_KEY=approved-fixture-backend\n"
        encoded = base64.b64encode(raw.encode()).decode()
        clean = sanitized_config(encoded)
        self.assertNotIn("fixture-personal", clean)
        self.assertNotIn("TMDB", clean)
        self.assertNotIn("old", clean)
        self.assertIn("NUVIO_SUPABASE_URL=https://backend.test", clean)
        self.assertIn("NUVIO_SUPABASE_ANON_KEY=approved-fixture-backend", clean)
        self.assertEqual(["fixture-personal-key", "fixture-personal-token"], excluded_tmdb_values(encoded))
