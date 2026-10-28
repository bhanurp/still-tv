package dev.still.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<App> apps = new ArrayList<>();
    private final Map<String, Bitmap> artwork = new HashMap<>();
    private final Map<String, View> tileViews = new HashMap<>();
    private SharedPreferences prefs;
    private LauncherState.Theme theme;
    private List<String> favorites = new ArrayList<>();
    private ScrollView scroll;
    private LinearLayout content;
    private View firstFavorite, firstApp, allAppsHeading;
    private TextView clock;
    private boolean loading, receiverRegistered, closed, refreshPending;
    private static boolean recoveryInstalled;
    private int tileWidth;
    private String lastFocus;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if(clock!=null) clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            main.postDelayed(this, 60000);
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { loadApps(); }
    };
    private static final class App {
        String label, pkg;
        ComponentName component;
        Bitmap banner;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("still", MODE_PRIVATE);
        rememberOriginalHome();
        installCrashRecovery(getApplicationContext(),prefs);
        theme = LauncherState.Theme.parse(prefs.getString("theme", null));
        if(prefs.getBoolean("safe_mode", false)) {
            if(getIntent().hasCategory(Intent.CATEGORY_HOME) && openOriginalHome()) { finish(); return; }
            showRecovery(); return;
        }
        loadApps();
        render();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if(Build.VERSION.SDK_INT >= 33) registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, filter);
        receiverRegistered = true;
    }

    private void rememberOriginalHome() {
        if(prefs.contains("original_home")) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if(isUsableHome(resolved)) saveHome(resolved);
        else for(ResolveInfo candidate:getPackageManager().queryIntentActivities(home, 0)) {
            if(isUsableHome(candidate)) { saveHome(candidate); break; }
        }
    }
    private boolean isUsableHome(ResolveInfo info) {
        return info!=null && info.activityInfo!=null && !getPackageName().equals(info.activityInfo.packageName)
            && !"android".equals(info.activityInfo.packageName) && !info.activityInfo.name.contains("FallbackHome");
    }
    private void saveHome(ResolveInfo info) {
        prefs.edit().putString("original_home",new ComponentName(info.activityInfo.packageName,info.activityInfo.name).flattenToString()).commit();
    }
    private static synchronized void installCrashRecovery(Context context, SharedPreferences recoveryPrefs) {
        if(recoveryInstalled) return;
        recoveryInstalled=true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try { recoveryPrefs.edit().putBoolean("safe_mode",true).commit(); openOriginalHome(context,recoveryPrefs); } catch(Throwable ignored) {}
            if(previous!=null) previous.uncaughtException(thread,error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
    private boolean openOriginalHome() {
        return openOriginalHome(this,prefs);
    }
    private static boolean openOriginalHome(Context context,SharedPreferences recoveryPrefs) {
        String saved = recoveryPrefs.getString("original_home", null);
        ComponentName original = saved==null?null:ComponentName.unflattenFromString(saved);
        if(original==null || original.getPackageName().equals(context.getPackageName())) return false;
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(original)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch(RuntimeException e) { return false; }
    }
    private void showRecovery() {
        dialog().setTitle("Still TV recovery").setMessage("Still TV stopped unexpectedly. Your original launcher is preserved.")
            .setPositiveButton("Open original Home",(d,w)->{ openOriginalHome(); finish(); })
            .setNeutralButton("Try Still TV again",(d,w)->{ prefs.edit().putBoolean("safe_mode",false).commit(); recreate(); })
            .setNegativeButton("System settings",(d,w)->{ openSystem(new Intent(Settings.ACTION_SETTINGS)); finish(); })
            .setCancelable(false).show();
    }

    private void loadApps() {
        if(closed) return;
        if(loading) { refreshPending=true; return; }
        loading=true;
        worker.execute(()->{
            final List<App> result=new ArrayList<>();
            final Map<String,Bitmap> nextArtwork=new HashMap<>();
            try {
                PackageManager pm=getPackageManager();
                Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                Map<String,Boolean> seen=new HashMap<>();
                List<ResolveInfo> candidates=pm.queryIntentActivities(query,0);
                android.util.Log.i("StillTV","Discovered "+candidates.size()+" TV activities");
                for(ResolveInfo info:candidates) {
                    ActivityInfo activity=info.activityInfo;
                    if(activity==null || !activity.enabled || !activity.exported || getPackageName().equals(activity.packageName)
                        || "com.android.tv.settings".equals(activity.packageName) || seen.containsKey(activity.packageName)) continue;
                    try {
                        App app=new App(); app.pkg=activity.packageName;
                        CharSequence label=info.loadLabel(pm); app.label=label==null?app.pkg:label.toString();
                        app.component=new ComponentName(app.pkg,activity.name);
                        app.banner=artwork.get(app.pkg);
                        if(app.banner==null) app.banner=loadArtwork(info,pm);
                        if(app.banner!=null) nextArtwork.put(app.pkg,app.banner);
                        result.add(app); seen.put(app.pkg,true);
                    } catch(RuntimeException error) { android.util.Log.w("StillTV","Unable to load an app",error); }
                }
                Collator collator=Collator.getInstance();
                Collections.sort(result,(a,b)->collator.compare(a.label,b.label));
            } catch(RuntimeException e) {
                android.util.Log.w("StillTV","Unable to discover TV apps",e);
                main.post(()->Toast.makeText(this,"Couldn't refresh apps. Try opening Still TV again.",Toast.LENGTH_LONG).show());
            }
            main.post(()->{
                loading=false;
                if(closed || isFinishing() || isDestroyed()) return;
                apps.clear(); apps.addAll(result);
                artwork.clear(); artwork.putAll(nextArtwork);
                List<String> available=new ArrayList<>(); for(App app:apps) available.add(app.pkg);
                favorites=LauncherState.favoritePackages(prefs.getString("favorites",null),available);
                render();
                if(refreshPending) { refreshPending=false; loadApps(); }
            });
        });
    }
    private Bitmap loadArtwork(ResolveInfo info, PackageManager pm) {
        Drawable drawable=null;
        try { drawable=info.activityInfo.loadBanner(pm); } catch(RuntimeException ignored) {}
        boolean isBanner=drawable!=null;
        if(drawable==null) try { drawable=info.loadIcon(pm); } catch(RuntimeException ignored) {}
        if(drawable==null) return null;
        Bitmap bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        canvas.drawColor(Color.rgb(36,36,40));
        int w=drawable.getIntrinsicWidth(),h=drawable.getIntrinsicHeight();
        if(w<=0 || h<=0) { w=160; h=160; }
        float scale=Math.min((isBanner?320f:106f)/w,(isBanner?180f:106f)/h);
        int dw=Math.max(1,Math.round(w*scale)), dh=Math.max(1,Math.round(h*scale));
        drawable.setBounds((320-dw)/2,(180-dh)/2,(320+dw)/2,(180+dh)/2);
        drawable.draw(canvas);
        return bitmap;
    }

    private void render() {
        if(closed) return;
        main.removeCallbacks(clockTick);
        tileViews.clear(); firstFavorite=null; firstApp=null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setStatusBarColor(color(theme.background));
        scroll=new ScrollView(this);
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setBackgroundColor(color(theme.background));
        scroll.setVerticalScrollBarEnabled(false);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(42),dp(20),dp(42),dp(30));
        content.setClipChildren(false); content.setClipToPadding(false);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);
        int width=Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        tileWidth=Math.max(80,(width-84-5*18)/6);
        addHeader();
        TextView greeting=text(greeting(),34,theme.foreground);
        greeting.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        LinearLayout.LayoutParams greetingLp=new LinearLayout.LayoutParams(-1,-2);
        greetingLp.topMargin=dp(30); content.addView(greeting,greetingLp);
        TextView subtitle=text("What are you in the mood for?",16,theme.muted);
        LinearLayout.LayoutParams subtitleLp=new LinearLayout.LayoutParams(-1,-2);
        subtitleLp.topMargin=dp(8); subtitleLp.bottomMargin=dp(26); content.addView(subtitle,subtitleLp);
        LinearLayout title=new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
        TextView favoritesTitle=text("Favorites",20,theme.foreground);
        title.addView(favoritesTitle,new LinearLayout.LayoutParams(0,-2,1));
        title.addView(text("Hold OK to customize",12,theme.muted)); content.addView(title);
        List<App> pinned=new ArrayList<>();
        for(String pkg:favorites) for(App app:apps) if(app.pkg.equals(pkg)) pinned.add(app);
        if(pinned.isEmpty()) {
            TextView empty=text(loading?"Finding your apps…":"Hold OK on an app below to add a favorite.",14,theme.muted);
            empty.setPadding(0,dp(18),0,dp(24)); content.addView(empty);
        } else addGrid(pinned,true);
        allAppsHeading=text("All apps",20,theme.foreground);
        LinearLayout.LayoutParams allLp=new LinearLayout.LayoutParams(-1,-2); allLp.topMargin=dp(18);
        content.addView(allAppsHeading,allLp); addGrid(apps,false);
        if(apps.isEmpty() && !loading) content.addView(text("No TV apps found. System settings is available above.",14,theme.muted));
        TextView footer=text("Still TV",12,theme.muted); footer.setPadding(0,dp(14),0,0); content.addView(footer);
        clockTick.run();
        View target=lastFocus==null?null:tileViews.get(lastFocus);
        if(target==null) target=firstFavorite!=null?firstFavorite:firstApp;
        if(target!=null) { View focus=target; focus.post(focus::requestFocus); }
    }
    private void addHeader() {
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("bhanureddy.dev",22,theme.foreground);
        brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        header.addView(nav("Home",()->focusSection(firstFavorite!=null?firstFavorite:firstApp)));
        header.addView(nav("Apps",()->focusSection(firstApp)));
        header.addView(nav("Settings",this::showSettings));
        clock=text("",16,theme.foreground); clock.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        header.addView(clock,new LinearLayout.LayoutParams(0,dp(44),1));
        content.addView(header);
    }
    private Button nav(String label, Runnable action) {
        Button button=new Button(this); button.setText(label); button.setTextSize(14); button.setAllCaps(false);
        button.setFocusableInTouchMode(true);
        button.setStateListAnimator(null); button.setElevation(0f);
        button.setTextColor(color(theme.muted)); button.setMinWidth(dp(72)); button.setMinimumHeight(dp(40));
        button.setBackground(shape(theme.background,0));
        button.setOnClickListener(v->action.run());
        button.setOnFocusChangeListener((v,focused)->{
            button.setBackground(shape(focused?theme.focus:theme.background,0));
            button.setTextColor(color(focused?theme.background:theme.muted));
        });
        return button;
    }
    private void focusSection(View target) { if(target!=null) target.requestFocus(); }
    private void addGrid(List<App> list,boolean pinned) {
        for(int start=0; start<list.size(); start+=6) {
            LinearLayout row=new LinearLayout(this); row.setClipChildren(false); row.setClipToPadding(false);
            row.setPadding(0,dp(16),0,dp(8));
            for(int col=0; col<6 && start+col<list.size(); col++) {
                App app=list.get(start+col); View tile=tile(app);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(tileWidth),-2);
                if(col>0) lp.leftMargin=dp(18);
                row.addView(tile,lp);
                if(pinned && firstFavorite==null) firstFavorite=tile;
                if(!pinned && firstApp==null) firstApp=tile;
                if(!tileViews.containsKey(app.pkg)) tileViews.put(app.pkg,tile);
            }
            content.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
    }
    private View tile(App app) {
        LinearLayout tile=new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL); tile.setFocusable(true); tile.setClickable(true);
        tile.setFocusableInTouchMode(true);
        tile.setContentDescription(app.label+". Hold OK for options."); tile.setClipChildren(false);
        FrameLayout imageFrame=new FrameLayout(this);
        imageFrame.setBackground(shape(theme.panel,0)); imageFrame.setClipToOutline(true);
        int height=tileWidth*9/16;
        if(app.banner!=null) {
            ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(app.banner); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            imageFrame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        } else {
            TextView fallback=text(app.label,17,theme.foreground); fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(8),0,dp(8),0); imageFrame.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        }
        // Foreground stroke remains visible over bright or white app banners.
        imageFrame.setForeground(shape("#00000000",0));
        tile.addView(imageFrame,new LinearLayout.LayoutParams(dp(tileWidth),dp(height)));
        TextView label=text(app.label,12,theme.muted); label.setGravity(Gravity.CENTER);
        label.setMaxLines(1); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(-1,dp(30)); labelLp.topMargin=dp(5);
        tile.addView(label,labelLp);
        tile.setOnFocusChangeListener((v,focused)->{
            if(focused) lastFocus=app.pkg;
            imageFrame.setForeground(shape("#00000000",focused?2:0));
            label.setTextColor(color(focused?theme.foreground:theme.muted));
            label.setTypeface(Typeface.create(focused?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
            float scale=focused?1.06f:1f;
            imageFrame.animate().cancel();
            if(Settings.Global.getFloat(getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f) {
                imageFrame.setScaleX(scale); imageFrame.setScaleY(scale);
            } else imageFrame.animate().scaleX(scale).scaleY(scale).setDuration(130).setInterpolator(new DecelerateInterpolator()).start();
        });
        tile.setOnClickListener(v->launch(app));
        tile.setOnLongClickListener(v->{ appMenu(app); return true; });
        tile.setOnKeyListener((v,key,event)->{
            if(key==KeyEvent.KEYCODE_MENU && event.getAction()==KeyEvent.ACTION_UP) { appMenu(app); return true; }
            return false;
        });
        return tile;
    }
    private void launch(App app) {
        try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch(RuntimeException e) { Toast.makeText(this,"Couldn't open "+app.label+". It may have been removed or disabled.",Toast.LENGTH_LONG).show(); loadApps(); }
    }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this,theme==LauncherState.Theme.LIGHT?android.R.style.Theme_Material_Light_Dialog_Alert:android.R.style.Theme_Material_Dialog_Alert));
    }
    private void appMenu(App app) {
        String pin=favorites.contains(app.pkg)?"Remove from favorites":"Add to favorites";
        dialog().setTitle(app.label).setItems(new String[]{"Open",pin,"App information"},(d,which)->{
            if(which==0) launch(app);
            else if(which==1) {
                favorites=LauncherState.toggleFavorite(favorites,app.pkg);
                prefs.edit().putString("favorites",android.text.TextUtils.join(",",favorites)).apply(); render();
            } else openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.pkg)));
        }).show();
    }
    private void showSettings() {
        dialog().setTitle("Still TV settings").setItems(new String[]{"Appearance · "+theme.label,"System settings","Open original Home","Recovery information"},(d,which)->{
            if(which==0) showThemes();
            else if(which==1) openSystem(new Intent(Settings.ACTION_SETTINGS));
            else if(which==2) { if(!openOriginalHome()) Toast.makeText(this,"Original Home is unavailable. Use System settings or ADB recovery.",Toast.LENGTH_LONG).show(); }
            else dialog().setTitle("Your original launcher is preserved").setMessage("Still TV never disables or removes your original launcher. Opening original Home does not change the default. To restore the default or recover a freeze, use the recovery tool documented with this app. Java crash recovery is best effort.").setPositiveButton("OK",null).show();
        }).show();
    }
    private void showThemes() {
        LauncherState.Theme[] values=LauncherState.Theme.values(); String[] labels=new String[values.length];
        for(int i=0;i<values.length;i++) labels[i]=values[i].label;
        dialog().setTitle("Appearance").setSingleChoiceItems(labels,theme.ordinal(),(d,which)->{
            theme=values[which]; prefs.edit().putString("theme",theme.name()).apply(); d.dismiss(); render();
        }).setNegativeButton("Cancel",null).show();
    }
    private void openSystem(Intent intent) {
        try { startActivity(intent); } catch(RuntimeException e) { Toast.makeText(this,"This settings screen isn't available on this TV.",Toast.LENGTH_LONG).show(); }
    }
    private String greeting() {
        int hour=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour<12?"Good morning.":hour<18?"Good afternoon.":"Good evening.";
    }
    private TextView text(String value,int size,String hex) {
        TextView text=new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color(hex)); return text;
    }
    private GradientDrawable shape(String fill,int stroke) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color(fill)); drawable.setCornerRadius(dp(12));
        if(stroke>0) drawable.setStroke(dp(stroke),color(theme.focus)); return drawable;
    }
    private int color(String hex) { return Color.parseColor(hex); }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() { if(scroll==null) { openOriginalHome(); finish(); return; } scroll.smoothScrollTo(0,0); if(firstFavorite!=null) firstFavorite.requestFocus(); else if(firstApp!=null) firstApp.requestFocus(); }
    @Override public void onResume() { super.onResume(); main.removeCallbacks(clockTick); if(clock!=null) clockTick.run(); }
    @Override public void onPause() { main.removeCallbacks(clockTick); super.onPause(); }
    @Override public void onDestroy() {
        closed=true; main.removeCallbacksAndMessages(null); if(receiverRegistered) unregisterReceiver(packages);
        worker.shutdownNow(); artwork.clear(); super.onDestroy();
    }
}
