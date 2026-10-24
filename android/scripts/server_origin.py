"""Resolve the default API origin without evaluating Gradle or using preview overrides."""
import json
import re
from urllib.parse import urlsplit


def production_origin(config_source, gradle_source):
    literal = re.search(r'const val SERVER\s*=\s*"([^"\n]+)"', config_source)
    if literal:
        origin = literal.group(1)
    elif re.search(r'const val SERVER\s*=\s*BuildConfig\.API_ORIGIN\b', config_source):
        # Only defaultConfig: never select the staging build type's endpoint.
        default = gradle_source.split('defaultConfig {', 1)[-1].split('buildTypes', 1)[0]
        field = re.search(r'buildConfigField\("String",\s*"API_ORIGIN",\s*("(?:\\.|[^"\\])*")\)', default)
        if not field:
            raise ValueError('No literal default API_ORIGIN found in app/build.gradle.kts')
        origin = json.loads(json.loads(field.group(1)))
    else:
        raise ValueError('Unsupported Config.SERVER declaration')
    parsed = urlsplit(origin)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise ValueError('The default API origin must be an HTTPS origin')
    return origin
