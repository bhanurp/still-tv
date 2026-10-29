package dev.still.launcher.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inspector.WindowInspector;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dependency-free, owner-invoked probe. No preferences, package states, or Home aliases are
 * written.
 */
public final class LauncherProbe extends Instrumentation {
  private final AtomicLong firstAppsNs = new AtomicLong();
  private final AtomicLong firstArtworkNs = new AtomicLong(),
      firstFavoriteArtworkNs = new AtomicLong(),
      allArtworkNs = new AtomicLong();
  private final Set<String> pendingInitialArtwork = new HashSet<>();
  private boolean initialArtworkTracked;
  private int coldFilesDeleted, coldDeleteFailures;
  private final List<JSONObject> callbacks = Collections.synchronizedList(new ArrayList<>());
  private final List<long[]> frames = Collections.synchronizedList(new ArrayList<>());
  private final AtomicLong droppedFrames = new AtomicLong();
  private Set<String> priorArtwork = Collections.emptySet(); // Accessed only on the main thread.
  private Map<String, Object> priorApps = Collections.emptyMap();
  private final Map<String, Integer> diskSites = new HashMap<>();
  private StrictMode.ThreadPolicy originalPolicy;
  private int diskReads, diskWrites;
  private Activity activity;
  private Object catalog;
  private String mode, observerError;
  private long createStartNs, createDurationNs;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    mode = arguments == null ? "audit" : arguments.getString("mode", "audit");
    if (mode.equals("cold-artwork")) {
      File[] files = new File(getTargetContext().getCacheDir(), "artwork").listFiles();
      if (files != null)
        for (File file : files)
          if (file.isFile()) {
            if (file.delete()) coldFilesDeleted++;
            else coldDeleteFailures++;
          }
    }
    start();
  }

  @Override
  public void callActivityOnCreate(Activity target, Bundle state) {
    if (!mode.equals("navigation") && Build.VERSION.SDK_INT >= 28 && originalPolicy == null) {
      originalPolicy = StrictMode.getThreadPolicy();
      StrictMode.setThreadPolicy(
          new StrictMode.ThreadPolicy.Builder(originalPolicy)
              .detectDiskReads()
              .detectDiskWrites()
              .penaltyListener(
                  Runnable::run,
                  violation -> {
                    String kind = violation.getClass().getSimpleName();
                    if (kind.contains("DiskRead")) diskReads++;
                    else if (kind.contains("DiskWrite")) diskWrites++;
                    else return;
                    String site = "framework_or_probe";
                    for (StackTraceElement frame : violation.getStackTrace())
                      if (frame.getClassName().startsWith("dev.still.launcher.")
                          && !frame.getClassName().startsWith("dev.still.launcher.tests.")) {
                        site = frame.getClassName() + "." + frame.getMethodName();
                        break;
                      }
                    String key = kind + ":" + site;
                    diskSites.put(key, diskSites.getOrDefault(key, 0) + 1);
                  })
              .build());
    }
    long started = SystemClock.elapsedRealtimeNanos();
    super.callActivityOnCreate(target, state);
    if (!target.getClass().getName().equals("dev.still.launcher.MainActivity")) return;
    createStartNs = started;
    createDurationNs = SystemClock.elapsedRealtimeNanos() - started;
    try {
      Object targetCatalog = field(target, "catalog");
      if (targetCatalog == null)
        throw new IllegalStateException("Catalog unavailable (safe mode?)");
      Field listenerField = findField(targetCatalog.getClass(), "listener");
      Object original = listenerField.get(targetCatalog);
      Class<?> listener = listenerField.getType();
      Object observer =
          Proxy.newProxyInstance(
              listener.getClassLoader(),
              new Class<?>[] {listener},
              (proxy, method, args) -> {
                if (method.getName().equals("onApps")) {
                  long now = SystemClock.elapsedRealtimeNanos();
                  firstAppsNs.compareAndSet(0, now);
                  List<?> incoming = (List<?>) args[0];
                  if (!initialArtworkTracked) {
                    initialArtworkTracked = true;
                    for (Object app : incoming)
                      if (field(app, "banner") == null)
                        pendingInitialArtwork.add((String) field(app, "pkg"));
                    if (pendingInitialArtwork.isEmpty()) allArtworkNs.compareAndSet(0, now);
                  }
                  int nullBanners = 0, lostBanners = 0, previousApps = 0, retainedApps = 0;
                  for (Object app : incoming) {
                    Object prior = priorApps.get(field(app, "pkg"));
                    if (prior != null) {
                      previousApps++;
                      if (prior == app) retainedApps++;
                    }
                    if (field(app, "banner") == null) {
                      nullBanners++;
                      if (priorArtwork.contains(field(app, "pkg"))) lostBanners++;
                    }
                  }
                  JSONObject event = new JSONObject();
                  put(event, "at_ms_since_onCreate_start", millis(now - createStartNs));
                  put(event, "apps", incoming.size());
                  put(event, "null_banners", nullBanners);
                  put(event, "existing_banners_turned_null", lostBanners);
                  put(event, "previously_seen_apps", previousApps);
                  put(event, "retained_app_objects", retainedApps);
                  put(event, "all_existing_app_objects_retained", previousApps == retainedApps);
                  callbacks.add(event);
                } else if (method.getName().equals("onArtwork") && args[1] != null) {
                  long now = SystemClock.elapsedRealtimeNanos();
                  firstArtworkNs.compareAndSet(0, now);
                  String pkg = (String) field(args[0], "pkg");
                  if (((List<?>) field(target, "favorites")).contains(pkg))
                    firstFavoriteArtworkNs.compareAndSet(0, now);
                  pendingInitialArtwork.remove(pkg);
                  if (initialArtworkTracked && pendingInitialArtwork.isEmpty())
                    allArtworkNs.compareAndSet(0, now);
                }
                try {
                  method.setAccessible(true);
                  return method.invoke(original, args);
                } catch (InvocationTargetException error) {
                  throw error.getCause();
                }
              });
      listenerField.set(targetCatalog, observer);
    } catch (Exception error) {
      observerError = error.toString();
    }
  }

  @Override
  public void onStart() {
    JSONObject report = new JSONObject();
    put(report, "mode", mode);
    put(report, "clock", "elapsedRealtimeNanos; durations in milliseconds");
    if (mode.equals("cache")) {
      try {
        cacheAudit(report);
      } catch (Throwable error) {
        put(report, "error", error.toString());
      }
      Bundle result = new Bundle();
      result.putString("report", report.toString());
      result.putString("stream", "\nSTILL_TV_PROBE_JSON=" + report + "\n");
      finish(Activity.RESULT_OK, result);
      return;
    }
    try {
      if (!mode.equals("audit") && !mode.equals("navigation") && !mode.equals("cold-artwork"))
        throw new IllegalArgumentException("mode must be audit, cold-artwork, navigation or cache");
      activity =
          startActivitySync(
              new Intent(Intent.ACTION_MAIN)
                  .setComponent(
                      new ComponentName("dev.still.launcher", "dev.still.launcher.MainActivity"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      catalog = field(activity, "catalog");
      put(report, "onCreate_ms", millis(createDurationNs));
      put(report, "observer_error", observerError == null ? JSONObject.NULL : observerError);
      if (catalog == null || observerError != null)
        throw new IllegalStateException("Cannot audit without a catalog observer");
      put(report, "initial_catalog_settled", awaitCatalog());
      put(
          report,
          "initial_catalog_settled_ms",
          millis(SystemClock.elapsedRealtimeNanos() - createStartNs));
      put(report, "cold_artwork_files_deleted", coldFilesDeleted);
      put(report, "cold_artwork_delete_failures", coldDeleteFailures);
      put(report, "first_artwork_ms", relativeTime(firstArtworkNs.get()));
      put(report, "first_favorite_artwork_ms", relativeTime(firstFavoriteArtworkNs.get()));
      put(report, "all_artwork_ms", relativeTime(allArtworkNs.get()));
      put(report, "initial_artwork_still_missing", pendingInitialArtwork.size());
      put(
          report,
          "first_onApps_ms",
          firstAppsNs.get() == 0 ? JSONObject.NULL : millis(firstAppsNs.get() - createStartNs));
      if (!mode.equals("navigation")) {
        audit(report);
        accentAudit(report);
      } else navigation(report);
    } catch (Throwable error) {
      put(report, "error", error.toString());
    }
    try {
      main(
          () -> {
            if (originalPolicy != null) StrictMode.setThreadPolicy(originalPolicy);
          });
      JSONObject disk = new JSONObject();
      put(disk, "supported", Build.VERSION.SDK_INT >= 28);
      put(disk, "enabled", originalPolicy != null);
      put(disk, "main_disk_reads", diskReads);
      put(disk, "main_disk_writes", diskWrites);
      List<Map.Entry<String, Integer>> sites = new ArrayList<>(diskSites.entrySet());
      sites.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
      JSONArray top = new JSONArray();
      for (int i = 0; i < Math.min(3, sites.size()); i++) {
        JSONObject site = new JSONObject();
        put(site, "site", sites.get(i).getKey());
        put(site, "violations", sites.get(i).getValue());
        top.put(site);
      }
      put(disk, "top_sites", top);
      put(
          disk,
          "scope",
          "Main thread onCreate through audit completion; StrictMode observations, not all disk"
              + " syscalls");
      put(report, "strict_mode", disk);
    } catch (Exception error) {
      put(report, "strict_mode_error", error.toString());
    }
    synchronized (callbacks) {
      put(report, "onApps_callbacks", new JSONArray(callbacks));
    }
    Bundle result = new Bundle();
    result.putString("report", report.toString());
    result.putString("stream", "\nSTILL_TV_PROBE_JSON=" + report + "\n");
    finish(
        Activity.RESULT_OK, result); // Inspect booleans/error; baseline failures are report data.
  }

  private void cacheAudit(JSONObject report) throws Exception {
    File directory = new File(getTargetContext().getCacheDir(), "artwork");
    File[] files = directory.listFiles();
    List<File> pngs = new ArrayList<>();
    if (files != null)
      for (File file : files) if (file.isFile() && file.getName().endsWith(".png")) pngs.add(file);
    pngs.sort((a, b) -> a.getName().compareTo(b.getName()));
    PackageManager pm = getTargetContext().getPackageManager();
    Set<String> cached = new HashSet<>();
    Set<String> validKeys = new HashSet<>();
    JSONArray samples = new JSONArray(),
        notInstalled = new JSONArray(),
        lookupErrors = new JSONArray();
    int matching = 0, mismatching = 0;
    int validVersionKeys = 0, legacyPngs = 0, staleVersionKeys = 0;
    long bytes = 0;
    for (File file : pngs) {
      String pkg = file.getName().substring(0, file.getName().length() - 4);
      Long encodedUpdated = null;
      int separator = pkg.lastIndexOf('_');
      if (separator > 0) {
        try {
          String suffix = pkg.substring(separator + 1);
          long parsed = Long.parseLong(suffix);
          if (parsed >= 0 && suffix.matches("[0-9]+")) {
            encodedUpdated = parsed;
            pkg = pkg.substring(0, separator);
          }
        } catch (NumberFormatException ignored) {
          // A package name can contain underscores; only a final long is a version key.
        }
      }
      if (encodedUpdated == null) legacyPngs++;
      cached.add(pkg);
      bytes += file.length();
      JSONObject sample = new JSONObject();
      put(sample, "pkg", pkg);
      put(sample, "encoded_updated", encodedUpdated == null ? JSONObject.NULL : encodedUpdated);
      put(sample, "file_timestamp", file.lastModified());
      put(sample, "bytes", file.length());
      try {
        long expected = pm.getPackageInfo(pkg, 0).lastUpdateTime;
        if (encodedUpdated != null) {
          boolean valid = encodedUpdated.longValue() == expected;
          put(sample, "version_key_valid", valid);
          if (valid) {
            validVersionKeys++;
            validKeys.add(pkg);
          } else staleVersionKeys++;
        }
        long difference = file.lastModified() - expected;
        if (difference == 0) matching++;
        else mismatching++;
        put(sample, "expected", expected);
        put(sample, "difference_ms", difference);
      } catch (PackageManager.NameNotFoundException error) {
        if (encodedUpdated != null) staleVersionKeys++;
        notInstalled.put(pkg);
        put(sample, "installed", false);
      } catch (RuntimeException error) {
        lookupErrors.put(pkg + ": " + error);
      }
      if (samples.length() < 5) samples.put(sample);
    }
    Set<String> missing = new java.util.TreeSet<>();
    Set<String> missingValid = new java.util.TreeSet<>();
    for (ResolveInfo info :
        pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
      if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.exported)
        continue;
      String pkg = info.activityInfo.packageName;
      if (!pkg.equals("dev.still.launcher") && !pkg.equals("com.android.tv.settings")) {
        if (!cached.contains(pkg)) missing.add(pkg);
        if (!validKeys.contains(pkg)) missingValid.add(pkg);
      }
    }
    put(report, "activity_launched", false);
    put(report, "cache_directory_exists", directory.isDirectory());
    put(report, "total_pngs", pngs.size());
    put(report, "matching_timestamps", matching);
    put(report, "mismatching_timestamps", mismatching);
    put(report, "valid_version_keys", validVersionKeys);
    put(report, "legacy_pngs", legacyPngs);
    put(report, "stale_version_keys", staleVersionKeys);
    put(report, "sum_bytes", bytes);
    put(report, "samples", samples);
    put(report, "cached_packages_not_installed", notInstalled);
    put(report, "package_lookup_errors", lookupErrors);
    put(report, "installed_tv_apps_missing_png", new JSONArray(missing));
    put(report, "installed_tv_apps_missing_valid_key", new JSONArray(missingValid));
    put(
        report,
        "scope",
        "Read-only cache PNG metadata and encoded version keys versus PackageInfo.lastUpdateTime;"
            + " difference_ms=file_timestamp-expected; valid_version_keys does not depend on"
            + " mtime");
  }

  private void audit(JSONObject report) {
    JSONArray rounds = new JSONArray();
    for (int round = 1; round <= 3; round++) {
      JSONObject result = new JSONObject();
      put(result, "round", round);
      final View[] selected = new View[1];
      final String[] selectedPackage = new String[1];
      final boolean[] focusAccepted = new boolean[1];
      final int beforeCallbacks = callbacks.size();
      long refreshStartedNs = SystemClock.elapsedRealtimeNanos();
      try {
        main(
            () -> {
              priorArtwork = new HashSet<>();
              priorApps = new HashMap<>();
              for (Object app : (List<?>) field(activity, "apps")) {
                String pkg = (String) field(app, "pkg");
                priorApps.put(pkg, app);
                if (field(app, "banner") != null) priorArtwork.add(pkg);
              }
              for (Map.Entry<?, ?> entry : tileMap().entrySet()) {
                List<?> values = (List<?>) entry.getValue();
                if (!values.isEmpty()) {
                  selectedPackage[0] = (String) entry.getKey();
                  selected[0] =
                      (View) values.get(values.size() - 1); // All apps copy follows favorites.
                  if (values.size() > 1) break;
                }
              }
              if (selected[0] == null) throw new IllegalStateException("No All apps tile found");
              focusAccepted[0] = selected[0].requestFocus();
              selected[0].requestRectangleOnScreen(
                  new android.graphics.Rect(0, 0, selected[0].getWidth(), selected[0].getHeight()),
                  true);
              Method refresh = catalog.getClass().getDeclaredMethod("refresh");
              refresh.setAccessible(true);
              refresh.invoke(catalog);
            });
        boolean settled = awaitCatalog();
        put(
            result,
            "refresh_elapsed_ms",
            millis(SystemClock.elapsedRealtimeNanos() - refreshStartedNs));
        final boolean[] same = new boolean[1], focused = new boolean[1];
        main(
            () -> {
              List<?> values = (List<?>) tileMap().get(selectedPackage[0]);
              same[0] =
                  values != null
                      && !values.isEmpty()
                      && values.get(values.size() - 1) == selected[0];
              focused[0] = selected[0].isFocused();
            });
        int lost = 0;
        synchronized (callbacks) {
          for (int i = beforeCallbacks; i < callbacks.size(); i++)
            lost += callbacks.get(i).optInt("existing_banners_turned_null");
        }
        put(result, "catalog_settled", settled);
        put(result, "onApps_observed", callbacks.size() > beforeCallbacks);
        put(result, "focus_request_accepted", focusAccepted[0]);
        put(result, "selected_package", selectedPackage[0]);
        put(result, "identical_all_apps_tile", same[0]);
        put(result, "same_view_kept_focus", focused[0]);
        put(result, "existing_banners_turned_null", lost);
        put(result, "existing_banners_preserved", lost == 0);
      } catch (Throwable error) {
        put(result, "error", error.toString());
      }
      rounds.put(result);
    }
    put(report, "refresh_rounds", rounds);
  }

  private void accentAudit(JSONObject report) {
    JSONObject result = new JSONObject();
    put(result, "supported", Build.VERSION.SDK_INT >= 29);
    if (Build.VERSION.SDK_INT < 29) {
      put(result, "skipped", "WindowInspector requires API 29; no preview action sent");
      put(report, "accent_preview", result);
      return;
    }
    View[] tile = new View[1];
    String[] pkg = new String[1];
    Object[] savedAccent = new Object[1];
    ScrollView[] originalScroll = new ScrollView[1];
    int[] originalY = new int[1];
    boolean opened = false;
    try {
      main(
          () -> {
            savedAccent[0] = field(activity, "accent");
            for (Map.Entry<?, ?> entry : tileMap().entrySet()) {
              List<?> values = (List<?>) entry.getValue();
              if (!values.isEmpty()) {
                pkg[0] = (String) entry.getKey();
                tile[0] = (View) values.get(values.size() - 1);
                if (values.size() > 1) break;
              }
            }
            if (tile[0] == null) throw new IllegalStateException("No tile for accent audit");
            tile[0].requestFocus();
            tile[0].requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, tile[0].getWidth(), tile[0].getHeight()), true);
          });
      waitForIdleSync();
      main(
          () -> {
            originalScroll[0] = (ScrollView) field(activity, "scroll");
            originalY[0] = originalScroll[0].getScrollY();
            Method accents = activity.getClass().getDeclaredMethod("showAccents");
            accents.setAccessible(true);
            accents.invoke(activity);
          });
      opened = true;
      waitForIdleSync();
      String savedName = savedAccent[0].toString();
      String label = savedName.equals("CYAN") ? "Electric violet" : "Electric cyan";
      put(result, "preview_label", label);
      put(result, "preview_selection_method", "WindowInspector exact ListView item click");
      put(result, "preview_center_sent", false);
      final boolean[] clicked = new boolean[1];
      main(() -> clicked[0] = clickAccentItem(label));
      put(result, "preview_item_click", clicked[0]);
      waitForIdleSync();
      final boolean[] changed = new boolean[1];
      main(
          () -> {
            changed[0] = field(activity, "accent") != savedAccent[0];
            put(result, "preview_accent_changed", changed[0]);
            put(result, "identity_checks_valid", changed[0]);
            if (!changed[0]) return;
            List<?> current = (List<?>) tileMap().get(pkg[0]);
            ScrollView scroll = (ScrollView) field(activity, "scroll");
            put(
                result,
                "identical_all_apps_tile",
                current != null
                    && !current.isEmpty()
                    && current.get(current.size() - 1) == tile[0]);
            put(result, "identical_scroll_view", scroll == originalScroll[0]);
            put(result, "scroll_y_before", originalY[0]);
            put(result, "scroll_y_after_preview", scroll.getScrollY());
            put(result, "scroll_position_preserved", originalY[0] == scroll.getScrollY());
          });
      if (!changed[0]) put(result, "active_window_debug", windowSummary());
    } catch (Throwable error) {
      put(result, "error", error.toString());
    } finally {
      if (opened) {
        try {
          sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); // Cancel, never select Keep color.
          waitForIdleSync();
          main(
              () ->
                  put(
                      result,
                      "saved_accent_restored",
                      field(activity, "accent") == savedAccent[0]));
        } catch (Exception error) {
          put(result, "cancel_error", error.toString());
        }
      }
    }
    put(report, "accent_preview", result);
  }

  private boolean clickAccentItem(String label) {
    for (View root : WindowInspector.getGlobalWindowViews()) {
      if (!root.getContext().getPackageName().equals("dev.still.launcher")) continue;
      List<View> views = windowViews(root);
      boolean titleMatches = false;
      for (View view : views)
        if (view instanceof TextView
            && ((TextView) view).getText().toString().equals("Preview accent")) titleMatches = true;
      if (!titleMatches) continue;
      for (View view : views)
        if (view instanceof ListView) {
          ListView list = (ListView) view;
          if (list.getAdapter() == null) continue;
          for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (!label.equals(String.valueOf(list.getAdapter().getItem(i)))) continue;
            View row = list.getChildAt(i - list.getFirstVisiblePosition());
            if (row != null && row.isShown())
              return list.performItemClick(row, i, list.getAdapter().getItemId(i));
          }
        }
    }
    return false;
  }

  private List<View> windowViews(View root) {
    List<View> views = new ArrayList<>();
    views.add(root);
    for (int i = 0; i < views.size() && views.size() < 200; i++) {
      View view = views.get(i);
      if (view instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) view;
        for (int j = 0; j < group.getChildCount() && views.size() < 200; j++)
          views.add(group.getChildAt(j));
      }
    }
    return views;
  }

  private JSONObject windowSummary() {
    JSONObject result = new JSONObject();
    try {
      main(
          () -> {
            JSONArray roots = new JSONArray();
            for (View root : WindowInspector.getGlobalWindowViews()) {
              if (!root.getContext().getPackageName().equals("dev.still.launcher")
                  || roots.length() >= 5) continue;
              JSONObject item = new JSONObject();
              put(item, "root_package", root.getContext().getPackageName());
              put(item, "root_class", root.getClass().getName());
              JSONArray text = new JSONArray();
              for (View view : windowViews(root)) {
                if (text.length() >= 10) break;
                if (view instanceof TextView) {
                  String label = ((TextView) view).getText().toString();
                  if (!label.isEmpty()) text.put(label.substring(0, Math.min(label.length(), 100)));
                }
              }
              put(item, "texts", text);
              roots.put(item);
            }
            put(result, "roots", roots);
          });
    } catch (Exception error) {
      put(result, "error", error.toString());
    }
    return result;
  }

  private void navigation(JSONObject report) throws Exception {
    HandlerThread thread = new HandlerThread("still-probe-frames");
    thread.start();
    Window.OnFrameMetricsAvailableListener listener =
        (window, metrics, dropped) -> {
          frames.add(
              new long[] {
                metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION),
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.SYNC_DURATION)
              });
          droppedFrames.addAndGet(dropped);
        };
    try {
      main(
          () ->
              activity
                  .getWindow()
                  .addOnFrameMetricsAvailableListener(listener, new Handler(thread.getLooper())));
      int[] keys = {
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_UP
      };
      long start = SystemClock.elapsedRealtimeNanos();
      for (int i = 0; i < 100; i++) {
        sendKeyDownUpSync(keys[i % keys.length]);
        SystemClock.sleep(180);
      }
      waitForIdleSync();
      put(report, "keys_sent", 100);
      put(report, "key_interval_ms", 180);
      put(report, "navigation_wall_ms", millis(SystemClock.elapsedRealtimeNanos() - start));
    } finally {
      main(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
      thread.quitSafely();
      thread.join(2000);
    }
    String[] names = {"total", "draw", "layout_measure", "sync"};
    JSONObject metrics = new JSONObject();
    synchronized (frames) {
      for (int metric = 0; metric < names.length; metric++) {
        List<Long> values = new ArrayList<>();
        for (long[] frame : frames) if (frame[metric] >= 0) values.add(frame[metric]);
        Collections.sort(values);
        JSONObject stats = new JSONObject();
        put(stats, "samples", values.size());
        for (int percentile : new int[] {50, 90, 95, 99, 100})
          put(
              stats,
              "p" + percentile + "_ms",
              values.isEmpty()
                  ? JSONObject.NULL
                  : millis(
                      values.get(
                          Math.max(0, (int) Math.ceil(values.size() * percentile / 100.0) - 1))));
        int slow = 0;
        for (long value : values) if (value > 16666667L) slow++;
        put(stats, "over_16_67ms", slow);
        put(metrics, names[metric], stats);
      }
    }
    put(report, "frame_metrics", metrics);
    put(report, "frame_callbacks_dropped", droppedFrames.get());
    put(
        report,
        "measurement_scope",
        "Window frame durations during synthetic D-pad navigation; not remote input latency");
  }

  private boolean awaitCatalog() throws Exception {
    long deadline = SystemClock.uptimeMillis() + 30000;
    while (SystemClock.uptimeMillis() < deadline) {
      final boolean[] loading = new boolean[1];
      main(() -> loading[0] = (Boolean) field(catalog, "loading"));
      if (!loading[0]) {
        waitForIdleSync();
        return true;
      }
      SystemClock.sleep(50);
    }
    return false;
  }

  private Map<?, ?> tileMap() throws Exception {
    return (Map<?, ?>) field(activity, "tiles");
  }

  private interface MainAction {
    void run() throws Exception;
  }

  private void main(MainAction action) throws Exception {
    Exception[] failure = new Exception[1];
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Exception error) {
            failure[0] = error;
          }
        });
    if (failure[0] != null) throw failure[0];
  }

  private static Field findField(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static Object field(Object target, String name) throws Exception {
    return findField(target.getClass(), name).get(target);
  }

  private static double millis(long nanos) {
    return nanos / 1000000.0;
  }

  private Object relativeTime(long timestamp) {
    return timestamp == 0 ? JSONObject.NULL : millis(timestamp - createStartNs);
  }

  private static void put(JSONObject object, String key, Object value) {
    try {
      object.put(key, value);
    } catch (org.json.JSONException error) {
      throw new IllegalStateException(error);
    }
  }
}
