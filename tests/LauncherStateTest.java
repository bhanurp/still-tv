import dev.still.launcher.LauncherState;
import java.util.Arrays;
import java.util.Collections;

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
      check(contrast(theme.background, theme.brand) > 4.5, "brand contrast " + theme);
    }
    System.out.println("Launcher state: 17 checks passed");
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
