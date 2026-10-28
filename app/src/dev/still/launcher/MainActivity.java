package dev.still.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lifecycle, screen composition and menus; rendering/data/recovery are separate. */
public final class MainActivity extends Activity implements AppCatalog.Listener {
  private SharedPreferences prefs;
  private Recovery recovery;
  private AppCatalog catalog;
  private LauncherState.Theme theme;
  private LauncherState.Accent accent;
  private UiStyle ui;
  private final List<AppCatalog.App> apps = new ArrayList<>();
  private final Map<String, List<AppTile>> tiles = new HashMap<>();
  private List<String> favorites = new ArrayList<>();
  private LinearLayout groups;
  private ScrollView scroll;
  private View firstFavorite, firstApp;
  private TextView greeting, date;
  private boolean registered, loaded;
  private String lastFocus;
  private int tileWidth;
  private final BroadcastReceiver packages =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          catalog.refresh();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("still", MODE_PRIVATE);
    recovery = new Recovery(this, prefs);
    theme = LauncherState.Theme.parse(prefs.getString("theme", null));
    accent = LauncherState.Accent.parse(prefs.getString("accent", null));
    ui = new UiStyle(this, theme, accent);
    if (recovery.isSafeMode()) {
      if (getIntent().hasCategory(Intent.CATEGORY_HOME) && recovery.openHome()) finish();
      else showRecovery();
      return;
    }
    render();
    catalog = new AppCatalog(this, this);
    catalog.refresh();
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_PACKAGE_ADDED);
    filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
    filter.addDataScheme("package");
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(packages, filter);
    registered = true;
  }

  @Override
  public void onApps(List<AppCatalog.App> result) {
    apps.clear();
    apps.addAll(result);
    loaded = true;
    List<String> available = new ArrayList<>();
    for (AppCatalog.App app : apps) available.add(app.pkg);
    favorites = LauncherState.favoritePackages(prefs.getString("favorites", null), available);
    bindGroups();
  }

  @Override
  public void onArtwork(AppCatalog.App app, Bitmap bitmap) {
    List<AppTile> matches = tiles.get(app.pkg);
    if (matches != null) for (AppTile tile : matches) tile.setArtwork(bitmap);
  }

  @Override
  public void onError() {
    Toast.makeText(
            this,
            "Couldn't refresh apps. Your existing apps are still available.",
            Toast.LENGTH_LONG)
        .show();
  }

  private void render() {
    ui = new UiStyle(this, theme, accent);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(ui.background);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(ui.dp(44), 0, ui.dp(44), 0);
    TextView brand = new TextView(this);
    brand.setText("bhanureddy.dev");
    ui.applyBrand(brand);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(
        nav("Home", () -> focus(firstFavorite != null ? firstFavorite : firstApp), true));
    header.addView(nav("Apps", () -> focus(firstApp), false));
    header.addView(nav("Settings", this::showSettings, false));
    TextClock clock = new TextClock(this);
    ui.applyBrand(clock);
    clock.setFormat12Hour("h:mm");
    clock.setFormat24Hour("HH:mm");
    clock.setGravity(Gravity.END);
    clock.setIncludeFontPadding(false);
    header.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, ui.dp(64));
    headerLp.topMargin = ui.dp(12);
    root.addView(header, headerLp);
    scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipChildren(false);
    scroll.setFillViewport(true);
    LinearLayout page = column();
    page.setPadding(ui.dp(44), ui.dp(18), ui.dp(44), ui.dp(34));
    greeting = ui.text("", 36, 500, ui.foreground);
    greeting.setLetterSpacing(-.035f);
    page.addView(greeting);
    date = ui.text("", 13, 400, ui.muted);
    LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-1, -2);
    dateLp.topMargin = ui.dp(10);
    dateLp.bottomMargin = ui.dp(30);
    page.addView(date, dateLp);
    updateGreeting();
    groups = column();
    page.addView(groups);
    scroll.addView(page);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    int width =
        Math.round(
            getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
    tileWidth = Math.max(80, (width - 88 - 5 * 18) / 6);
    bindGroups();
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.VERTICAL);
    view.setClipChildren(false);
    view.setClipToPadding(false);
    return view;
  }

  private void updateGreeting() {
    if (greeting == null) return;
    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    greeting.setText(hour < 12 ? "Good morning." : hour < 18 ? "Good afternoon." : "Good evening.");
    date.setText(android.text.format.DateFormat.format("EEEE, d MMMM", new java.util.Date()));
  }

  private Button nav(String label, Runnable action, boolean selected) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTypeface(ui.font(500));
    button.setTextSize(13);
    button.setFocusableInTouchMode(true);
    button.setStateListAnimator(null);
    button.setElevation(0);
    button.setMinWidth(ui.dp(76));
    StateListDrawable background = new StateListDrawable();
    background.addState(new int[] {android.R.attr.state_focused}, ui.rounded(ui.focus));
    background.addState(new int[] {android.R.attr.state_selected}, ui.rounded(ui.panel));
    background.addState(new int[] {}, ui.rounded(ui.background));
    button.setBackground(background);
    button.setSelected(selected);
    button.setTextColor(
        new ColorStateList(
            new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
            new int[] {ui.background, ui.foreground, ui.muted}));
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private void bindGroups() {
    groups.removeAllViews();
    tiles.clear();
    firstFavorite = null;
    firstApp = null;
    LinearLayout heading = new LinearLayout(this);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.addView(
        ui.text("Favorites", 18, 600, ui.foreground), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(ui.text("Hold OK to customize", 11, 400, ui.muted));
    groups.addView(heading);
    List<AppCatalog.App> pinned = new ArrayList<>();
    for (String pkg : favorites)
      for (AppCatalog.App app : apps) if (app.pkg.equals(pkg)) pinned.add(app);
    if (pinned.isEmpty()) {
      TextView empty =
          ui.text(
              loaded ? "Hold OK on an app to add a favorite." : "Finding your apps…",
              13,
              400,
              ui.muted);
      empty.setPadding(0, ui.dp(20), 0, ui.dp(24));
      groups.addView(empty);
    } else addGrid(pinned, true);
    TextView title = ui.text("All apps", 18, 600, ui.foreground);
    title.setPadding(0, ui.dp(22), 0, 0);
    groups.addView(title);
    addGrid(apps, false);
    if (loaded && apps.isEmpty())
      groups.addView(
          ui.text("No TV apps found. System settings is available above.", 13, 400, ui.muted));
    List<AppTile> saved = lastFocus == null ? null : tiles.get(lastFocus);
    View target = saved == null ? (firstFavorite != null ? firstFavorite : firstApp) : saved.get(0);
    if (target != null) target.post(target::requestFocus);
  }

  private void addGrid(List<AppCatalog.App> list, boolean pinned) {
    for (int start = 0; start < list.size(); start += 6) {
      LinearLayout row = new LinearLayout(this);
      row.setClipChildren(false);
      row.setClipToPadding(false);
      row.setPadding(0, ui.dp(16), 0, ui.dp(6));
      for (int col = 0; col < 6 && start + col < list.size(); col++) {
        AppCatalog.App app = list.get(start + col);
        AppTile tile = new AppTile(ui, app.label, app.banner);
        tile.setOnFocusChangeListener(
            (v, focused) -> {
              if (focused) lastFocus = app.pkg;
            });
        tile.setOnClickListener(v -> launch(app));
        tile.setOnLongClickListener(
            v -> {
              appMenu(app);
              return true;
            });
        tile.setOnKeyListener(
            (v, key, event) -> {
              if (key == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
                appMenu(app);
                return true;
              }
              return false;
            });
        if (!tiles.containsKey(app.pkg)) tiles.put(app.pkg, new ArrayList<>());
        tiles.get(app.pkg).add(tile);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(tileWidth), -2);
        if (col > 0) lp.leftMargin = ui.dp(18);
        row.addView(tile, lp);
        if (pinned && firstFavorite == null) firstFavorite = tile;
        if (!pinned && firstApp == null) firstApp = tile;
      }
      groups.addView(row);
    }
  }

  private void focus(View view) {
    if (view != null) view.requestFocus();
  }

  private void launch(AppCatalog.App app) {
    try {
      startActivity(
          new Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
              .setComponent(app.component)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (RuntimeException error) {
      Toast.makeText(
              this, "Couldn't open " + app.label + ". It may have been removed.", Toast.LENGTH_LONG)
          .show();
      catalog.refresh();
    }
  }

  private AlertDialog.Builder dialog() {
    return new AlertDialog.Builder(
        new ContextThemeWrapper(
            this,
            theme == LauncherState.Theme.LIGHT
                ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert));
  }

  private void appMenu(AppCatalog.App app) {
    dialog()
        .setTitle(app.label)
        .setItems(
            new String[] {
              "Open",
              favorites.contains(app.pkg) ? "Remove from favorites" : "Add to favorites",
              "App information"
            },
            (d, which) -> {
              if (which == 0) launch(app);
              else if (which == 1) {
                favorites = LauncherState.toggleFavorite(favorites, app.pkg);
                prefs.edit().putString("favorites", TextUtils.join(",", favorites)).apply();
                bindGroups();
              } else
                openSystem(
                    new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.pkg)));
            })
        .show();
  }

  private void showSettings() {
    dialog()
        .setTitle("Still TV settings")
        .setItems(
            new String[] {
              "Appearance · " + theme.label,
              "System settings",
              "Use Still TV as Home",
              "Restore original launcher",
              "Recovery information"
            },
            (d, which) -> {
              if (which == 0) showAppearance();
              else if (which == 1) openSystem(new Intent(Settings.ACTION_SETTINGS));
              else if (which == 2) {
                if (!recovery.requestStillHome(this))
                  Toast.makeText(
                          this,
                          "This firmware did not switch Home to Still TV. Your original launcher is"
                              + " preserved.",
                          Toast.LENGTH_LONG)
                      .show();
              } else if (which == 3) {
                if (recovery.revertHome()) finish();
                else
                  Toast.makeText(
                          this,
                          "Original Home could not be restored. Still TV is preserved; use ADB"
                              + " recovery.",
                          Toast.LENGTH_LONG)
                      .show();
              } else
                dialog()
                    .setTitle("Original launcher preserved")
                    .setMessage(
                        "Still TV never disables or removes your original launcher. Restore"
                            + " original launcher switches back while keeping Still TV available in"
                            + " Apps. ADB recovery is available if the screen freezes. Java crash"
                            + " recovery is best effort.")
                    .setPositiveButton("OK", null)
                    .show();
            })
        .show();
  }

  private void showAppearance() {
    dialog()
        .setTitle("Appearance")
        .setItems(
            new String[] {"Theme · " + theme.label, "Accent · " + accent.label},
            (d, which) -> {
              if (which == 0) showThemes();
              else showAccents();
            })
        .show();
  }

  private void showAccents() {
    LauncherState.Accent saved = accent;
    LauncherState.Accent[] values = LauncherState.Accent.values();
    String[] labels = new String[values.length];
    for (int i = 0; i < values.length; i++) labels[i] = values[i].label;
    Runnable cancel =
        () -> {
          accent = saved;
          render();
        };
    AlertDialog preview =
        dialog()
            .setTitle("Preview accent")
            .setSingleChoiceItems(
                labels,
                accent.ordinal(),
                (d, which) -> {
                  accent = values[which];
                  render();
                })
            .setPositiveButton(
                "Keep color", (d, w) -> prefs.edit().putString("accent", accent.name()).apply())
            .setNegativeButton("Cancel", (d, w) -> cancel.run())
            .setOnCancelListener(d -> cancel.run())
            .create();
    preview.show();
    if (preview.getWindow() != null) {
      preview.getWindow().setDimAmount(0f);
      preview.getWindow().setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
      preview.getWindow().setLayout(ui.dp(330), WindowManager.LayoutParams.WRAP_CONTENT);
    }
  }

  private void showThemes() {
    LauncherState.Theme[] values = LauncherState.Theme.values();
    String[] labels = new String[values.length];
    for (int i = 0; i < values.length; i++) labels[i] = values[i].label;
    dialog()
        .setTitle("Appearance")
        .setSingleChoiceItems(
            labels,
            theme.ordinal(),
            (d, which) -> {
              theme = values[which];
              prefs.edit().putString("theme", theme.name()).apply();
              d.dismiss();
              render();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void showRecovery() {
    dialog()
        .setTitle("Still TV recovery")
        .setMessage("Still TV stopped unexpectedly. Your original launcher is preserved.")
        .setPositiveButton(
            "Open original Home",
            (d, w) -> {
              recovery.openHome();
              finish();
            })
        .setNeutralButton(
            "Try Still TV again",
            (d, w) -> {
              recovery.retry();
              recreate();
            })
        .setNegativeButton(
            "System settings",
            (d, w) -> {
              openSystem(new Intent(Settings.ACTION_SETTINGS));
              finish();
            })
        .setCancelable(false)
        .show();
  }

  private void openSystem(Intent intent) {
    try {
      startActivity(intent);
    } catch (RuntimeException e) {
      Toast.makeText(this, "This settings screen isn't available on this TV.", Toast.LENGTH_LONG)
          .show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    updateGreeting();
  }

  @Override
  public void onBackPressed() {
    if (scroll == null) {
      recovery.openHome();
      finish();
    } else {
      scroll.smoothScrollTo(0, 0);
      focus(firstFavorite != null ? firstFavorite : firstApp);
    }
  }

  @Override
  public void onDestroy() {
    if (registered) unregisterReceiver(packages);
    if (catalog != null) catalog.close();
    super.onDestroy();
  }
}
