package dev.still.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

public final class LauncherState {
  private LauncherState() {}

  public static String connectionStatus(
      String transport, boolean internet, boolean validated, boolean captivePortal) {
    if (transport == null) return "Offline";
    String state =
        captivePortal
            ? "Sign-in required"
            : validated ? "Internet connected" : internet ? "Internet unverified" : "No internet";
    return transport + " · " + state;
  }

  private static final List<String> HEADLINES =
      Arrays.asList(
          "Netflix & Chill",
          "Stream & Dream",
          "Binge & Bliss",
          "Popcorn & Play",
          "Pause Real Life",
          "One More Episode",
          "Just One More",
          "Press Play",
          "Enjoy the Show",
          "Movie Night",
          "Cinema Time",
          "Lights Down",
          "Get Comfy",
          "Couch Mode On",
          "Weekend Mode",
          "Watch & Unwind",
          "Stay Tuned",
          "Roll the Credits",
          "Good Vibes Only");

  /** Pick uniformly from the approved phrases, excluding the previous choice. */
  public static String nextHeadline(String previous, Random random) {
    int excluded = HEADLINES.indexOf(previous);
    int index = random.nextInt(HEADLINES.size() - (excluded < 0 ? 0 : 1));
    return HEADLINES.get(excluded >= 0 && index >= excluded ? index + 1 : index);
  }

  public enum Theme {
    DARK("Dark", "#19191C", "#FAFAFC", "#B9B9C0", "#29292E", "#FFFFFF"),
    SUPER_DARK("Super Dark", "#060606", "#FAFAFC", "#B5B5BC", "#19191B", "#FFFFFF"),
    LIGHT("Light", "#F3F3F5", "#161619", "#595960", "#E3E3E8", "#161619");
    public final String label, background, foreground, muted, panel, focus;

    Theme(
        String label,
        String background,
        String foreground,
        String muted,
        String panel,
        String focus) {
      this.label = label;
      this.background = background;
      this.foreground = foreground;
      this.muted = muted;
      this.panel = panel;
      this.focus = focus;
    }

    public static Theme parse(String name) {
      try {
        return valueOf(name);
      } catch (IllegalArgumentException | NullPointerException e) {
        return SUPER_DARK;
      }
    }
  }

  public enum Accent {
    VIOLET("Electric violet", "#B994FF", "#7136C8"),
    CYAN("Electric cyan", "#60DEFF", "#006782"),
    AMBER("Neon amber", "#FFD166", "#8B5200");

    public final String label;
    private final String dark, light;

    Accent(String label, String dark, String light) {
      this.label = label;
      this.dark = dark;
      this.light = light;
    }

    public String color(Theme theme) {
      return theme == Theme.LIGHT ? light : dark;
    }

    public static Accent parse(String name) {
      try {
        return valueOf(name);
      } catch (IllegalArgumentException | NullPointerException ignored) {
        return AMBER;
      }
    }
  }

  public static List<String> favoritePackages(String saved, List<String> available) {
    List<String> preferred =
        Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
            "com.apple.atve.androidtv.appletv",
            "com.jio.media.stb.ondemand");
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String p : saved == null ? preferred : Arrays.asList(saved.split(",")))
      if (available.contains(p)) result.add(p);
    if (saved == null && result.isEmpty())
      for (String p : available) {
        result.add(p);
        if (result.size() == 6) break;
      }
    return new ArrayList<>(result);
  }

  public static List<String> toggleFavorite(List<String> current, String pkg) {
    LinkedHashSet<String> result = new LinkedHashSet<>(current);
    if (!result.remove(pkg)) result.add(pkg);
    return new ArrayList<>(result);
  }
}
