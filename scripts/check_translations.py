#!/usr/bin/env python3
"""Validate contributor JSON catalogues. No third-party dependencies."""
import json, re, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'app/src/main/assets/i18n'
TOKEN = re.compile(r'\{p[1-9][0-9]*\}')
def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate key: {key}')
        result[key] = value
    return result

def read(path):
    data = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)
    if set(data) != {'locale', 'nativeName', 'strings'}:
        raise ValueError('expected locale, nativeName and strings')
    if not isinstance(data['locale'], str) or not re.fullmatch(r'[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*', data['locale']):
        raise ValueError('invalid language tag (examples: en, pt-BR, zh-Hant)')
    if path.stem != data['locale']:
        raise ValueError('filename must match locale')
    if not isinstance(data['nativeName'], str) or not 1 <= len(data['nativeName'].strip()) <= 60:
        raise ValueError('nativeName must be the language name in its own language')
    if not isinstance(data['strings'], dict):
        raise ValueError('strings must be an object')
    for key, value in data['strings'].items():
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f'{key}: expected non-empty text')
        if re.search(r'[{}]', TOKEN.sub('', value)):
            raise ValueError(f'{key}: unsupported placeholder; keep {{p1}}, {{p2}}, …')
    return data

def main():
    errors = []
    base = read(DIRECTORY / 'en.json')['strings']
    for path in sorted(DIRECTORY.glob('*.json')):
        try:
            data = read(path)['strings']
            missing, extra = set(base) - set(data), set(data) - set(base)
            if missing: raise ValueError('missing keys: ' + ', '.join(sorted(missing)))
            if extra: raise ValueError('unknown keys: ' + ', '.join(sorted(extra)))
            for key, value in data.items():
                if set(TOKEN.findall(value)) != set(TOKEN.findall(base[key])):
                    raise ValueError(f'{key}: placeholders differ from English')
            print(f'OK {path.name}: {len(data)} strings')
        except (ValueError, TypeError, KeyError) as error:
            errors.append(f'{path.name}: {error}')
    # Every literal key in production code must exist in the English catalogue.
    for source in (ROOT / 'app/src/main/java').rglob('*.kt'):
        for key in re.findall(r'\btr\("([a-z0-9_]+)"', source.read_text()):
            if key not in base: errors.append(f'{source.name}: unknown translation key {key}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('All catalogues and Kotlin translation references are valid.')
    return 0
if __name__ == '__main__':
    sys.exit(main())
