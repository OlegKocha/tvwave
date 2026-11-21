#!/usr/bin/env python3
"""Run on a disposable Android TV emulator. Reinstalls the debug app before each case."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.device):
    parser.error('Specify the serial of a disposable Android TV emulator.')

project = Path(__file__).resolve().parents[1]
apk_dir = project / 'app/build/outputs/apk'
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
adb = ['adb', '-s', args.device]
package = 'tv.tvwave.app.debug'
test_package = package + '.test'
backup_dir = '/sdcard/Download/TVWave'
restore_file = '/sdcard/Download/permission-restore.tvwb'


def shell(*args):
    return subprocess.check_output([*adb, 'shell', *args], text=True).strip()


def reinstall():
    installed = set(shell('pm', 'list', 'packages', 'tv.tvwave.app').splitlines())
    if 'package:tv.tvwave.app' in installed:
        raise RuntimeError('Use a disposable emulator with no release build. Both builds appear as TV Wave in Settings.')
    for name in [test_package, package]:
        if 'package:' + name in installed:
            subprocess.run([*adb, 'uninstall', name], check=True)
    for name in ['debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk']:
        subprocess.run([*adb, 'install', apk_dir / name], check=True)


def backup_names():
    listing = shell('find', backup_dir, '-maxdepth', '1', '-name', "'*.tvwb'")
    return set(listing.splitlines())


def flow(name):
    subprocess.run(['maestro', 'test', '--device', args.device, '--format', 'JUNIT',
                    '--output', output / (name + '.xml'), '--test-output-dir', output / name,
                    project / 'e2e/maestro/file-access' / (name + '.yaml')], check=True)


# An existing backup must never count as evidence for a new export.
shell('mkdir', '-p', backup_dir)
for name in ['export', 'grant-export', 'restore']:
    reinstall()
    if name == 'restore':
        subprocess.run([*adb, 'push', output / 'first-export.tvwb', restore_file], check=True)
        flow(name)
    else:
        result = shell('am', 'instrument', '-w', '-e', 'class',
                       'tv.tvwave.app.TvNavigationTest#seedForMaestro',
                       test_package + '/androidx.test.runner.AndroidJUnitRunner')
        if 'OK (1 test)' not in result:
            raise RuntimeError(result)
        before = backup_names()
        flow(name)
        created = backup_names() - before
        if len(created) != 1:
            raise RuntimeError(f'Expected one new backup, found {created}')
        data = subprocess.check_output([*adb, 'exec-out', 'cat', created.pop()])
        if len(data) <= 48 or hashlib.sha256(data[48:]).digest() != data[16:48]:
            raise RuntimeError('Export payload checksum mismatch')
        (output / ('first-export.tvwb' if name == 'export' else 'second-export.tvwb')).write_bytes(data)
