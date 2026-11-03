#!/usr/bin/env python3
"""Maestro UI + real loopback HTTP; never touches the physical TV/provider."""
from pathlib import Path
import argparse,subprocess,json,urllib.request,urllib.error,urllib.parse,time
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--flow',choices=['all','recovery','phone'],default='all');a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
def shell(*args):return subprocess.run(adb+list(args),capture_output=True,check=True)
def seed(media='http://10.0.2.2:8765/video.mp4'):
 shell('shell','am','force-stop','tv.tvwave.app.debug')
 r=shell('shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#seedForMaestro','-e','media',media,'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner')
 assert b'OK (1 test)' in r.stdout

def flow(name,label=None):
 label=label or name
 cmd=['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device','emulator-5556','--format','JUNIT','--output',str(out/(label+'.xml')),'--test-output-dir',str(out/label),str(project/'e2e/maestro/network'/(name+'.yaml'))]
 r=subprocess.run(cmd,capture_output=True,timeout=180);(out/(label+'.log')).write_bytes(r.stdout+r.stderr)
 results.append({'case':label,'passed':r.returncode==0});(out/'results.json').write_text(json.dumps(results,indent=2));print(label,r.returncode,flush=True)
 if r.returncode:raise RuntimeError('Maestro failed: '+label)

def control(mode):
 urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8766/control',data=json.dumps({'mode':mode}).encode(),headers={'Content-Type':'application/json'})).read()
def stats():return json.load(urllib.request.urlopen('http://127.0.0.1:8766/stats'))
measurements=[]
def verify_requests(case,allowed):
 value=stats();measurements.append({'case':case,**value});(out/'http-requests.json').write_text(json.dumps(measurements,indent=2))
 assert value['requests'] in allowed,value
if a.flow in ['all','recovery']:
 for mode in ['fail2','disconnect']:
  control(mode);seed('http://10.0.2.2:8766/recovery.mp4');flow('recovery-success','recovery-'+mode);verify_requests(mode,range(2,5))
 control('always');seed('http://10.0.2.2:8766/recovery.mp4');flow('recovery-exhausted');time.sleep(2);verify_requests('exhausted',[4])
 control('healthy');flow('recovery-manual');verify_requests('manual',[1])
 control('denied');seed('http://10.0.2.2:8766/recovery.mp4');flow('recovery-denied');time.sleep(3);verify_requests('denied',[1])
if a.flow in ['all','phone']:
 for target in ['welcome','settings','playlist','epg']:
  if target!='settings':seed()
  flow({'welcome':'phone-welcome-open','settings':'phone-settings-open','playlist':'phone-open','epg':'phone-epg-open'}[target])
  png=out/(target+'-qr-screen.png');png.write_bytes(shell('exec-out','screencap','-p').stdout)
  jar=next((root/'work/android-tools/gradle-home/caches').rglob('core-3.5.3.jar'))
  java=root/'work/android-tools/amazon-corretto-17.jdk/Contents/Home/bin/java'
  url=subprocess.check_output([str(java),'-cp',str(jar),str(project/'e2e/QrDecode.java'),str(png)],text=True)
  uri=urllib.parse.urlsplit(url);port=uri.port;hosturl='http://127.0.0.1:18888'+uri.path
  shell('emu','redir','add',f'tcp:18888:{port}')
  try:
   def request(path=hosturl,body=None,origin=None,host=None):
    headers={'Host':host or uri.netloc}
    if body is not None:headers['Content-Type']='application/x-www-form-urlencoded'
    if origin:headers['Origin']=origin
    try:
     r=urllib.request.urlopen(urllib.request.Request(path,data=body,headers=headers),timeout=5);return r.status,r.read().decode()
    except urllib.error.HTTPError as e:return e.code,e.read().decode()
   with urllib.request.urlopen(urllib.request.Request(hosturl,headers={'Host':uri.netloc}),timeout=5) as response:
    assert response.status==200
    assert response.headers['Referrer-Policy']=='same-origin'
   assert request(host='evil.invalid')[0]==404
   assert request(origin='http://evil.invalid',body=b'url=https%3A%2F%2Fexample.com')[0]==403
   assert request(origin='null',body=b'url=https%3A%2F%2Fexample.com')[0]==403
   assert request(origin='http://'+uri.netloc,body=b'url=not-a-url')[0]==400
   value='http://10.0.2.2:8765/playlist.m3u8' if target!='epg' else 'http://10.0.2.2:8765/epg/2.xml'
   assert request(origin='http://'+uri.netloc,body=urllib.parse.urlencode({'url':value}).encode())[0]==200
   try:request();raise AssertionError('Session remained open')
   except (urllib.error.URLError,ConnectionError,TimeoutError):pass
   flow('phone-second-receive' if target=='settings' else 'phone-epg-receive' if target=='epg' else 'phone-receive',target+'-receive')
  finally:shell('emu','redir','del','tcp:18888')
 if a.flow in ['all','phone']:
  for closing in ['cancel','background']:
   seed();flow('phone-open','phone-'+closing+'-open')
   png=out/('phone-'+closing+'.png');png.write_bytes(shell('exec-out','screencap','-p').stdout)
   jar=next((root/'work/android-tools/gradle-home/caches').rglob('core-3.5.3.jar'));java=root/'work/android-tools/amazon-corretto-17.jdk/Contents/Home/bin/java'
   url=subprocess.check_output([str(java),'-cp',str(jar),str(project/'e2e/QrDecode.java'),str(png)],text=True);uri=urllib.parse.urlsplit(url)
   shell('emu','redir','add',f'tcp:18888:{uri.port}')
   try:
    flow('phone-'+closing)
    try:
     urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18888'+uri.path,headers={'Host':uri.netloc}),timeout=5)
     raise AssertionError('Cancelled/background session remained open')
    except (urllib.error.URLError,ConnectionError,TimeoutError):pass
   finally:shell('emu','redir','del','tcp:18888')
print('Network flows passed',flush=True)
