#!/usr/bin/env python3
"""Targeted EPG/portable backup QA; emulator and synthetic data only."""
from pathlib import Path
import argparse,subprocess,json,gzip,hashlib,struct
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--flow',choices=['all','management','assignment','backup','maintenance'],default='all');a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=project.parents[2];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
adb=[str(root/'work/android-tools/sdk/platform-tools/adb'),'-s','emulator-5556'];results=[]
def shell(*args):return subprocess.run(adb+list(args),check=True,capture_output=True)
def native(name,test_class='EpgSettingsTest'):
 shell('shell','am','force-stop','tv.tvwave.app.debug')
 r=shell('shell','am','instrument','-w','-e','class','tv.tvwave.app.'+test_class+'#'+name,'tv.tvwave.app.debug.test/androidx.test.runner.AndroidJUnitRunner')
 (out/(name+'.log')).write_bytes(r.stdout+r.stderr);assert b'OK (1 test)' in r.stdout,r.stdout.decode()
 print(name,'PASS',flush=True)
def flow(name,file=None,env=None):
 cmd=['python3',str(root/'work/qa-emulator/maestro.py'),'test','--device','emulator-5556','--format','JUNIT','--output',str(out/(name+'.xml')),'--test-output-dir',str(out/name)]
 for k,v in (env or {}).items():cmd+=['-e',k+'='+v]
 cmd+=[str(project/'e2e/maestro'/(file or 'epg-management/'+name+'.yaml'))]
 r=subprocess.run(cmd,capture_output=True,timeout=240);(out/(name+'.log')).write_bytes(r.stdout+r.stderr)
 results.append({'case':name,'passed':r.returncode==0});(out/'results.json').write_text(json.dumps(results,indent=2));print(name,r.returncode,flush=True)
 assert r.returncode==0
if a.flow in ['all','management','maintenance']:
 for name in ['weeklyCleanupPersistsAndPreservesConfigurationCataloguesAndOverlap','failedCleanupRollsBackAndIsRetried','manualTimeCorrectionPreservesTheDisplayedBoundary','compactionReturnsRealDiskSpaceAndSkipsLowStorage','onlyAbandonedKnownTemporaryFilesAreRemoved','upgradeV5PreservesSettingsAndBackup']:
  native(name,'CacheMaintenanceTest')
if a.flow in ['all','management']:
 for name in ['zappingOnlyWritesSmallEncryptedHistoryAndBackupRestoresIt','fullSaveRollsBackBothConfigurationAndViewing','migrationFromV6KeepsInlineHistoryUntilFirstViewingWrite','summaryIsLightweightAndTracksMidnightCleanupAndChangedUrl','repositoryScheduleCacheInvalidatesForImportBindingOffsetCleanupAndRestore']:
  native(name,'PerformanceStorageTest')
 native('shiftedScheduleUsesCorrectWindowAndPriority')
 native('seedManagement');flow('refresh-one');native('assertOnlyOneSourceUpdated')
 native('seedProgress');flow('progress')
 native('seedManagement');flow('reorder');native('assertReordered')
 native('seedTen');flow('reorder-ten');native('assertTenReordered')
 native('seedManagement');flow('assignment');native('assertAssignmentAndOffset')
if a.flow=='assignment':
 native('seedManagement');flow('assignment');native('assertAssignmentAndOffset')
if a.flow in ['all','backup']:
 native('seedMultipleForBackup')
 shell('shell','mkdir','-p','/sdcard/Download/TVWave')
 before=set(shell('shell','ls','/sdcard/Download/TVWave').stdout.decode().splitlines())
 flow('export','backup-export.yaml')
 names=set(shell('shell','ls','/sdcard/Download/TVWave').stdout.decode().splitlines())-before
 exported=[n for n in names if n.startswith('TVWave-') and n.endswith('.tvwavebackup')]
 assert len(exported)==1,exported
 name=exported[0]
 backup=out/'multi.tvwavebackup';shell('pull','/sdcard/Download/TVWave/'+name,str(backup));content=backup.read_bytes()
 assert content[:8]==b'TVWAVBK3';version,length=struct.unpack('>II',content[8:16]);assert version==3 and length==len(content)-48
 assert hashlib.sha256(content[48:]).digest()==content[16:48]
 config=json.loads(gzip.decompress(content[48:]))
 assert len(config['playlists'])==3 and len(config['epgSources'])==3 and config['fontPercent']==85
 assert [s['name'] for s in config['epgSources']]==['EPG 03','EPG 01','EPG 02']
 assert len(config['playlistEpgSources'])==1 and len(config['epgOffsets'])==2 and len(config['epgBindings'])==1
 assert config['audioLanguage']=='en' and config['subtitles'] and config['epgIntervalHours']==6
 assert len(config['favourites'])==2 and len(config['history'])==2 and config['playlists'][1]['source']==''
 assert not any(k in config for k in ['epgBackup','programmes','epgCache']) and len(content)<10000
 assert 'Описание передачи' not in json.dumps(config,ensure_ascii=False)
 broken=bytearray(content);broken[-1]^=1;damaged=out/'qa-corrupt.tvwavebackup';damaged.write_bytes(broken)
 shell('push',str(damaged),'/sdcard/Download/TVWave/qa-corrupt.tvwavebackup')
 (out/'backup-check.json').write_text(json.dumps({'bytes':len(content),'playlists':3,'epgSources':3,'fontPercent':85,'programmeCache':False,'checksumValid':True,'playlistAssignments':1,'channelOffsets':2,'manualBindings':1},indent=2))
 # Real reinstall. The exported file stays in shared Downloads; no staging/copy into app-private storage.
 shell('uninstall','tv.tvwave.app.debug');shell('install',str(project/'app/build/outputs/apk/debug/app-debug.apk'))
 assert name in shell('shell','ls','/sdcard/Download/TVWave').stdout.decode()
 shell('shell','appops','set','tv.tvwave.app.debug','MANAGE_EXTERNAL_STORAGE','allow')
 flow('restore-empty',env={'BACKUP_NAME':name});native('assertRestoredConfiguration');flow('restore-use')
print('Targeted EPG/backup tests passed',flush=True)
