#!/usr/bin/env python3
"""Open the project's Android TV window. Does not connect to any network TV."""
from pathlib import Path
import subprocess,os,urllib.request
project=Path(__file__).resolve().parents[1];root=project.parents[2];base=root/'work/android-tools';env=os.environ.copy()
env.update(ANDROID_HOME=str(base/'sdk'),ANDROID_AVD_HOME=str(base/'avd'),ANDROID_USER_HOME=str(base/'android-user'),ANDROID_EMULATOR_HOME=str(base/'android-user'))
try:
 urllib.request.urlopen('http://127.0.0.1:8765/playlist.m3u8',timeout=1).close()
except Exception:
 serverlog=root/'work/qa-emulator/fixture-server.log';serverlog.parent.mkdir(parents=True,exist_ok=True)
 with serverlog.open('ab') as f:subprocess.Popen(['python3',str(project/'e2e/fixture_server.py')],stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
adb=base/'sdk/platform-tools/adb' 
r=subprocess.run([str(adb),'-s','emulator-5556','get-state'],capture_output=True,text=True)
if r.returncode==0 and r.stdout.strip()=='device':
 print('Android TV is already running (emulator-5556). Open its Emulator window.')
else:
 log=root/'work/qa-emulator/emulator.log';log.parent.mkdir(parents=True,exist_ok=True)
 with log.open('ab') as f:
  process=subprocess.Popen([str(base/'sdk/emulator/emulator'),'-avd','TVWave_API34','-port','5556','-no-snapshot','-no-boot-anim','-no-audio','-gpu','host','-scale','0.6'],env=env,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
 print('Android TV window starting. PID:',process.pid)
