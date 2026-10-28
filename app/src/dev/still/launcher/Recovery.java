package dev.still.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** Preserves the original Home and contains all best-effort crash forwarding. */
final class Recovery {
  private static boolean installed;
  private final Context context;
  private final SharedPreferences prefs;

  Recovery(Context context, SharedPreferences prefs) {
    this.context = context.getApplicationContext();
    this.prefs = prefs;
    rememberHome();
    installHandler(this.context, prefs);
  }

  boolean isSafeMode() {
    return prefs.getBoolean("safe_mode", false);
  }

  void retry() {
    prefs.edit().putBoolean("safe_mode", false).commit();
  }

  boolean openHome() {
    return openHome(context, prefs);
  }

  private void rememberHome() {
    if (prefs.contains("original_home")) return;
    PackageManager pm = context.getPackageManager();
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    ResolveInfo preferred = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
    if (usable(preferred)) save(preferred);
    else
      for (ResolveInfo info : pm.queryIntentActivities(home, 0))
        if (usable(info)) {
          save(info);
          break;
        }
  }

  private boolean usable(ResolveInfo info) {
    return info != null
        && info.activityInfo != null
        && !context.getPackageName().equals(info.activityInfo.packageName)
        && !"android".equals(info.activityInfo.packageName)
        && !info.activityInfo.name.contains("FallbackHome");
  }

  private void save(ResolveInfo info) {
    prefs
        .edit()
        .putString(
            "original_home",
            new ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                .flattenToString())
        .commit();
  }

  private static synchronized void installHandler(Context context, SharedPreferences prefs) {
    if (installed) return;
    installed = true;
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            prefs.edit().putBoolean("safe_mode", true).commit();
            openHome(context, prefs);
          } catch (Throwable ignored) {
          }
          if (previous != null) previous.uncaughtException(thread, error);
          else android.os.Process.killProcess(android.os.Process.myPid());
        });
  }

  private static boolean openHome(Context context, SharedPreferences prefs) {
    String saved = prefs.getString("original_home", null);
    ComponentName home = saved == null ? null : ComponentName.unflattenFromString(saved);
    if (home == null || home.getPackageName().equals(context.getPackageName())) return false;
    try {
      context.startActivity(
          new Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_HOME)
              .setComponent(home)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }
}
