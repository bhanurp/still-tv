package dev.still.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/** Preserves the original Home and contains all best-effort crash forwarding. */
final class Recovery {
  private static final int REQUEST_HOME_ROLE = 1001;
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

  /** Removes Still TV from Home resolution, then opens the saved original Home. */
  boolean revertHome() {
    ComponentName original = originalHome(context, prefs);
    if (original == null) return false;
    PackageManager pm = context.getPackageManager();
    ComponentName entry = homeEntry(context);
    boolean previousStartup = prefs.getBoolean(BootStartup.ENABLED, false);
    if (!prefs.edit().putBoolean(BootStartup.ENABLED, false).commit()) return false;
    int previous;
    try {
      previous = pm.getComponentEnabledSetting(entry);
      pm.setComponentEnabledSetting(
          entry, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    } catch (RuntimeException ignored) {
      prefs.edit().putBoolean(BootStartup.ENABLED, previousStartup).commit();
      return false;
    }
    if (original.equals(resolvedHome()) && startHome(context, original)) return true;
    restoreHomeEntry(pm, entry, previous);
    prefs.edit().putBoolean(BootStartup.ENABLED, previousStartup).commit();
    return false;
  }

  /** Returns whether a system picker opened or Still TV already holds the Home role. */
  boolean requestStillHome(Activity activity) {
    PackageManager pm = context.getPackageManager();
    ComponentName entry = homeEntry(context);
    int previous;
    try {
      previous = pm.getComponentEnabledSetting(entry);
      pm.setComponentEnabledSetting(
          entry, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    } catch (RuntimeException ignored) {
      return false;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      try {
        RoleManager roles = context.getSystemService(RoleManager.class);
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
          if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            if (isDefaultHome()) return true;
            restoreHomeEntry(pm, entry, previous);
            return false;
          }
          activity.startActivityForResult(
              roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE);
          return true;
        }
      } catch (RuntimeException ignored) {
        // Some TV builds expose the role without a usable role request activity.
      }
    }
    try {
      activity.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
      return true;
    } catch (RuntimeException ignored) {
      restoreHomeEntry(pm, entry, previous);
      return false;
    }
  }

  boolean isDefaultHome() {
    ComponentName resolved = resolvedHome();
    return resolved != null && context.getPackageName().equals(resolved.getPackageName());
  }

  private ComponentName resolvedHome() {
    ResolveInfo resolved =
        context
            .getPackageManager()
            .resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
    return resolved == null || resolved.activityInfo == null
        ? null
        : new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
  }

  private static ComponentName homeEntry(Context context) {
    return new ComponentName(context.getPackageName(), "dev.still.launcher.HomeEntry");
  }

  private static void restoreHomeEntry(PackageManager pm, ComponentName entry, int previous) {
    try {
      pm.setComponentEnabledSetting(entry, previous, PackageManager.DONT_KILL_APP);
    } catch (RuntimeException ignored) {
      // Recovery must remain usable even if the package manager rejects a rollback.
    }
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
          } catch (Throwable ignored) {
          }
          try {
            context
                .getPackageManager()
                .setComponentEnabledSetting(
                    homeEntry(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
          } catch (Throwable ignored) {
          }
          try {
            openHome(context, prefs);
          } catch (Throwable ignored) {
          }
          if (previous != null) previous.uncaughtException(thread, error);
          else android.os.Process.killProcess(android.os.Process.myPid());
        });
  }

  private static boolean openHome(Context context, SharedPreferences prefs) {
    ComponentName home = originalHome(context, prefs);
    return home != null && startHome(context, home);
  }

  private static ComponentName originalHome(Context context, SharedPreferences prefs) {
    try {
      String saved = prefs.getString("original_home", null);
      ComponentName home = saved == null ? null : ComponentName.unflattenFromString(saved);
      if (home == null || home.getPackageName().equals(context.getPackageName())) return null;
      PackageManager pm = context.getPackageManager();
      ActivityInfo info = pm.getActivityInfo(home, 0);
      if (!info.exported
          || !enabled(pm.getComponentEnabledSetting(home), info.enabled)
          || !enabled(
              pm.getApplicationEnabledSetting(home.getPackageName()), info.applicationInfo.enabled))
        return null;
      return home;
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
      return null;
    }
  }

  private static boolean enabled(int state, boolean manifestEnabled) {
    return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && manifestEnabled);
  }

  private static boolean startHome(Context context, ComponentName home) {
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
