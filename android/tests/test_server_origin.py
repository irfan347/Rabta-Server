import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('server_origin', ROOT/'scripts/server_origin.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ServerOriginTests(unittest.TestCase):
    def test_current_project_resolves_production_not_preview(self):
        config = (ROOT/'app/src/main/java/com/irfan/rabta/Config.kt').read_text()
        gradle = (ROOT/'app/build.gradle.kts').read_text()
        self.assertEqual(module.production_origin(config, gradle), 'https://rabta-server-1e2i.onrender.com')

    def test_legacy_literal(self):
        self.assertEqual(module.production_origin('const val SERVER = "https://example.com"', ''), 'https://example.com')

    def test_missing_or_non_https_fails_clearly(self):
        for source in ['const val SERVER = BuildConfig.API_ORIGIN', 'const val SERVER = "http://example.com"']:
            with self.assertRaises(ValueError):
                module.production_origin(source, 'defaultConfig {}')
