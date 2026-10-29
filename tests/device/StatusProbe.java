package dev.still.launcher.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/**
 * Owner-run: am instrument -w dev.still.launcher.tests/.StatusProbe
 *
 * <p>Build with sh tools/device-test.sh. Simulates callback delivery, never changes networks,
 * appearance, package states or Home aliases. Normal resumes may advance the saved header phrase.
 * Re-registers real observation before finishing.
 */
public final class StatusProbe extends Instrumentation {
  private Activity activity;
  private Object status;
  private TextView view;
  private int failures;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    JSONObject report = new JSONObject();
    try {
      activity =
          startActivitySync(
              new Intent(Intent.ACTION_MAIN)
                  .setComponent(
                      new ComponentName("dev.still.launcher", "dev.still.launcher.MainActivity"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      status = field(activity, "localStatus");
      view = (TextView) field(activity, "statusView");
      check(report, "initial_storage_ready", awaitReady(false));
      main(
          () -> {
            TextView date = (TextView) field(activity, "date");
            check(report, "date_nonempty", date.getText().length() > 0);
            float sp = view.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity;
            put(report, "status_font_sp", sp);
            check(report, "status_font_13sp", Math.abs(sp - 13f) < .25f);
            check(
                report,
                "shared_horizontal_strip",
                view.getParent() == date.getParent()
                    && view.getParent() instanceof LinearLayout
                    && ((LinearLayout) view.getParent()).getOrientation()
                        == LinearLayout.HORIZONTAL);
            put(report, "status_view_type", view.getClass().getName());
            put(report, "date_view_type", date.getClass().getName());
            put(report, "initial_status", view.getText().toString());
          });
      ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
      Network network = cm.getActiveNetwork();
      ConnectivityManager.NetworkCallback callback =
          (ConnectivityManager.NetworkCallback) field(status, "callback");
      Object groups = field(activity, "groups");
      if (network != null && callback != null) {
        main(() -> callback.onAvailable(network));
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps != null) {
          main(() -> callback.onCapabilitiesChanged(network, caps));
          waitForIdleSync();
          main(
              () -> {
                check(
                    report,
                    "real_capabilities_replayed",
                    text().contains(expectedConnection(network, caps)));
                String transport =
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        ? "VPN"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                            ? "Ethernet"
                            : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                ? "Wi-Fi"
                                : null;
                if (transport != null)
                  check(report, "actual_transport_displayed", text().contains(transport));
                put(report, "replayed_status", text());
              });
        } else put(report, "capabilities_replay_skipped", "OS returned no capabilities");
        main(() -> callback.onLost(network));
        waitForIdleSync();
        main(() -> check(report, "lost_network_offline", text().contains("Offline")));
      } else put(report, "callback_simulation_skipped", "No real active Network/captured callback");
      put(
          report,
          "synthetic_capability_transitions",
          "Covered by host LauncherState.connectionStatus formatter tests; native probe uses public"
              + " SDK and real OS capabilities only");
      put(
          report,
          "old_network_lost_after_new_skipped",
          "No fabricated or hidden Network constructor used");
      if (callback != null && network != null) {
        NetworkCapabilities real = cm.getNetworkCapabilities(network);
        main(
            () -> {
              callback.onAvailable(network);
              if (real != null) callback.onCapabilitiesChanged(network, real);
            });
        waitForIdleSync();
      }
      main(
          () -> {
            invoke("stop");
            check(report, "stopped_inactive", Boolean.FALSE.equals(field(status, "active")));
            check(report, "callback_cleared", field(status, "callback") == null);
          });
      final String[] stopped = new String[1];
      final Object[] stoppedCurrent = new Object[1];
      main(
          () -> {
            stopped[0] = text();
            stoppedCurrent[0] = field(status, "current");
          });
      if (callback != null && network != null) {
        main(() -> callback.onAvailable(network));
        waitForIdleSync();
        main(
            () ->
                check(
                    report,
                    "stale_callback_current_unchanged",
                    java.util.Objects.equals(stoppedCurrent[0], field(status, "current"))));
        main(() -> callback.onLost(network));
        waitForIdleSync();
        main(() -> check(report, "stale_callback_ignored_after_stop", stopped[0].equals(text())));
      }
      main(() -> check(report, "groups_identity_retained", field(activity, "groups") == groups));
    } catch (Throwable error) {
      put(report, "error", error.toString());
    } finally {
      if (status != null)
        try {
          main(
              () -> {
                invoke("stop");
                invoke("start");
                invoke("refreshStorage");
              });
          check(report, "real_observation_restored", awaitReady(true));
          main(() -> put(report, "final_status", text()));
        } catch (Throwable error) {
          put(report, "restore_error", error.toString());
          failures++;
        }
    }
    put(report, "failed_checks", failures);
    if (failures > 0 && !report.has("error"))
      put(report, "error", "One or more status invariants failed");
    Bundle result = new Bundle();
    result.putString("report", report.toString());
    result.putString("stream", "\nSTILL_TV_STATUS_JSON=" + report + "\n");
    finish(Activity.RESULT_OK, result);
  }

  private boolean awaitReady(boolean real) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 30000;
    while (SystemClock.uptimeMillis() < deadline) {
      boolean[] ready = new boolean[1];
      main(
          () -> {
            String value = text();
            ready[0] =
                !value.contains("Checking")
                    && (value.contains("free") || value.contains("Storage unavailable"));
            if (real) {
              ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
              Network active = cm.getActiveNetwork();
              ready[0] &=
                  Boolean.TRUE.equals(field(status, "active"))
                      && field(status, "callback") != null
                      && (active == null
                          ? field(status, "current") == null
                          : active.equals(field(status, "current")));
              NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
              ready[0] &= value.contains(expectedConnection(active, caps));
            }
          });
      if (ready[0]) return true;
      SystemClock.sleep(50);
    }
    return false;
  }

  private static String expectedConnection(Network network, NetworkCapabilities caps) {
    if (network == null) return "Offline";
    if (caps == null) return "Network connected";
    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
      return "Sign-in required";
    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
      return "Internet connected";
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ? "Internet unverified"
        : "No internet";
  }

  private String text() {
    return view.getText().toString();
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private void invoke(String name) throws Exception {
    Method method = status.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(status);
  }

  private interface Action {
    void run() throws Exception;
  }

  private void main(Action action) throws Exception {
    Exception[] error = new Exception[1];
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Exception failure) {
            error[0] = failure;
          }
        });
    if (error[0] != null) throw error[0];
  }

  private void check(JSONObject report, String name, boolean value) {
    put(report, name, value);
    if (!value) failures++;
  }

  private static void put(JSONObject report, String name, Object value) {
    try {
      report.put(name, value);
    } catch (org.json.JSONException error) {
      throw new IllegalStateException(error);
    }
  }
}
