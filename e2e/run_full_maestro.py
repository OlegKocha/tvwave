#!/usr/bin/env python3
"""All maintained Maestro groups, sequentially on emulator-5556 only."""
from pathlib import Path
import argparse,subprocess,json
p=argparse.ArgumentParser();p.add_argument('--output',required=True);args=p.parse_args()
project=Path(__file__).resolve().parents[1];out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True);results=[]
for name,script,options in [('core','run_emulator_tests.py',['--mode','maestro']),('programmes','run_programme_tests.py',[]),('extended','run_extended_tests.py',[]),('network','run_network_tests.py',[]),('epg-management','run_epg_management_tests.py',[]),('remote-reliability','run_remote_reliability.py',['--full-suite']),('menu-rail','run_menu_rail_tests.py',[]),('channel-logos','run_logo_tests.py',[])]:
 with (out/(name+'.log')).open('w') as log:
  r=subprocess.run(['python3',str(project/'e2e'/script),'--output',str(out/name)]+options,stdout=log,stderr=subprocess.STDOUT,timeout=3600)
 results.append({'group':name,'passed':r.returncode==0});(out/'groups.json').write_text(json.dumps(results,indent=2));print(name,r.returncode,flush=True)
raise SystemExit(0 if all(r['passed'] for r in results) else 1)
