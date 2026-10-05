#!/usr/bin/env python3
"""Exercise predictive back geometry and device corners without an Android APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class PredictiveBackBehaviorTest(unittest.TestCase):
    def test_gesture_settlement_and_device_corners(self) -> None:
        animation = read(
            "app/src/main/java/org/thunderdog/challegram/navigation/PredictiveBackAnimation.java"
        )
        animation = animation[animation.index("final class PredictiveBackAnimation"):]
        animation = animation.replace(
            "final class PredictiveBackAnimation", "static final class PredictiveBackAnimation", 1
        )
        root = read("app/src/main/java/org/thunderdog/challegram/navigation/RootLayout.java")
        corners = java_method(root, "private void prepareBackCorners ()")
        surface = java_method(root, "private int saveBackSurface (")
        harness = r"""
import java.util.Arrays;
public class PredictiveBackTest {
  static class RectF {
    float left, top, right, bottom;
    void set(float l, float t, float r, float b) { left=l; top=t; right=r; bottom=b; }
    void set(RectF r) { set(r.left, r.top, r.right, r.bottom); }
    float width() { return right-left; }
    float height() { return bottom-top; }
    void offset(float x, float y) { left+=x; right+=x; top+=y; bottom+=y; }
    void inset(float x, float y) { left+=x; right-=x; top+=y; bottom-=y; }
  }
  interface Interpolator { float getInterpolation(float value); }
  static class PathInterpolatorCompat {
    // Geometry invariants and endpoints do not depend on intermediate easing.
    static Interpolator create(float a, float b, float c, float d) { return t -> t; }
    static Interpolator create(Path path) { return t -> t; }
  }
  static class Path {
    enum Direction { CW }
    float[] radii;
    void moveTo(float x, float y) {}
    void cubicTo(float a, float b, float c, float d, float e, float f) {}
    void rewind() {}
    void addRoundRect(RectF bounds, float[] radii, Direction direction) {
      this.radii = radii.clone();
    }
  }
  static class Build {
    static class VERSION { static int SDK_INT = 31; }
    static class VERSION_CODES { static final int S = 31; }
  }
  static class RoundedCorner {
    static final int POSITION_TOP_LEFT=0, POSITION_TOP_RIGHT=1;
    static final int POSITION_BOTTOM_RIGHT=2, POSITION_BOTTOM_LEFT=3;
    final int radius;
    RoundedCorner(int radius) { this.radius=radius; }
    int getRadius() { return radius; }
  }
  static class WindowInsets {
    final RoundedCorner[] corners;
    WindowInsets(RoundedCorner... corners) { this.corners=corners; }
    RoundedCorner getRoundedCorner(int position) { return corners[position]; }
  }
  static class Screen { static float dp(float value) { return value*2; } }
  static class Canvas {
    static final int ALL_SAVE_FLAG=31;
    float[] radii;
    int saveLayerAlpha(float l, float t, float r, float b, int alpha, int flags) { return 1; }
    void clipPath(Path path) { radii=path.radii.clone(); }
    void translate(float x, float y) {}
    void scale(float x, float y) {}
  }
  static class RootLayout {
    final float[] backCornerRadii = new float[8];
    final float[] backScaledCornerRadii = new float[8];
    final Path backClip = new Path();
    WindowInsets insets;
    int insetReads;
    WindowInsets getRootWindowInsets() { insetReads++; return insets; }
    int getWidth() { return 1000; }
    int getHeight() { return 2000; }
    __CORNERS__
    __SURFACE__
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  static void close(float actual, float expected, String message) {
    check(Math.abs(actual-expected)<.01f, message+": "+actual+" != "+expected);
  }
  static void checkRadii(float[] actual, float... expected) {
    check(actual.length==expected.length, "Corner count");
    for (int i=0; i<actual.length; i++) close(actual[i], expected[i], "Corner "+i);
  }
  public static void main(String[] args) {
    PredictiveBackAnimation animation = new PredictiveBackAnimation();
    for (boolean right : new boolean[] {false, true}) {
      animation.start(1000, 2000, 16, 192, right);
      close(animation.getClosingRect().width(), 1000, "Start at full width");
      close(animation.getClosingRect().height(), 2000, "Start at full height");
      for (float progress : new float[] {0, .25f, .5f, .75f, 1}) {
        for (float drag : new float[] {-4000, 0, 4000}) {
          animation.updateGesture(progress, drag);
          RectF closing = animation.getClosingRect(), entering = animation.getEnteringRect();
          close(closing.width()/1000, closing.height()/2000, "Preserve aspect ratio");
          close(entering.width(), closing.width(), "Both cards share the scale");
          check(closing.left>=-.01 && closing.right<=1000.01, "Stay within horizontal bounds");
          check(closing.top>=-.01 && closing.bottom<=2000.01, "Clamp vertical dragging");
          if (progress==1) {
            check(closing.width()<=860 && closing.width()>=800, "Make zoom-out pronounced");
          }
        }
      }
      animation.updateGesture(1, 0);
      animation.startCommit(1, 4);
      for (float fraction : new float[] {0, .25f, .5f, .75f, 1}) {
        animation.updateCommit(fraction, fraction*.45f);
        check(animation.getClosingRect().width()>0, "Commit keeps a drawable surface");
        check(animation.getEnteringRect().width()<=1000.01, "Destination cannot overshoot");
      }
      close(animation.getEnteringRect().left, 0, "Commit restores destination position");
      close(animation.getEnteringRect().width(), 1000, "Commit restores full destination width");
      close(animation.getEnteringRect().height(), 2000, "Commit restores full destination height");
      animation.start(1000, 2000, 16, 192, right);
      float previous=1;
      for (int step=0; step<=100; step++) {
        float factor=PredictiveBackAnimation.cancelProgress(step/100f);
        check(factor<=previous+.0001 && factor>=-.0001, "Cancellation returns monotonically");
        previous=factor;
      }
      animation.updateGesture(PredictiveBackAnimation.cancelProgress(1), 0);
      close(animation.getClosingRect().left, 0, "Cancellation restores position");
      close(animation.getClosingRect().width(), 1000, "Cancellation restores full width");
    }

    RootLayout root = new RootLayout();
    root.insets = new WindowInsets(new RoundedCorner(100), new RoundedCorner(80),
      new RoundedCorner(20), new RoundedCorner(40));
    root.prepareBackCorners();
    checkRadii(root.backCornerRadii, 100,100,80,80,20,20,40,40);
    Canvas canvas = new Canvas();
    RectF bounds = new RectF();
    bounds.set(50,100,900,1800);
    root.saveBackSurface(canvas, bounds, 1);
    checkRadii(canvas.radii, 85,85,68,68,17,17,34,34);

    // Refresh device corners on a subsequent gesture, including square corners.
    root.insets = new WindowInsets(null, null, new RoundedCorner(120), new RoundedCorner(120));
    root.prepareBackCorners();
    checkRadii(root.backCornerRadii, 0,0,0,0,120,120,120,120);
    root.insets = new WindowInsets(null, null, null, null);
    root.prepareBackCorners();
    check(Arrays.equals(root.backCornerRadii, new float[8]), "Square devices remain square");
    root.insets = null;
    root.prepareBackCorners();
    checkRadii(root.backCornerRadii, 56,56,56,56,56,56,56,56);
    Build.VERSION.SDK_INT=30;
    int reads=root.insetReads;
    root.prepareBackCorners();
    check(root.insetReads==reads, "Older devices do not access rounded-corner APIs");
    checkRadii(root.backCornerRadii, 56,56,56,56,56,56,56,56);
  }
  __ANIMATION__
}
"""
        harness = (harness.replace("__CORNERS__", corners).replace("__SURFACE__", surface)
                   .replace("__ANIMATION__", animation))
        with tempfile.TemporaryDirectory(prefix="predictive-back-test-") as directory:
            java = Path(directory) / "PredictiveBackTest.java"
            java.write_text(harness, encoding="utf-8")
            result = subprocess.run(
                ["javac", "--release", "17", "-d", directory, str(java)],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "PredictiveBackTest"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
