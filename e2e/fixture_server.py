#!/usr/bin/env python3
"""Loopback-only synthetic IPTV/EPG server. No provider credentials or external requests."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from datetime import datetime, timedelta, timezone
from pathlib import Path
import argparse, gzip, json
from threading import Lock
from urllib.parse import urlsplit, parse_qs
RECOVERY={"mode":"healthy","requests":0}
RECOVERY_LOCK=Lock()
BASE=Path(__file__).parent/'fixtures'
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path!='/control':self.send_error(404);return
        length=int(self.headers.get('Content-Length','0'))
        if length>1024:self.send_error(413);return
        mode=json.loads(self.rfile.read(length)).get('mode')
        if mode not in ['healthy','fail2','always','denied','disconnect']:self.send_error(400);return
        with RECOVERY_LOCK:RECOVERY.update(mode=mode,requests=0)
        self.send_response(200);self.end_headers();self.wfile.write(b'ok')
    def do_GET(self):
        path=self.path.split('?',1)[0];kind='text/plain; charset=utf-8';status=200
        if path=='/stats':
            with RECOVERY_LOCK:body=json.dumps(RECOVERY).encode()
        elif path=='/recovery.mp4':
            with RECOVERY_LOCK:
                RECOVERY['requests']+=1;count=RECOVERY['requests'];mode=RECOVERY['mode']
            if mode=='denied':status=403;body=b'Forbidden'
            elif mode=='always' or mode=='fail2' and count<=2:status=503;body=b'Temporary outage'
            else:
                body=(BASE/'video.mp4').read_bytes();kind='video/mp4'
                if mode=='disconnect' and count==1:
                    self.send_response(200);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body[:100000]);self.close_connection=True;return
        elif path=='/playlist.m3u8':
            body=('#EXTM3U\n'+''.join(f'#EXTINF:-1 tvg-id="test{i}" group-title="Тестовые",Тестовый канал {i}\nhttp://10.0.2.2:8765/video.mp4\n' for i in range(1,17))).encode();kind='audio/x-mpegurl'
        elif path.startswith('/epg/') and path.endswith(('.xml','.xml.gz')):
            day=parse_qs(urlsplit(self.path).query).get('dayStart',[None])[0]
            try:now=datetime.fromtimestamp(int(day)/1000,timezone.utc)+timedelta(hours=12) if day is not None else datetime.now(timezone.utc)
            except (ValueError,OverflowError,OSError):self.send_error(400);return
            # A resumed AVD can lag behind the host clock. Generic fixtures cover
            # both clocks; management's pinned-day fixture stays inside that day.
            span=timedelta(hours=1 if day is not None else 12)
            start=(now-span).strftime('%Y%m%d%H%M%S +0000');end=(now+span).strftime('%Y%m%d%H%M%S +0000')
            body=('<tv>'+''.join(f'<channel id="test{i}"><display-name>Тестовый канал {i}</display-name></channel>' for i in range(1,17))+''.join(f'<programme channel="test{i}" start="{start}" stop="{end}"><title>Тестовая передача {i}</title></programme>' for i in range(1,17))+'</tv>').encode();kind='application/xml'
            if path.endswith('.gz'):body=gzip.compress(body);kind='application/gzip'
        elif path=='/empty.m3u8':body=b'#EXTM3U\n'
        elif path=='/invalid.m3u8':body=b'This is not a playlist.'
        elif path=='/invalid.xml':body=b'<html>Not XMLTV</html>'
        elif path=='/video.mp4' and (BASE/'video.mp4').exists():body=(BASE/'video.mp4').read_bytes();kind='video/mp4'
        else:status=404;body=b'Fixture not found'
        self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(body)));self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8765);args=parser.parse_args()
    print(f'Synthetic fixtures: http://127.0.0.1:{args.port}',flush=True)
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
