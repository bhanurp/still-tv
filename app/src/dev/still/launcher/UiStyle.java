package dev.still.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.widget.TextView;

/** Immutable, cached typography and palette for one selected theme. */
final class UiStyle {
  private static Typeface regular, medium, semibold;
  final Context context;
  final int background, foreground, muted, panel, focus, brand;

  UiStyle(Context context, LauncherState.Theme theme, LauncherState.Accent accent) {
    this.context = context;
    background = Color.parseColor(theme.background);
    foreground = Color.parseColor(theme.foreground);
    muted = Color.parseColor(theme.muted);
    panel = Color.parseColor(theme.panel);
    focus = Color.parseColor(theme.focus);
    brand = Color.parseColor(accent.color(theme));
    if (regular == null) {
      Typeface base = Typeface.createFromAsset(context.getAssets(), "fonts/DMSans.ttf");
      regular = weight(base, 400);
      medium = weight(base, 500);
      semibold = weight(base, 600);
    }
  }

  private static Typeface weight(Typeface base, int value) {
    return Build.VERSION.SDK_INT >= 28
        ? Typeface.create(base, value, false)
        : Typeface.create(base, value >= 600 ? Typeface.BOLD : Typeface.NORMAL);
  }

  Typeface font(int weight) {
    return weight >= 600 ? semibold : weight >= 500 ? medium : regular;
  }

  int dp(float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  float sp(float value) {
    return value * context.getResources().getDisplayMetrics().scaledDensity;
  }

  TextView text(String value, int size, int weight, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setTypeface(font(weight));
    view.setIncludeFontPadding(false);
    return view;
  }

  void applyBrand(TextView view) {
    view.setTextSize(36);
    view.setTypeface(font(500));
    view.setLetterSpacing(-.035f);
    view.setTextColor(brand);
    view.setIncludeFontPadding(false);
  }

  GradientDrawable rounded(int fill) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(12));
    return drawable;
  }
}
