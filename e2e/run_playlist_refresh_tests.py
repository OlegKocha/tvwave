#!/usr/bin/env python3
"""Maestro playlist refresh regression. Isolated debug app and local HTTP fixtures only.

Requires debug + androidTest APKs installed on emulator-5556. Does not use IPTV credentials.
Covers manual/retry, HTTP/invalid-content failures, file exclusion, EPG single/all/automatic.
"""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime, timedelta, timezone
import argparse
import json
import subprocess
import threading

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
parser.add_argument('--cases', nargs='+', choices=['manual', 'http-error', 'invalid-content', 'retry', 'file', 'epg-one', 'epg-all', 'automatic'])
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
root = project.parents[2]
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
adb = [str(root / 'work/android-tools/sdk/platform-tools/adb'), '-s', 'emulator-5556']
requests = []
results = []
mode = 'success'

class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        requests.append(self.path)
        code = 200
        if self.path == '/broken' or (self.path == '/list' and mode == 'http'):
            code, content = 503, 'Unavailable'
        elif self.path == '/list':
            content = '<html>Not a playlist</html>' if mode == 'invalid' else (
                '#EXTM3U\n#EXTINF:-1 tvg-id="one" group-title="Тестовые",Канал один\n'
                f'{base}/new\n#EXTINF:-1 tvg-id="added" group-title="Тестовые",Новый канал\n{base}/added\n')
        elif self.path in ('/guide', '/other'):
            now = datetime.now(timezone.utc)
            start = (now - timedelta(minutes=30)).strftime('%Y%m%d%H%M%S %z')
            end = (now + timedelta(minutes=30)).strftime('%Y%m%d%H%M%S %z')
            content = '<?xml version="1.0" encoding="UTF-8"?><tv>'
            # XMLTV puts the complete channel catalogue before the programmes.
            for channel in ['one', 'added']:
                content += f'<channel id="{channel}"><display-name>{channel}</display-name></channel>'
            for channel, title in [('one', 'Программа один'), ('added', 'Программа нового канала')]:
                content += f'<programme channel="{channel}" start="{start}" stop="{end}"><title>{title}</title></programme>'
            content += '</tv>'
        else:
            code, content = 404, 'Not found'
        data = content.encode()
        self.send_response(code)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.end_headers()
        self.wfile.write(data)

def shell(*arguments):
    return subprocess.run(adb + list(arguments), check=True, capture_output=True, timeout=90)

def native(method):
    shell('shell', 'am', 'force-stop', 'tv.tvwave.app.debug')
    result = shell('shell', 'am', 'instrument', '-w', '-e', 'fixtureBase', base,
                   '-e', 'class', 'tv.tvwave.app.PlaylistRefreshMaestroTest#' + method,
                   'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner')
    (out / (case + '-' + method + '.log')).write_bytes(result.stdout + result.stderr)
    assert b'OK (1 test)' in result.stdout, result.stdout.decode()

def flow(name, status=None):
    cmd = ['python3', str(root / 'work/qa-emulator/maestro.py'), 'test', '--device', 'emulator-5556',
           '--format', 'JUNIT', '--output', str(out / (case + '.xml')), '--test-output-dir', str(out / case)]
    if status:
        cmd += ['-e', 'EXPECTED_STATUS=' + status]
    cmd += [str(project / 'e2e/maestro/playlist-refresh' / (name + '.yaml'))]
    result = subprocess.run(cmd, capture_output=True, timeout=240)
    (out / (case + '.log')).write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, 'Maestro failed: ' + str(out / (case + '.log'))

def check_requests(expected):
    assert requests == expected, (requests, expected)

server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
base = f'http://10.0.2.2:{server.server_port}'
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    for case in args.cases or ['manual', 'http-error', 'invalid-content', 'retry', 'file', 'epg-one', 'epg-all', 'automatic']:
        requests.clear()
        mode = {'http-error': 'http', 'invalid-content': 'invalid'}.get(case, 'success')
        try:
            # Retry deliberately retains the playlist surviving the previous failed request.
            if case != 'retry' or not results or results[-1]['case'] != 'invalid-content':
                native('seedAutomatic' if case == 'automatic' else 'seedManual')
            if case in ['manual', 'retry', 'http-error', 'invalid-content']:
                status = {'http-error': 'Источник вернул HTTP 503',
                          'invalid-content': '.*M3U.*'}.get(case, 'Плейлист обновлён: 2 каналов')
                flow('refresh', status)
                native('assertUnchanged' if case in ['http-error', 'invalid-content'] else 'assertManualUpdated')
                check_requests(['/list'])
            elif case == 'file':
                flow('file')
                native('assertUnchanged')
                check_requests([])
            else:
                flow(case)
                native('assertOneEpgUpdated' if case == 'epg-one' else 'assertAllEpgUpdated')
                if case == 'epg-one':
                    check_requests(['/list', '/broken', '/guide'])
                else:
                    # Playlist refresh precedes the two independent XMLTV downloads.
                    assert requests[:2] == ['/list', '/broken'], requests
                    assert sorted(requests[2:]) == ['/guide', '/other'], requests
            results.append({'case': case, 'passed': True, 'requests': requests.copy()})
            print(case, 'PASS', flush=True)
        except Exception:
            results.append({'case': case, 'passed': False, 'requests': requests.copy()})
            raise
        finally:
            (out / 'results.json').write_text(json.dumps(results, indent=2))
finally:
    shell('shell', 'am', 'force-stop', 'tv.tvwave.app.debug')
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
print('All playlist refresh scenarios passed', flush=True)
