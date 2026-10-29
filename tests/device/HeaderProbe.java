package dev.still.launcher.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;

/** Check all approved phrases against the TV's actual font metrics and header width. */
public final class HeaderProbe extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    JSONObject report = new JSONObject();
    try {
      Activity activity =
          startActivitySync(
              new Intent(Intent.ACTION_MAIN)
                  .setComponent(
                      new ComponentName("dev.still.launcher", "dev.still.launcher.MainActivity"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      waitForIdleSync();
      Throwable[] failure = new Throwable[1];
      runOnMainSync(
          () -> {
            try {
              TextView brand = (TextView) field(activity, "brand");
              TextView clock = (TextView) field(activity, "clock");
              float scaled = activity.getResources().getDisplayMetrics().scaledDensity;
              require(
                  Math.abs(brand.getTextSize() / scaled - 32) < .01f, "Left header must be 32 sp");
              require(
                  Math.abs(clock.getTextSize() / scaled - 36) < .01f, "Clock must remain 36 sp");
              Class<?> state =
                  activity.getClassLoader().loadClass("dev.still.launcher.LauncherState");
              Field pool = state.getDeclaredField("HEADLINES");
              pool.setAccessible(true);
              List<?> phrases = (List<?>) pool.get(null);
              int width =
                  brand.getWidth()
                      - brand.getCompoundPaddingLeft()
                      - brand.getCompoundPaddingRight();
              require(width > 0, "Header must be laid out");
              for (Object value : phrases) {
                String phrase = (String) value;
                StaticLayout layout =
                    StaticLayout.Builder.obtain(phrase, 0, phrase.length(), brand.getPaint(), width)
                        .setMaxLines(1)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build();
                require(layout.getEllipsisCount(0) == 0, "Clipped phrase: " + phrase);
              }
              String before = brand.getText().toString();
              require(phrases.contains(before), "Displayed phrase must be approved");
              Method render = activity.getClass().getDeclaredMethod("render");
              render.setAccessible(true);
              render.invoke(activity);
              require(
                  before.equals(((TextView) field(activity, "brand")).getText().toString()),
                  "Appearance render must retain the current phrase");
              report.put("phrases_fit", phrases.size());
              report.put("left_sp", brand.getTextSize() / scaled);
              report.put("clock_sp", clock.getTextSize() / scaled);
              report.put("available_width_px", width);
              report.put("appearance_retains_phrase", true);
            } catch (Throwable error) {
              failure[0] = error;
            }
          });
      if (failure[0] != null) throw failure[0];
      waitForIdleSync();
    } catch (Throwable error) {
      try {
        report.put("error", error.toString());
      } catch (Exception ignored) {
      }
    }
    Bundle result = new Bundle();
    result.putString("stream", "\nSTILL_TV_HEADER_JSON=" + report + "\n");
    finish(report.has("error") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
