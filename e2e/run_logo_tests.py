#!/usr/bin/env python3
"""Only logo parsing/storage/loading/UI; isolated emulator and local image fixtures."""
from pathlib import Path
import argparse,subprocess,json,hashlib,uuid,time,urllib.request
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--real-providers',action='store_true');a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
def native(name):
 subprocess.run(adb+['shell','am','force-stop','tv.tvwave.app.debug'],check=True,capture_output=True)
 r=subprocess.run(adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.LogoDeviceTest#'+name,'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=90)
 (out/(name+'.log')).write_bytes(r.stdout+r.stderr);passed=b'OK (1 test)' in r.stdout
 results.append({'case':name,'passed':passed});(out/'results.json').write_text(json.dumps(results,indent=2));print(name,passed,flush=True);assert passed
with (out/'server.log').open('w') as log:
 server=subprocess.Popen(['python3',str(project/'e2e/logo_fixture_server.py')],stdout=log,stderr=subprocess.STDOUT)
 try:
  for _ in range(30):
   if server.poll() is not None:raise RuntimeError('Logo server failed')
   try:urllib.request.urlopen('http://127.0.0.1:8767/stats',timeout=1).close();break
   except OSError:time.sleep(.1)
  native('linksMigrationAndBackup');native('boundedLoadingAndCancellation')
  if a.real_providers:native('realProviderSamples')
  native('seed')
  playlist=str(uuid.UUID(bytes=hashlib.md5(b'e2e-playlist').digest(),version=3))
  cmd=['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device','emulator-5556','--format','JUNIT','--output',str(out/'logos.xml'),'--test-output-dir',str(out/'maestro')]
  for i in range(1,8):cmd+=['-e',f'C{i}='+playlist+':'+hashlib.sha256(f'id:test{i}\x000'.encode()).hexdigest()]
  cmd+=[str(project/'e2e/maestro/channel-logos.yaml')]
  r=subprocess.run(cmd,capture_output=True,timeout=240);(out/'maestro.log').write_bytes(r.stdout+r.stderr)
  results.append({'case':'maestro-logos','passed':r.returncode==0});(out/'results.json').write_text(json.dumps(results,indent=2));print('Maestro',r.returncode,flush=True)
  (out/'http-stats.json').write_bytes(urllib.request.urlopen('http://127.0.0.1:8767/stats').read());assert r.returncode==0
 finally:server.terminate();server.wait(timeout=5)
