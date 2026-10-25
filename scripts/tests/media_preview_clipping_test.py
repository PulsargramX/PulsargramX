"""Exercise media clipping and layout interleavings without Android or Gradle."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/org/thunderdog/challegram/data/MediaWrapper.java"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class MediaPreviewClippingTest(unittest.TestCase):
    def check_layout(self, source, scenario="bounds"):
        fields = source[source.index("  private int lastLeft"):
                        source.index("  private final Tdlib tdlib")]
        roundings = source[source.index("  private static final int ROUND_TOP_LEFT"):
                           source.index("  private boolean useBubbles")]
        methods = "\n".join(java_method(source, signature) for signature in (
            "private void layoutPath (", "public void buildContent (",
            "public int getCellLeft (", "public int getCellTop (",
            "public int getCellRight (", "public int getCellBottom ("))
        # Run the actual draw setup through clipping, before selection/receiver painting.
        draw = java_method(source, "public <T extends View & DrawableProvider> void draw (")
        draw = draw[:draw.index("    int selectionColor")]
        fixture = r"""
class RectF {
  float left, top, right, bottom;
  void set(float l, float t, float r, float b) {left=l; top=t; right=r; bottom=b;}
  void set(RectF r) {set(r.left, r.top, r.right, r.bottom);}
}
class Path {
  final RectF bounds = new RectF();
  float topLeft, topRight, bottomRight, bottomLeft;
  int builds;
  void reset() {}
}
class Build {
  static class VERSION {static int SDK_INT = 35;}
  static class VERSION_CODES {static final int KITKAT = 19;}
}
class Paints {
  static final RectF scratch = new RectF();
  static RectF getRectF() {return scratch;}
}
class DrawAlgorithms {
  static boolean interleave;
  static void buildPath(Path path, RectF bounds, float tl, float tr, float br, float bl) {
    if (interleave) {
      // Force another layout to overwrite shared scratch after bounds were set,
      // before the path builder reads them. Never depend on random thread timing.
      Thread layout = new Thread(() -> Paints.getRectF().set(10, 20, 210, 100));
      layout.start();
      try {layout.join();} catch (InterruptedException e) {throw new AssertionError(e);}
    }
    path.bounds.set(bounds);
    path.topLeft=tl; path.topRight=tr; path.bottomRight=br; path.bottomLeft=bl;
    path.builds++;
  }
}
class Canvas {
  final RectF clip = new RectF();
  int clips;
}
class ViewSupport {
  static int clipPath(Canvas c, Path path) {c.clip.set(path.bounds); return ++c.clips;}
}
class Screen {static int dp(float value) {return (int) value;}}
class View {}
interface DrawableProvider {}
class Receiver {}
class StringUtils {static boolean isEmpty(String value) {return value == null || value.isEmpty();}}
class Progress {
  boolean isLoaded() {return true;}
  boolean isVideoStreaming() {return false;}
}
class Source {
  boolean fullWidth;
  boolean useFullWidth() {return fullWidth;}
  float getSelectionFactor(Object animator) {return 0;}
}
public class MediaPreviewClippingHarness {
  FIELDS
  ROUNDINGS
  METHODS
  DRAW
  }
  Path path = new Path();
  final Source source = new Source();
  Object selectionAnimator;
  String duration, durationAlternative;
  int durationAlternativeWidth, radius = 24;
  boolean isVideo() {return false;}
  Progress getFileProgress() {return new Progress();}
  void trimDuration() {}
  void trimDoubleDuration(String text, int width) {}
  int getRadius() {return radius;}

  void draw(Canvas c, int x, int y) {draw(null, c, x, y, null, null, 1f);}
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  static void bounds(RectF r, int l, int t, int right, int bottom) {
    check(r.left == l && r.top == t && r.right == right && r.bottom == bottom,
      "Wrong media clip: " + r.left + "," + r.top + "," + r.right + "," + r.bottom +
      "; expected " + l + "," + t + "," + right + "," + bottom);
  }
  public static void main(String[] args) throws Exception {
    MediaPreviewClippingHarness media = new MediaPreviewClippingHarness();
    Canvas canvas = new Canvas();
    if (args[0].equals("scratch")) {
      DrawAlgorithms.interleave = true;
      media.buildContent(500, 300);
      media.draw(canvas, 10, 20);
      bounds(canvas.clip, 10, 20, 510, 320);
      media.draw(canvas, 10, 20);
      bounds(canvas.clip, 10, 20, 510, 320);
      return;
    }
    media.buildContent(300, 180);
    media.draw(canvas, 10, 20);
    bounds(canvas.clip, 10, 20, 310, 200);
    media.draw(canvas, 10, 20);
    check(media.path.builds == 1, "Unchanged previews must reuse their clip");

    media.buildContent(350, 180);
    media.draw(canvas, 10, 20);
    bounds(canvas.clip, 10, 20, 360, 200);
    media.buildContent(350, 220);
    media.draw(canvas, 10, 20);
    bounds(canvas.clip, 10, 20, 360, 240);
    media.draw(canvas, 30, 40);
    bounds(canvas.clip, 30, 40, 380, 260);
    media.radius = 16;
    media.draw(canvas, 30, 40);
    check(media.path.topLeft == 8, "Theme radius changes must rebuild the clip");

    int builds = media.path.builds;
    Thread background = new Thread(() -> media.setNeedRound(true, false, false, true));
    background.start();
    background.join();
    check(media.path.builds == builds, "Background layout must not mutate the drawing path");
    media.draw(canvas, 30, 40);
    check(media.path.builds == builds + 1 && media.path.topLeft == 8 &&
      media.path.topRight == 0 && media.path.bottomRight == 0 && media.path.bottomLeft == 8,
      "Changed album corners must rebuild on the next draw");

    media.source.fullWidth = true;
    media.buildContent(400, 240);
    int clips = canvas.clips;
    media.draw(canvas, 30, 40);
    check(canvas.clips == clips, "Full width media must skip wrapper clipping");
    check(media.getCellLeft() == 30 && media.getCellTop() == 40 &&
      media.getCellRight() == 430 && media.getCellBottom() == 280,
      "Unclipped media must retain current bounds for hit testing and transitions");
    media.source.fullWidth = false;
    media.draw(canvas, 30, 40);
    bounds(canvas.clip, 30, 40, 430, 280);

    media.path = null;
    clips = canvas.clips;
    media.buildContent(320, 200);
    media.draw(canvas, 5, 8);
    check(canvas.clips == clips && media.getCellRight() == 325 && media.getCellBottom() == 208,
      "Media without a clipping path must still update its bounds");
    System.out.println("Media clipping bounds, cache invalidation and layout interleavings passed");
  }
}
"""
        for name, value in (("FIELDS", fields), ("ROUNDINGS", roundings),
                            ("METHODS", methods), ("DRAW", draw)):
            fixture = fixture.replace(name, value)
        with tempfile.TemporaryDirectory(prefix="media-preview-clipping-") as directory:
            java = Path(directory) / "MediaPreviewClippingHarness.java"
            java.write_text(fixture, encoding="utf-8")
            result = subprocess.run(["javac", "-encoding", "UTF-8", str(java)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", directory,
                                     "MediaPreviewClippingHarness", scenario],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_media_clip_bounds_follow_drawn_content(self):
        self.check_layout(SOURCE.read_text(encoding="utf-8"))

    def test_other_layout_cannot_replace_media_clip(self):
        self.check_layout(SOURCE.read_text(encoding="utf-8"), "scratch")


if __name__ == "__main__":
    unittest.main()
