import subprocess
import unittest
from tools.recover import restore

ORIGINAL = 'com.vendor.home/.Home'

class RecoveryTest(unittest.TestCase):
    def runner(self, replies):
        self.commands = []
        def run(args, **kwargs):
            self.commands.append(args)
            reply = replies.pop(0) if replies else ''
            if isinstance(reply, Exception):
                raise reply
            return subprocess.CompletedProcess(args, 0, reply, '')
        return run

    def test_restores_and_verifies_before_uninstall(self):
        run = self.runner(['package:com.vendor.home\n','Success\n',ORIGINAL+'\n','Status: ok\n','mResumedActivity: ActivityRecord{123 u0 '+ORIGINAL+' t1}\n','','Success\n'])
        restore('adb','test-device',ORIGINAL,0,True,run)
        flattened = [' '.join(c) for c in self.commands]
        self.assertIn('set-home-activity',flattened[1])
        self.assertIn('resolve-activity',flattened[2])
        self.assertIn('am start',flattened[3])
        self.assertIn('dumpsys activity activities',flattened[4])
        self.assertIn('force-stop',flattened[5])
        self.assertIn('uninstall --user 0 dev.still.launcher',flattened[6])
        self.assertFalse(any('disable' in c for c in flattened))

    def test_default_restore_failure_never_uninstalls(self):
        run=self.runner(['package:com.vendor.home\n',subprocess.CalledProcessError(1,'adb')])
        with self.assertRaises(subprocess.CalledProcessError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),2)

    def test_wrong_home_readback_stops_recovery(self):
        run=self.runner(['package:com.vendor.home\n','Success\n','dev.still.launcher/.MainActivity\n'])
        with self.assertRaises(RuntimeError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),3)

    def test_disabled_original_is_enabled_before_restoration(self):
        run=self.runner(['','package:com.vendor.home\n','Package new state: enabled\n',
            'package:com.vendor.home\n','Success\n',ORIGINAL+'\n','Status: ok\n',
            'mResumedActivity: ActivityRecord{123 u0 '+ORIGINAL+' t1}\n',''])
        restore('adb','test-device',ORIGINAL,0,False,run)
        commands=[' '.join(c) for c in self.commands]
        self.assertIn('pm enable --user 0 com.vendor.home', commands[2])
        self.assertIn('set-home-activity',commands[4])
        self.assertIn('force-stop',commands[-1])
        self.assertFalse(any('disable' in c or 'uninstall' in c for c in commands))

    def test_missing_original_stops_without_changes(self):
        run=self.runner(['',''])
        with self.assertRaises(RuntimeError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),2)

    def test_original_enable_failure_never_stops_or_uninstalls(self):
        run=self.runner(['','package:com.vendor.home\n',subprocess.CalledProcessError(1,'adb')])
        with self.assertRaises(subprocess.CalledProcessError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),3)

    def test_original_enable_readback_failure_stops(self):
        run=self.runner(['','package:com.vendor.home\n','Package new state: enabled\n',''])
        with self.assertRaises(RuntimeError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),4)

    def test_custom_launcher_cannot_be_original(self):
        run=self.runner([])
        with self.assertRaises(ValueError): restore('adb','test-device','dev.still.launcher/.MainActivity',0,True,run)
        self.assertFalse(self.commands)

    def test_default_recovery_preserves_app_and_settings(self):
        run=self.runner(['package:com.vendor.home\n','Success\n',ORIGINAL+'\n','Status: ok\n','mResumedActivity: ActivityRecord{123 u0 '+ORIGINAL+' t1}\n',''])
        restore('adb','test-device',ORIGINAL,0,False,run)
        self.assertFalse(any('uninstall' in c for c in self.commands))

    def test_launch_timeout_never_stops_or_uninstalls(self):
        run=self.runner(['package:com.vendor.home\n','Success\n',ORIGINAL+'\n','Status: timeout\nComplete\n'])
        with self.assertRaises(RuntimeError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),4)

    def test_wrong_foreground_never_stops_or_uninstalls(self):
        run=self.runner(['package:com.vendor.home\n','Success\n',ORIGINAL+'\n','Status: ok\n','mResumedActivity: ActivityRecord{123 u0 dev.still.launcher/.MainActivity t1}\n'])
        with self.assertRaises(RuntimeError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(self.commands),5)

    def test_stderr_error_never_changes_default(self):
        commands=[]
        def run(args,**kwargs):
            commands.append(args)
            return subprocess.CompletedProcess(args,0,'package:com.vendor.home\n','Error: denied')
        with self.assertRaises(RuntimeError): restore('adb','test-device',ORIGINAL,0,True,run)
        self.assertEqual(len(commands),1)

if __name__=='__main__': unittest.main()
