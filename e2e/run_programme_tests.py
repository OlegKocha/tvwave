#!/usr/bin/env python3
"""Only the new programme-navigation flows; never runs the regression suite."""
from pathlib import Path
from datetime import datetime,timedelta,timezone
import subprocess,argparse,json,re,time
p=argparse.ArgumentParser();p.add_argument('--device',default='emulator-5556');p.add_argument('--output',required=True);p.add_argument('--flow',choices=['all','time-navigation','browser','details','hud'],default='all');p.add_argument('--font',type=int,choices=[60,100,130],help='Run only an existing case at this font size');args=p.parse_args()
if not re.fullmatch(r'emulator-\d+',args.device):p.error('Only an explicit emulator serial is allowed')
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s',args.device];results=[]
cases=[('time-navigation',100),('browser',100),('details',100),('details',130),('hud',100),('hud',130),('details',60),('hud',60)]
for flow,font in cases:
 if args.flow!='all' and flow!=args.flow:continue
 if args.font is not None and font!=args.font:continue
 # Hourly fixture titles must remain stable through a flow, including at midnight.
 while True:
  clock=datetime.now(timezone(timedelta(hours=3)));remaining=3600-clock.minute*60-clock.second
  if remaining>180:break
  print(f'Waiting for the next fixture hour: {remaining}s',flush=True);time.sleep(min(30,remaining+1))
 name=f'{flow}-{font}';subprocess.run(adb+['shell','am','force-stop','tv.tvwave.app.debug'],capture_output=True,check=True)
 if flow=='hud':
  native=subprocess.run(adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.HudNavigationTest','-e','font',str(font),'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=90)
  (out/(name+'-playback-native.log')).write_text(native.stdout+native.stderr)
  if 'OK (1 test)' not in native.stdout:raise RuntimeError('Playback HUD focus/timer failed')
  subprocess.run(adb+['shell','am','force-stop','tv.tvwave.app.debug'],capture_output=True,check=True)
 seed=subprocess.run(adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#seedForMaestro','-e','sources','1','-e','font',str(font),'-e','rich','true','tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=60)
 (out/(name+'-seed.log')).write_text(seed.stdout+seed.stderr)
 if 'OK (1 test)' not in seed.stdout:raise RuntimeError('Fixture preparation failed')
 now=datetime.now(timezone(timedelta(hours=3)));future=47-now.hour
 cmd=['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device',args.device,'--format','JUNIT','--output',str(out/(name+'.xml')),'--test-output-dir',str(out/name),'-e',f'FUTURE_STEPS={future}','-e',f'EARLY_STEPS={future-19}',str(project/'e2e/maestro/programmes'/(flow+'.yaml'))]
 r=subprocess.run(cmd,capture_output=True,text=True,timeout=300,cwd=project);(out/(name+'.log')).write_text(r.stdout+r.stderr)
 results.append({'flow':flow,'font':font,'passed':r.returncode==0,'future_steps':future});(out/'results.json').write_text(json.dumps(results,indent=2));print(name,'PASS' if r.returncode==0 else 'FAIL',flush=True)
raise SystemExit(0 if all(r['passed'] for r in results) else 1)
