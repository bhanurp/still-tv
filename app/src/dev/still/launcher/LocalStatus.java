package dev.still.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Foreground-only OS observations; storage and the initial snapshot use the catalog worker. */
final class LocalStatus implements AutoCloseable {
  private final Context context;
  private final ConnectivityManager connectivity;
  private final Executor worker;
  private final Handler main = new Handler(Looper.getMainLooper());
  private Consumer<String> listener;
  private volatile long generation;
  private int networkRevision;
  private boolean active, reading;
  private ConnectivityManager.NetworkCallback callback;
  private Network current;
  private String connectionText = "Checking connection…", storageText = "Checking storage…";

  LocalStatus(Context context, Executor worker, Consumer<String> listener) {
    this.context = context.getApplicationContext();
    connectivity = this.context.getSystemService(ConnectivityManager.class);
    this.worker = worker;
    this.listener = listener;
  }

  void start() {
    if (active || listener == null) return;
    active = true;
    long token = ++generation;
    networkRevision = 0;
    current = null;
    if (connectivity == null) {
      connectionText = "Connection unavailable";
      publish();
      return;
    }
    callback =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(Network network) {
            if (!valid(token)) return;
            current = network;
            networkRevision++;
          }

          @Override
          public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (!valid(token) || !network.equals(current)) return;
            networkRevision++;
            connectionText = describe(capabilities);
            publish();
          }

          @Override
          public void onLost(Network network) {
            if (!valid(token) || !network.equals(current)) return;
            networkRevision++;
            current = null;
            connectionText = "Offline";
            publish();
          }
        };
    try {
      connectivity.registerDefaultNetworkCallback(callback, main);
    } catch (RuntimeException unavailable) {
      callback = null;
      connectionText = "Connection unavailable";
      publish();
    }
  }

  void refreshStorage() {
    if (!active || reading) return;
    reading = true;
    long token = generation;
    int revision = networkRevision;
    boolean snapshot = revision == 0 || callback == null;
    worker.execute(
        () -> {
          if (generation != token) return;
          Network network = null;
          String connection = "Connection unavailable", storage;
          if (snapshot && connectivity != null) {
            try {
              network = connectivity.getActiveNetwork();
              connection =
                  network == null
                      ? "Offline"
                      : describe(connectivity.getNetworkCapabilities(network));
            } catch (RuntimeException ignored) {
            }
          }
          try {
            long bytes = new StatFs(context.getApplicationInfo().dataDir).getAvailableBytes();
            storage = "Storage: " + Formatter.formatShortFileSize(context, bytes) + " free";
          } catch (RuntimeException unavailable) {
            storage = "Storage unavailable";
          }
          Network found = network;
          String networkLabel = connection, storageLabel = storage;
          main.post(
              () -> {
                if (!valid(token)) return;
                reading = false;
                // A snapshot must not overwrite a newer capability/loss callback.
                if (snapshot && networkRevision == revision) {
                  current = found;
                  connectionText = networkLabel;
                  networkRevision++;
                }
                storageText = storageLabel;
                publish();
              });
        });
  }

  private static String describe(NetworkCapabilities capabilities) {
    if (capabilities == null) return "Network connected";
    String transport =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ? "VPN"
            : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ? "Ethernet"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    ? "Wi-Fi"
                    : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        ? "Mobile"
                        : "Network";
    return LauncherState.connectionStatus(
        transport,
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
  }

  private boolean valid(long token) {
    return active && generation == token && listener != null;
  }

  private void publish() {
    if (listener != null) listener.accept(connectionText + "  ·  " + storageText);
  }

  void stop() {
    active = false;
    generation++;
    reading = false;
    if (callback != null) {
      try {
        connectivity.unregisterNetworkCallback(callback);
      } catch (RuntimeException ignored) {
      }
      callback = null;
    }
    main.removeCallbacksAndMessages(null);
  }

  @Override
  public void close() {
    stop();
    listener = null;
  }
}
