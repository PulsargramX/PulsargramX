"""Exercise shared profile button animation ownership without an Android APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ProfileFloatingButtonTest(unittest.TestCase):
  def test_navigation_cancels_profile_animation_and_resets_visibility(self):
    source = read("app/src/main/java/org/thunderdog/challegram/navigation/FloatingButton.java")
    methods = "\n".join(java_method(source, signature) for signature in (
      "private void cancelVisibilityAnimation ()",
      "public void initHeightFactor (",
      "public void setHeightFactor (",
    ))
    scroll = read("app/src/main/java/org/thunderdog/challegram/navigation/ComplexRecyclerView.java")
    scroll_method = java_method(scroll, "private void updateScrollFactor (")
    harness = r'''
public class ProfileFloatingButtonTest {
  static class ValueAnimator {
    Runnable pending;
    void cancel() { pending = null; }
    void finish() { if (pending != null) { pending.run(); pending = null; } }
  }
  static class MathUtils {
    static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
  }
  static class Size {
    static int getHeaderPortraitSize() { return 56; }
    static int getHeaderBigPortraitSize(boolean ignored) { return 200; }
    static int getHeaderSizeDifference(boolean ignored) { return 144; }
  }
  static class AnimatorUtils { static Object ANTICIPATE_OVERSHOOT_INTERPOLATOR; }
  static class Views {
    static void animate(Button b, float x, float y, float alpha,
                        long duration, Object interpolator, Object listener) {
      b.animation.pending = () -> {
        b.setScaleX(x); b.setScaleY(y); b.setAlpha(alpha);
      };
    }
  }
  static class Button {
    static final long OVERSHOOT_DURATION = 380;
    static final float MIN_SCALE = .4f;
    boolean isShowing, touchEnabled, translateFully;
    float heightFactor = -1, scaleFactor = -1, heightDiff = -144;
    float fromHeightFactor, toHeightFactor, alpha, scaleX, scaleY, translationY;
    ValueAnimator visibilityAnimator;
    final ValueAnimator animation = new ValueAnimator();
    ValueAnimator animate() { return animation; }
    void setTranslationY(float value) { translationY = value; }
    void setAlpha(float value) { alpha = value; }
    void setScaleX(float value) { scaleX = value; }
    void setScaleY(float value) { scaleY = value; }
    void setTouchEnabled(boolean value) { touchEnabled = value; }
    Object getHideListener() { return null; }
    METHODS
  }
  static class View { int getTop() { return 0; } }
  static class Header {
    int updates;
    void setScaleFactor(float a, float b, float c, boolean d) { updates++; }
    void setBackgroundHeight(int height) { updates++; }
  }
  static class Target {
    boolean custom;
    final Header headerView = new Header();
    boolean inCustomMode() { return custom; }
    int getMaximumHeaderHeight() { return 200; }
    int getFloatingButtonId() { return 1; }
  }
  static class LayoutManager { View findViewByPosition(int index) { return new View(); } }
  static class Scroll {
    static final int FLAG_FORCE = 2;
    boolean factorLocked;
    int flags;
    float scrollFactor;
    final Header headerView = new Header();
    final Target target = new Target();
    final Button floatingButton;
    Scroll(Button button) { floatingButton = button; }
    LayoutManager getLayoutManager() { return new LayoutManager(); }
    SCROLL_METHOD
  }
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    Button b = new Button();
    b.initHeightFactor(1f, 0f, 1f, false, false);
    b.setHeightFactor(0f, 0f, true); // Collapse the profile header.
    b.animation.finish();
    b.setHeightFactor(.2f, 0f, true); // Scroll back: start a show animation.
    check(b.animation.pending != null, "Expected a running show animation");
    b.initHeightFactor(.2f, 0f, 1f, false, false); // Start leaving the profile.
    b.setHeightFactor(0f, 0f, false); // Navigation has hidden the button.
    b.animation.finish();
    check(b.alpha == 0f && !b.touchEnabled, "Late animation resurrected profile button");

    // Equal header heights must still apply visibility (collapsed profiles and resets).
    b.initHeightFactor(0f, 0f, 1f, false, false);
    check(b.alpha == 1f && b.touchEnabled, "Equal-height init ignored visibility");
    b.initHeightFactor(0f, 0f, 0f, false, false);
    check(b.alpha == 0f && !b.touchEnabled, "Screen reset kept the profile button");

    // Cached factors must not prevent correcting state written by an animation.
    b.setAlpha(1f);
    b.setHeightFactor(0f, 0f, false);
    check(b.alpha == 0f, "Repeated navigation state failed to reset alpha");

    Scroll scroll = new Scroll(b);
    scroll.factorLocked = true;
    scroll.updateScrollFactor(true); // A posted update arriving after profile blur.
    check(scroll.headerView.updates == 0 && b.animation.pending == null,
      "Locked profile changed shared UI");
    scroll.flags = Scroll.FLAG_FORCE;
    scroll.updateScrollFactor(false);
    check(scroll.scrollFactor == 1f && scroll.headerView.updates == 0,
      "Forced geometry query must compute without changing UI");
    scroll.flags = 0;
    scroll.factorLocked = false;
    scroll.updateScrollFactor(true);
    check(scroll.headerView.updates == 1 && b.animation.pending != null,
      "Focused profile must still animate its button");
  }
}
'''.replace("METHODS", methods).replace("SCROLL_METHOD", scroll_method)
    with tempfile.TemporaryDirectory() as directory:
      path = Path(directory) / "ProfileFloatingButtonTest.java"
      path.write_text(harness)
      subprocess.run(["javac", str(path)], check=True, capture_output=True, text=True)
      subprocess.run(["java", "-cp", directory, "ProfileFloatingButtonTest"],
                     check=True, capture_output=True, text=True)


if __name__ == "__main__":
  unittest.main()
