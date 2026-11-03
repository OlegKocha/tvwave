#!/usr/bin/env python3
"""Browser QA bridge for the emulator's private NAT address, loopback only.
Host and a matching browser Origin are translated together; null/foreign origins
are kept intact so real form submission policies are still exercised.
Never use this bridge for physical TVs or real provider credentials.
"""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import argparse, subprocess, urllib.parse, http.client, json
p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556']
png=out/'browser-qr.png';png.write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
jar=next((root/'work/android-tools/gradle-home/caches').rglob('core-3.5.3.jar'))
url=subprocess.check_output([str(root/'work/android-tools/amazon-corretto-17.jdk/Contents/Home/bin/java'),'-cp',str(jar),str(project/'e2e/QrDecode.java'),str(png)],text=True).strip()
uri=urllib.parse.urlsplit(url);local='http://127.0.0.1:18889';results=[]
subprocess.run(adb+['emu','redir','add',f'tcp:18888:{uri.port}'],check=True)
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def handle_request(self):
  length=int(self.headers.get('Content-Length','0'))
  if length>24576:self.send_error(413);return
  headers={'Host':uri.netloc}
  for name in ['Content-Type','Origin']:
   if self.headers.get(name):headers[name]=self.headers[name]
  if headers.get('Origin')==local:headers['Origin']='http://'+uri.netloc
  c=http.client.HTTPConnection('127.0.0.1',18888,timeout=5)
  try:
   c.request(self.command,self.path,self.rfile.read(length) if length else None,headers)
   r=c.getresponse();body=r.read();self.send_response(r.status)
   for name,value in r.getheaders():
    if name.lower() not in ['connection','server','date']:self.send_header(name,value)
   self.end_headers();self.wfile.write(body)
   results.append({'method':self.command,'browserOrigin':self.headers.get('Origin'),'status':r.status})
   (out/'browser-http.json').write_text(json.dumps(results,indent=2)+'\n')
  except (ConnectionError,TimeoutError,OSError):self.send_error(502)
  finally:c.close()
 do_GET=handle_request
 do_POST=handle_request
print(local+uri.path,flush=True)
try:ThreadingHTTPServer(('127.0.0.1',18889),Handler).serve_forever()
except KeyboardInterrupt:pass
finally:subprocess.run(adb+['emu','redir','del','tcp:18888'],check=False)
