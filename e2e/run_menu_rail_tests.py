#!/usr/bin/env python3
"""Targeted compact/expanded TV menu checks on the emulator only."""
from pathlib import Path
import argparse,subprocess,json
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--font',type=int,choices=[60,100,130]);a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
for font in ([a.font] if a.font else [100,60,130]):
 subprocess.run(adb+['shell','am','force-stop','tv.tvwave.app.debug'],check=True,capture_output=True)
 seed=subprocess.run(adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.TvNavigationTest#seedForMaestro','-e','font',str(font),'-e','rich','true','tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner'],check=True,capture_output=True)
 (out/f'seed-{font}.log').write_bytes(seed.stdout+seed.stderr);assert b'OK (1 test)' in seed.stdout
 cmd=['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device','emulator-5556','--format','JUNIT','--output',str(out/f'menu-{font}.xml'),'--test-output-dir',str(out/f'menu-{font}'),str(project/'e2e/maestro/menu-rail.yaml')]
 r=subprocess.run(cmd,capture_output=True,timeout=240);(out/f'menu-{font}.log').write_bytes(r.stdout+r.stderr)
 results.append({'font':font,'passed':r.returncode==0});(out/'results.json').write_text(json.dumps(results,indent=2));print(font,r.returncode,flush=True)
 if r.returncode:raise SystemExit(1)
