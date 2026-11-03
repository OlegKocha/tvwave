#!/usr/bin/env python3
"""Deterministic logo responses. Loopback only; no provider data."""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import struct,zlib,json,time
from collections import Counter
from threading import Lock
counts=Counter();lock=Lock()
def png(width=64,height=64,blue=False):
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 pixel=bytes((30,120,240) if blue else (230,60,30))
 row=b'\0'+pixel*width
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(row*height))+chunk(b'IEND',b'')
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  path=self.path.split('?')[0]
  with lock:counts[path]+=1
  status=200;kind='image/png'
  if path=='/stats':body=json.dumps(counts).encode();kind='application/json'
  elif path in ['/good.png','/blue.png']:body=png(blue=path=='/blue.png')
  elif path=='/broken.png':body=b'<html>Not an image</html>';kind='text/html'
  elif path=='/huge.png':body=b'x'*(2*1024*1024+1)
  elif path=='/wide.png':body=png(9000,1)
  elif path=='/slow.png':time.sleep(2);body=png()
  else:status=404;body=b'Not found'
  self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','max-age=86400');self.end_headers()
  try:self.wfile.write(body)
  except (BrokenPipeError,ConnectionResetError):pass
 def log_message(self,*args):pass
if __name__=='__main__':
 print('Logo fixtures on 127.0.0.1:8767',flush=True)
 ThreadingHTTPServer(('127.0.0.1',8767),Handler).serve_forever()
