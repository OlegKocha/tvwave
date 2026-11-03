#!/usr/bin/env python3
"""Run D-pad tests on a named emulator only. Refuses physical device serials."""
from pathlib import Path
import argparse,subprocess,time,json,re
p=argparse.ArgumentParser();p.add_argument('--device',default='emulator-5556');p.add_argument('--mode',choices=['native','maestro','all'],default='all');p.add_argument('--cycles',type=int,default=3);p.add_argument('--output',required=True);args=p.parse_args()
if not re.fullmatch(r'emulator-\d+',args.device):p.error('Only explicit emulator serials are permitted')
project=Path(__file__).resolve().parents[1];workspace=project.parents[2];out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(workspace/'work/android-tools/sdk/platform-tools/adb'),'-s',args.device];results=[]
def run(cmd,log=None,timeout=1200):
 start=time.monotonic();r=subprocess.run(cmd,capture_output=True,text=True,timeout=timeout,cwd=project)
 if log:(out/log).write_text(r.stdout+r.stderr)
 return r,round(time.monotonic()-start,2)
def instrument(method,count,large=False,cycles=1,name='seed',font=None):
 run(adb+['shell','am','force-stop','tv.tvwave.app.debug'])
 cmd=adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#'+method,'-e','sources',str(count),'-e','large',str(large).lower(),'-e','cycles',str(cycles),'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner']
 if font is not None:cmd[-1:-1]=['-e','font',str(font)]
 r,t=run(cmd,name+'.log');return r.returncode==0 and bool(re.search(r'OK \(1 test\)',r.stdout)),t
if args.mode in ['native','all']:
 for count in [1,5,10]:
  for large in [False,True]:
   name=f'native-{count}-'+('large' if large else 'normal');ok,t=instrument('epgFooterRoundTrip',count,large,args.cycles,name)
   results.append(dict(case=name,passed=ok,seconds=t,cycles=args.cycles));print(name,'PASS' if ok else 'FAIL',t,flush=True)
   if not ok:
    for suffix in ['png','xml']:run(adb+['pull',f'/sdcard/Android/data/tv.tvwave.app.debug/files/focus-failure.{suffix}',str(out/f'{name}.{suffix}')])
   (out/'results.json').write_text(json.dumps(results,indent=2))
 for name,method,font in [('appearance','appearanceSliderPersistsAndKeepsFocus',100)]+[(f'hud-{n}','hudOccupiesOneThird',n) for n in [60,100,130]]:
  ok,t=instrument(method,1,name=name,font=font)
  results.append(dict(case=name,passed=ok,seconds=t));print(name,'PASS' if ok else 'FAIL',t,flush=True)
  files=['font-60.png','font-100.png','font-130.png'] if name=='appearance' else [name+'.png']
  for f in files:run(adb+['pull','/sdcard/Android/data/tv.tvwave.app.debug/files/'+f,str(out/f)])
  (out/'results.json').write_text(json.dumps(results,indent=2))
if args.mode in ['maestro','all']:
 for flow,count in [('epg-roundtrip',10),('epg-interval',1),('epg-edit-cancel',1),('backup-export',1),('import-playlist',1),('import-playlist-direct',1),('player',1),('appearance',1)]:
  ok,_=instrument('seedForMaestro',count,name='seed-'+flow)
  if not ok:raise RuntimeError('Fixture setup failed')
  cmd=['python3',str(workspace/'work/qa-emulator/maestro.py'),'test','--device',args.device,'--format','JUNIT','--output',str(out/(flow+'.xml')),'--test-output-dir',str(out/flow),'-e','CYCLES='+str(args.cycles),str(project/'e2e/maestro'/(flow+'.yaml'))]
  r,t=run(cmd,flow+'.log',1800);ok=r.returncode==0
  results.append(dict(case='maestro-'+flow,passed=ok,seconds=t));print(flow,'PASS' if ok else 'FAIL',t,flush=True)
  (out/'results.json').write_text(json.dumps(results,indent=2))
raise SystemExit(0 if all(r['passed'] for r in results) else 1)
