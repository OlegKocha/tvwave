#!/usr/bin/env python3
"""Opt-in stress/focus checks, invoked only by the full Maestro suite."""
from pathlib import Path
import subprocess,argparse,json,time
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--full-suite',action='store_true');a=p.parse_args()
if not a.full_suite:p.error('Run through run_full_maestro.py; not part of targeted checks')
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
def shell(*args):return subprocess.run(adb+list(args),check=True,capture_output=True)
def flow(name,label=None):
 label=label or name
 r=subprocess.run(['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device','emulator-5556','--format','JUNIT','--output',str(out/(label+'.xml')),'--test-output-dir',str(out/label),str(project/'e2e/maestro/reliability'/(name+'.yaml'))],capture_output=True,timeout=240)
 (out/(label+'.log')).write_bytes(r.stdout+r.stderr);results.append({'case':label,'passed':r.returncode==0});(out/'results.json').write_text(json.dumps(results,indent=2));assert r.returncode==0,label
shell('shell','am','force-stop','tv.tvwave.app.debug')
r=shell('shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#seedForMaestro','-e','sources','10','-e','font','130','tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner');assert b'OK (1 test)' in r.stdout
flow('open')
for n in range(3):
 shell('shell','input','keyevent',*(['20']*12));flow('footer',f'burst-{n}-down')
 shell('shell','input','keyevent',*(['19']*12));flow('top',f'burst-{n}-up')
flow('keyboard')
shell('shell','input','keyevent','223');time.sleep(1);shell('shell','input','keyevent','224');shell('shell','wm','dismiss-keyguard');flow('top','wake-focus')
flow('update')
