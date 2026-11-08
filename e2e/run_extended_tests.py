#!/usr/bin/env python3
from pathlib import Path
import argparse,subprocess,json,urllib.request,gzip,hashlib,struct,base64
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--flow',default='all');p.add_argument('--font',type=int,default=100);args=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
def shell(*a):return subprocess.run(adb+list(a),capture_output=True,check=True)
def stage_import(name,data):
 r=shell('shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#stageImportForMaestro','-e','name',name,'-e','content',base64.b64encode(data).decode(),'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner')
 (out/('stage-'+name+'.log')).write_bytes(r.stdout+r.stderr);assert b'OK (1 test)' in r.stdout
def run(flow,name):
 cmd=['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device','emulator-5556','--format','JUNIT','--output',str(out/(name+'.xml')),'--test-output-dir',str(out/name),str(project/'e2e/maestro'/flow)]
 r=subprocess.run(cmd,capture_output=True,timeout=240);(out/(name+'.log')).write_bytes(r.stdout+r.stderr);results.append({'case':name,'passed':r.returncode==0});(out/'results.json').write_text(json.dumps(results,indent=2));print(name,r.returncode,flush=True);return r.returncode==0
for flow in ['playlists','settings','epg-mapping','channels','files','restore']:
 if args.flow not in ['all',flow]:continue
 shell('shell','am','force-stop','tv.tvwave.app.debug')
 r=shell('shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#seedForMaestro','-e','rich','true','-e','font','60' if flow=='restore' else str(args.font),'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner');assert b'OK (1 test)' in r.stdout
 if flow=='files':
  shell('shell','appops','set','tv.tvwave.app.debug','MANAGE_EXTERNAL_STORAGE','allow')
  for name,data in [('qa-bad.m3u8',b'invalid'),('qa-good.m3u8',urllib.request.urlopen('http://127.0.0.1:8765/playlist.m3u8').read())]:
   f=out/name;f.write_bytes(data);shell('push',str(f),'/sdcard/Download/'+name)
 if flow=='restore':
  shell('shell','mkdir','-p','/sdcard/Download/TVWave')
  before=set(shell('shell','ls','/sdcard/Download/TVWave').stdout.decode().splitlines())
  if not run('backup-export.yaml','export-before-restore'):continue
  names=set(shell('shell','ls','/sdcard/Download/TVWave').stdout.decode().splitlines())-before
  exported=[n for n in names if n.startswith('TVWave-') and n.endswith('.tvwb')]
  assert len(exported)==1,exported
  name=exported[0]
  backup=out/'qa-export.tvwavebackup';shell('pull','/sdcard/Download/TVWave/'+name,str(backup))
  content=backup.read_bytes();assert content[:8]==b'TVWAVBK3'
  version,length=struct.unpack('>II',content[8:16]);assert version==3 and length==len(content)-48
  assert hashlib.sha256(content[48:]).digest()==content[16:48]
  config=json.loads(gzip.decompress(content[48:]));assert config['fontPercent']==60 and len(config['playlists'])==1 and len(config['epgSources'])==1
  assert not any(k in config for k in ['epgBackup','programmes','epgCache']) and len(content)<10000
  (out/'backup-check.json').write_text(json.dumps({'bytes':len(content),'fontPercent':60,'playlists':1,'epgSources':1,'programmeCache':False,'checksumValid':True},indent=2))
  shell('uninstall','tv.tvwave.app.debug');shell('install',str(project/'app/build/outputs/apk/debug/app-debug.apk'))
  shell('shell','appops','set','tv.tvwave.app.debug','MANAGE_EXTERNAL_STORAGE','allow')
  shell('push',str(backup),'/sdcard/Download/qa-export.tvwavebackup')
 ok=run('extended/'+flow+'.yaml',flow)
 if flow=='channels' and ok:
  shell('shell','input','keyevent','--longpress','23')
  run('extended/search-delete.yaml','search-delete')
raise SystemExit(0 if all(x['passed'] for x in results) else 1)
