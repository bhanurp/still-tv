#!/usr/bin/env python3
"""Snapshot or restore the original launcher. Never disables a package."""
import argparse
import json
from pathlib import Path
import re
import subprocess

PACKAGE='dev.still.launcher'

def normalized(component):
    pkg, activity=component.split('/',1)
    return pkg+'/'+(pkg+activity if activity.startswith('.') else activity)

def original_component(component):
    if not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+',component):
        raise ValueError('Invalid original Home component')
    if component.split('/')[0]==PACKAGE:
        raise ValueError('Still TV cannot be its own recovery launcher')
    return component

def invoke(adb,device,args,runner=subprocess.run):
    result=runner([adb,'-s',device,'shell']+args,check=True,capture_output=True,text=True,timeout=20)
    if re.search(r'(^|\n)\s*(Error:|Exception|Failure)',result.stdout+'\n'+result.stderr,re.I):
        raise RuntimeError((result.stdout+'\n'+result.stderr).strip())
    return result.stdout

def home(adb,device,user,runner=subprocess.run):
    result=invoke(adb,device,['cmd','package','resolve-activity','--brief','--user',str(user),'-a','android.intent.action.MAIN','-c','android.intent.category.HOME'],runner)
    return next((line.strip() for line in result.splitlines() if re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+',line.strip())),None)

def enabled(adb,device,component,user,runner=subprocess.run):
    pkg=component.split('/')[0]
    result=invoke(adb,device,['pm','list','packages','--user',str(user),'-e',pkg],runner)
    return 'package:'+pkg in result.splitlines()

def restore(adb,device,original,user=0,uninstall=False,runner=subprocess.run):
    original_component(original)
    if not enabled(adb,device,original,user,runner):
        raise RuntimeError('Original launcher is missing or disabled; no changes made')
    invoke(adb,device,['cmd','package','set-home-activity','--user',str(user),original],runner)
    resolved=home(adb,device,user,runner)
    if not resolved or normalized(resolved)!=normalized(original):
        raise RuntimeError('Original Home default did not verify; Still TV was not removed')
    launch=invoke(adb,device,['am','start','-W','--user',str(user),'-n',original,'-a','android.intent.action.MAIN','-c','android.intent.category.HOME'],runner)
    if not re.search(r'^Status:\s*ok\s*$',launch,re.M):
        raise RuntimeError('Original Home launch did not complete; Still TV was not stopped or removed')
    activities=invoke(adb,device,['dumpsys','activity','activities'],runner)
    resumed=re.findall(r'mResumedActivity:.*?\s([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)(?:\s|\})',activities)
    if not any(normalized(component)==normalized(original) for component in resumed):
        raise RuntimeError('Original Home is not in the foreground; Still TV was not stopped or removed')
    invoke(adb,device,['am','force-stop','--user',str(user),PACKAGE],runner)
    if uninstall:
        result=invoke(adb,device,['pm','uninstall','--user',str(user),PACKAGE],runner)
        if 'Success' not in result:
            raise RuntimeError('Original Home restored, but Still TV removal did not report success')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['snapshot','restore'])
    parser.add_argument('--adb',default='adb')
    parser.add_argument('--device',help='Connected ADB serial/address; required for snapshot')
    parser.add_argument('--user',type=int,default=0)
    parser.add_argument('--state',type=Path,default=Path('installation-state.json'))
    parser.add_argument('--uninstall',action='store_true',help='After verified restore, remove only Still TV and its data for this user')
    parser.add_argument('--dry-run',action='store_true')
    args=parser.parse_args()
    if args.user<0: parser.error('--user must be nonnegative')
    if args.action=='snapshot':
        if not args.device: parser.error('snapshot requires --device')
        if args.state.exists(): parser.error('State file already exists; preserve the original snapshot')
        original=home(args.adb,args.device,args.user)
        if not original: parser.error('No original Home resolved')
        original_component(original)
        if not enabled(args.adb,args.device,original,args.user): parser.error('Original Home is not enabled')
        args.state.write_text(json.dumps({'device':args.device,'original':original,'user':args.user},indent=2)+'\n')
        args.state.chmod(0o600)
        print('Original Home saved. Keep this local state file; it is excluded from Git.')
    else:
        state=json.loads(args.state.read_text())
        original_component(state['original'])
        if args.dry_run:
            print('Check original launcher enabled → restore default → verify default → open original Home → stop Still TV'+(' → uninstall only Still TV' if args.uninstall else ''))
            return
        restore(args.adb,args.device or state['device'],state['original'],state['user'],args.uninstall)
        print('Original Home restored and opened. Still TV '+('removed.' if args.uninstall else 'stopped; its preferences are preserved.'))

if __name__=='__main__': main()
