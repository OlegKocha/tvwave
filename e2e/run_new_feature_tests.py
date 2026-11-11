#!/usr/bin/env python3
"""Regression checks for archive loading, EPG search and empty-app restore."""
from pathlib import Path
import argparse,subprocess,json
p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
for name in ['EpgSearchTest','ArchiveLoadingTest','RestoreEntryTest','GroupLabelWrappingTest','ProgrammeRemindersTest']:
 subprocess.run(adb+['shell','am','force-stop','tv.tvwave.app.debug'],check=True,capture_output=True)
 r=subprocess.run(adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.'+name,'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=180)
 (out/(name+'.log')).write_bytes(r.stdout+r.stderr)
 ok=r.returncode==0 and b'OK (' in r.stdout and b'FAILURES' not in r.stdout
 results.append({'case':name,'passed':ok});print(name,ok,flush=True)
 (out/'results.json').write_text(json.dumps(results,indent=2))
raise SystemExit(0 if all(x['passed'] for x in results) else 1)
