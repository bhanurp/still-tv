package dev.still.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Package/resource/disk operations stay on one background worker. */
final class AppCatalog implements AutoCloseable {
  static final class App {
    final String pkg, label;
    final ComponentName component;
    final ResolveInfo info;
    final long updated;
    volatile Bitmap banner;

    App(ResolveInfo info, PackageManager pm) throws PackageManager.NameNotFoundException {
      this.info = info;
      pkg = info.activityInfo.packageName;
      component = new ComponentName(pkg, info.activityInfo.name);
      CharSequence value = info.loadLabel(pm);
      label = value == null ? pkg : value.toString();
      updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
    }

    boolean matches(App other) {
      return other != null
          && component.equals(other.component)
          && label.equals(other.label)
          && updated == other.updated;
    }
  }

  interface Listener {
    void onApps(List<App> apps);

    void onArtwork(App app, Bitmap bitmap);

    void onError();
  }

  private static final LruCache<String, Bitmap> MEMORY =
      new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getAllocationByteCount();
        }
      };
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          task ->
              new Thread(
                  () -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                  },
                  "still-artwork"));
  // Reuse the images already retained by live tiles, even when the LRU is smaller than the app
  // list.
  // Only the worker touches this map; banner publication is visible across threads.
  private final Map<String, App> previous = new HashMap<>();
  private Listener listener;
  private volatile boolean closed;
  private boolean loading, pending;

  AppCatalog(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
  }

  void refresh() {
    if (closed) return;
    if (loading) {
      pending = true;
      return;
    }
    loading = true;
    worker.execute(
        () -> {
          try {
            PackageManager pm = context.getPackageManager();
            List<App> apps = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            Intent query =
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
              if (info.activityInfo == null
                  || !info.activityInfo.enabled
                  || !info.activityInfo.exported) continue;
              String pkg = info.activityInfo.packageName;
              if (pkg.equals(context.getPackageName())
                  || pkg.equals("com.android.tv.settings")
                  || !seen.add(pkg)) continue;
              try {
                App app = new App(info, pm);
                App existing = previous.get(pkg);
                apps.add(app.matches(existing) ? existing : app);
              } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
              }
            }
            Collator order = Collator.getInstance();
            apps.sort((a, b) -> order.compare(a.label, b.label));
            previous.clear();
            for (App app : apps) previous.put(app.pkg, app);
            post(
                () ->
                    listener.onApps(apps)); // Navigation is ready before artwork decoding finishes.
            File directory = new File(context.getCacheDir(), "artwork");
            directory.mkdirs();
            Map<App, Bitmap> writes = new LinkedHashMap<>();
            for (App app : apps) {
              if (closed || Thread.currentThread().isInterrupted()) break;
              if (app.banner != null) continue;
              try {
                String key = app.pkg + "_" + app.updated;
                Bitmap bitmap = MEMORY.get(key);
                // Some Android filesystems round File.setLastModified() down to whole seconds.
                // Keep the exact package version in the filename instead of comparing mtimes.
                File cached = new File(directory, key + ".png");
                if (bitmap == null && cached.isFile())
                  bitmap = BitmapFactory.decodeFile(cached.getPath());
                if (bitmap == null) {
                  bitmap = decode(app.info, pm);
                  if (bitmap != null) writes.put(app, bitmap);
                }
                if (bitmap != null) {
                  MEMORY.put(key, bitmap);
                  bitmap.prepareToDraw();
                }
                Bitmap result = bitmap;
                post(
                    () -> {
                      app.banner = result;
                      listener.onArtwork(app, result);
                    });
              } catch (Exception ignored) {
                /* Removed/updated packages retain a readable fallback. */
              }
            }
            // Deliver every image before PNG compression competes with the visible loading path.
            for (Map.Entry<App, Bitmap> entry : writes.entrySet()) {
              if (closed || Thread.currentThread().isInterrupted()) break;
              App app = entry.getKey();
              File cached = new File(directory, app.pkg + "_" + app.updated + ".png");
              File temporary = new File(directory, cached.getName() + ".tmp");
              boolean written;
              try (FileOutputStream output = new FileOutputStream(temporary)) {
                written = entry.getValue().compress(Bitmap.CompressFormat.PNG, 100, output);
              } catch (Exception ignored) {
                written = false;
              }
              // A process killed during compression must not leave a seemingly valid PNG key.
              if (!written || !temporary.renameTo(cached)) temporary.delete();
            }
            // Keep the disk cache bounded without doing I/O on the UI thread.
            File[] files = directory.listFiles();
            long bytes = 0;
            if (files != null) {
              HashSet<String> valid = new HashSet<>();
              for (App app : apps) valid.add(app.pkg + "_" + app.updated + ".png");
              for (File file : files) {
                if (!valid.contains(file.getName())) file.delete();
                if (file.isFile()) bytes += file.length();
              }
              // Evict only enough oldest files to meet the budget; retain the rest of the cache.
              Arrays.sort(files, Comparator.comparingLong(File::lastModified));
              for (File file : files) {
                if (bytes <= 8 * 1024 * 1024) break;
                long size = file.length();
                if (file.delete()) bytes -= size;
              }
            }
          } catch (RuntimeException error) {
            post(() -> listener.onError());
          } finally {
            post(
                () -> {
                  loading = false;
                  if (pending) {
                    pending = false;
                    refresh();
                  }
                });
          }
        });
  }

  private void post(Runnable action) {
    main.post(
        () -> {
          if (!closed && listener != null) action.run();
        });
  }

  private Bitmap decode(ResolveInfo info, PackageManager pm) {
    Drawable drawable = info.activityInfo.loadBanner(pm);
    boolean banner = drawable != null;
    if (drawable == null) drawable = info.loadIcon(pm);
    if (drawable == null) return null;
    Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.rgb(36, 36, 40));
    int w = Math.max(1, drawable.getIntrinsicWidth()),
        h = Math.max(1, drawable.getIntrinsicHeight());
    float scale = Math.min((banner ? 320f : 106f) / w, (banner ? 180f : 106f) / h);
    int dw = Math.round(w * scale), dh = Math.round(h * scale);
    drawable.setBounds((320 - dw) / 2, (180 - dh) / 2, (320 + dw) / 2, (180 + dh) / 2);
    drawable.draw(canvas);
    return bitmap;
  }

  @Override
  public void close() {
    closed = true;
    listener = null;
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
  }
}
