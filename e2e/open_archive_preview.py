#!/usr/bin/env python3
"""Open the HUD review on the project emulator only; uses synthetic data and a local recording."""
from pathlib import Path
import subprocess,time,argparse
p=argparse.ArgumentParser();p.add_argument('--font',type=int,choices=[60,100,130],default=100);a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2]
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556']
subprocess.run(['python3',str(project/'e2e/open_emulator.py')],check=True)
subprocess.run(adb+['wait-for-device'],check=True,timeout=60)
video=root/'work/archive-hud/archive-preview.mp4'
if not video.exists():raise SystemExit(f'Missing synthetic video fixture: {video}. Create a synthetic MP4 here before opening the preview.')
subprocess.run(adb+['shell','am','force-stop','tv.tvwave.app.debug'],check=True)
subprocess.run(adb+['push',str(video),'/data/local/tmp/tvwave-archive-preview.mp4'],check=True)
subprocess.run(adb+['shell','run-as','tv.tvwave.app.debug','cp','/data/local/tmp/tvwave-archive-preview.mp4','files/archive-preview.mp4'],check=True)
result=subprocess.run(adb+['shell','am','instrument','-w','-e','class','tv.tvwave.app.ArchiveHudTest#seedPreview','-e','font',str(a.font),'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,check=True)
if 'OK (1 test)' not in result.stdout:raise SystemExit(result.stdout)
subprocess.run(adb+['shell','am','start','-n','tv.tvwave.app.debug/tv.tvwave.app.MainActivity'],check=True)
def hierarchy():
 subprocess.run(adb+['shell','uiautomator','dump','/sdcard/tvwave-preview-window.xml'],capture_output=True)
 result=subprocess.run(adb+['shell','cat','/sdcard/tvwave-preview-window.xml'],capture_output=True)
 return result.stdout.decode('utf-8','replace') if result.returncode==0 else ''
for attempt in range(10):
 if 'Передача 0' in hierarchy():break
 time.sleep(.4)
else:raise SystemExit('EPG did not load; preview not opened.')
# Enter remote focus, open details first, then select the previous programme.
for key in [20,19,22,23]:
 subprocess.run(adb+['shell','input','keyevent',str(key)],check=True);time.sleep(.4)
if 'programme-details' not in hierarchy():raise SystemExit('Programme details did not open.')
for key in [21,23]:
 subprocess.run(adb+['shell','input','keyevent',str(key)],check=True);time.sleep(.5)
time.sleep(1)
subprocess.run(adb+['shell','input','keyevent','127'],check=True)
if 'Запись' not in hierarchy():raise SystemExit('Archive preview did not start.')
print('Archive HUD preview is open and paused on emulator-5556. No physical TVs contacted.')
