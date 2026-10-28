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
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Package/resource/disk operations stay on one background worker. */
final class AppCatalog implements AutoCloseable {
  static final class App {
    final String pkg, label;
    final ComponentName component;
    final ResolveInfo info;
    Bitmap banner;

    App(ResolveInfo info, PackageManager pm) {
      this.info = info;
      pkg = info.activityInfo.packageName;
      component = new ComponentName(pkg, info.activityInfo.name);
      CharSequence value = info.loadLabel(pm);
      label = value == null ? pkg : value.toString();
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
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
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
                apps.add(new App(info, pm));
              } catch (RuntimeException ignored) {
              }
            }
            Collator order = Collator.getInstance();
            apps.sort((a, b) -> order.compare(a.label, b.label));
            post(
                () ->
                    listener.onApps(apps)); // Navigation is ready before artwork decoding finishes.
            File directory = new File(context.getCacheDir(), "artwork");
            directory.mkdirs();
            for (App app : apps) {
              if (closed || Thread.currentThread().isInterrupted()) break;
              try {
                long updated = pm.getPackageInfo(app.pkg, 0).lastUpdateTime;
                String key = app.pkg + "_" + updated;
                Bitmap bitmap = MEMORY.get(key);
                File cached = new File(directory, app.pkg + ".png");
                if (bitmap == null && cached.lastModified() == updated)
                  bitmap = BitmapFactory.decodeFile(cached.getPath());
                if (bitmap == null) {
                  bitmap = decode(app.info, pm);
                  if (bitmap != null)
                    try (FileOutputStream output = new FileOutputStream(cached)) {
                      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                      cached.setLastModified(updated);
                    } catch (Exception ignored) {
                    }
                }
                if (bitmap != null) MEMORY.put(key, bitmap);
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
            // Keep the disk cache bounded without doing I/O on the UI thread.
            File[] files = directory.listFiles();
            long bytes = 0;
            if (files != null) {
              for (File file : files) bytes += file.length();
              if (bytes > 8 * 1024 * 1024) for (File file : files) file.delete();
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
