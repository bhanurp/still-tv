package dev.still.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/** One boot event, one launch attempt. No service, timer, or overlay window. */
public final class BootStartup extends BroadcastReceiver {
  static final String ENABLED = "start_on_boot";

  static boolean canLaunch(Context context) {
    if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)) return true;
    ResolveInfo home =
        context
            .getPackageManager()
            .resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
    return home != null
        && home.activityInfo != null
        && context.getPackageName().equals(home.activityInfo.packageName);
  }

  static String status(Context context, SharedPreferences prefs) {
    if (!prefs.getBoolean(ENABLED, false)) return "Off";
    return canLaunch(context) ? "On" : "Permission needed";
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
    SharedPreferences prefs = context.getSharedPreferences("still", Context.MODE_PRIVATE);
    if (!LauncherState.shouldStartOnBoot(
        prefs.getBoolean(ENABLED, false),
        prefs.getBoolean("safe_mode", false),
        canLaunch(context))) {
      Log.i("StillTVBoot", "Startup skipped: disabled, recovery mode, or access missing");
      return;
    }
    try {
      context.startActivity(
          new Intent(context, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
      Log.i("StillTVBoot", "Boot startup requested");
    } catch (RuntimeException error) {
      Log.w("StillTVBoot", "Boot startup could not open Still TV", error);
    }
  }
}
