import dev.still.launcher.LauncherState;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public final class LauncherStateTest {
  public static void main(String[] args) {
    check(LauncherState.Theme.parse(null) == LauncherState.Theme.SUPER_DARK, "missing theme");
    check(LauncherState.Theme.parse("broken") == LauncherState.Theme.SUPER_DARK, "invalid theme");
    check(LauncherState.Theme.parse("LIGHT") == LauncherState.Theme.LIGHT, "light theme");
    check(
        LauncherState.favoritePackages("a,b,a,removed", Arrays.asList("a", "b", "c"))
            .equals(Arrays.asList("a", "b")),
        "stale and duplicate favorites");
    check(
        LauncherState.favoritePackages("", Arrays.asList("a")).isEmpty(),
        "explicit empty favorites");
    check(
        LauncherState.favoritePackages(null, Arrays.asList("a", "b"))
            .equals(Arrays.asList("a", "b")),
        "first-run fallback");
    check(
        LauncherState.toggleFavorite(Arrays.asList("a", "b"), "a").equals(Arrays.asList("b")),
        "remove favorite");
    check(
        LauncherState.toggleFavorite(Collections.singletonList("a"), "b")
            .equals(Arrays.asList("a", "b")),
        "append favorite");
    for (LauncherState.Theme theme : LauncherState.Theme.values()) {
      check(contrast(theme.background, theme.foreground) > 7.0, "text contrast " + theme);
      check(contrast(theme.background, theme.focus) > 3.0, "focus contrast " + theme);
      for (LauncherState.Accent accent : LauncherState.Accent.values()) {
        check(
            contrast(theme.background, accent.color(theme)) > 4.5,
            "accent contrast " + theme + accent);
      }
    }
    check(LauncherState.Accent.parse(null) == LauncherState.Accent.AMBER, "missing accent");
    check(LauncherState.Accent.parse("broken") == LauncherState.Accent.AMBER, "invalid accent");
    check(LauncherState.Accent.parse("CYAN") == LauncherState.Accent.CYAN, "saved accent");
    System.out.println("Launcher state: 26 checks passed");
    checkHeadlineRotation();
  }

  private static void checkHeadlineRotation() {
    Set<String> approved =
        new HashSet<>(
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
                "Good Vibes Only"));
    Set<String> seen = new HashSet<>();
    Random random = new Random(2026);
    String previous = null;
    for (int reload = 0; reload < 1000; reload++) {
      String next = LauncherState.nextHeadline(previous, random);
      check(approved.contains(next), "only approved phrases: " + next);
      check(!next.equals(previous), "no immediate repeat after " + previous);
      seen.add(next);
      previous = next;
    }
    check(seen.equals(approved), "every approved phrase can appear");
    check(
        approved.contains(LauncherState.nextHeadline("removed phrase", random)),
        "old saved phrase is replaced safely");
    for (String phrase : approved) {
      int words = phrase.split(" ").length;
      check(words >= 2 && words <= 3, "two or three words: " + phrase);
    }
    System.out.println("Header rotation: 19 approved phrases and 1000 reloads passed");
  }

  private static double contrast(String a, String b) {
    double x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  }

  private static double luminance(String s) {
    int n = Integer.parseInt(s.substring(1), 16);
    double[] c = {(n >> 16) & 255, (n >> 8) & 255, n & 255};
    for (int i = 0; i < 3; i++) {
      c[i] /= 255;
      c[i] = c[i] <= 0.04045 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
    }
    return c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722;
  }

  private static void check(boolean result, String message) {
    if (!result) throw new AssertionError(message);
  }
}
