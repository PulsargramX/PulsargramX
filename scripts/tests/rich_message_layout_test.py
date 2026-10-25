"""Exercise rich-message preview measurement and drawing without an APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class RichMessageLayoutTest(unittest.TestCase):
  def test_partial_preview_skips_unmeasured_media_and_expands(self):
    source = read("app/src/main/java/org/thunderdog/challegram/data/RichMessageLayout.java")
    media = source[source.index("private final class MediaNode extends BlockNode {"):]
    methods = "\n".join(java_method(source, signature) for signature in (
      "public int measure (", "public void draw (@NonNull MessageView view,",
      "private boolean isExposed ("))
    media_methods = "\n".join(java_method(media, signature) for signature in (
      "public int measure (", "void draw (MessageView view,"))
    harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;

public class RichMessageLayoutHarness {
  @interface NonNull {}
  static class Rect {
    int top, bottom;
  }
  static class RectF {
    float left, top, right, bottom;
    void set(float l, float t, float r, float b) {left=l; top=t; right=r; bottom=b;}
    void setEmpty() {set(0, 0, 0, 0);}
    boolean isEmpty() {return left >= right || top >= bottom;}
    float centerY() {return (top + bottom) / 2f;}
  }
  static class Canvas {
    void getClipBounds(Rect bounds) {bounds.top = 0; bounds.bottom = 5000;}
    void clipRect(int l, int t, int r, int b) {}
    void drawRoundRect(RectF bounds, int x, int y, Paint paint) {}
    void drawText(String text, float x, float y, Paint paint) {}
  }
  static class Paint {
    float measureText(String text) {return text.length() * 6;}
    float ascent() {return -10;}
    float descent() {return 2;}
  }
  static class Paints {
    static Paint getRegularTextPaint(float size, int color) {return new Paint();}
    static Paint fillingPaint(int color) {return new Paint();}
    static RectF getRectF() {return new RectF();}
  }
  static class Screen {
    static int dp(float value) {return Math.round(value);}
    static float density() {return 1;}
  }
  static class Views {
    static int save(Canvas canvas) {return 0;}
    static void restore(Canvas canvas, int save) {}
  }
  static class ThemeManager {
    static ThemeManager instance() {return new ThemeManager();}
    int currentThemeId() {return 1;}
  }
  static class Lang {
    static Locale dateFormatLocale() {return Locale.US;}
  }
  static class UI {
    static boolean needAmPm() {return false;}
  }
  static class TGMessage {
    TGMessage context() {return this;}
    TGMessage getResources() {return this;}
    TGMessage getConfiguration() {return this;}
    float fontScale = 1;
    static TGMessage getTextStyleProvider() {return new TGMessage();}
    float getTextSize() {return 16;}
  }
  static class TextStreamingAnimator {
    static class Block {}
    void saveCursor() {}
    void setBlocks(ArrayList<Block> blocks) {}
    boolean isVisible(String key) {return true;}
    float alpha(String key) {return 1;}
  }
  static class MessageView {}
  static class Receiver {}
  static class DoubleImageReceiver {}
  static class ImageReceiver extends Receiver {
    void setBounds(int l, int t, int r, int b) {}
    void setRadius(int radius) {}
    void setPaintAlpha(float alpha) {}
    void draw(Canvas canvas) {}
    void restorePaintAlpha() {}
  }
  static class ComplexReceiver {
    DoubleImageReceiver getPreviewReceiver(long key) {return new DoubleImageReceiver();}
    ImageReceiver getImageReceiver(long key) {return new ImageReceiver();}
    Receiver getGifReceiver(long key) {return new Receiver();}
  }
  static class RichMessageFlattener {
    static long receiverKey(String path, int index) {return index;}
  }
  static class MediaWrapper {
    int draws;
    int getContentWidth() {return 320;}
    int getContentHeight() {return 200;}
    void buildContent(int width, int height) {}
    boolean needGif() {return false;}
    void draw(MessageView view, Canvas canvas, int x, int y,
              DoubleImageReceiver preview, Receiver target, float alpha) {draws++;}
  }
  static class Fallback {
    void prepare(int width) {}
    int getHeight() {return 20;}
    void draw(Canvas canvas, int x, int right, int z, int y,
              Object unused, float alpha, ComplexReceiver receiver) {}
  }
  static class AudioResult {
    void layout(int width, Object unused) {}
    int getHeight() {return 40;}
    void drawRichMessage(MessageView view, Canvas canvas, int x, int y, int width,
                         int height, ComplexReceiver receiver, long key, float alpha) {}
  }
  static class CollageContext {
    int getHeight(int width, int maxHeight) {return 200;}
    void draw(MessageView view, Canvas canvas, int x, int y, ComplexReceiver receiver) {}
  }
  static class State {int slideshowIndex;}
  static class Spec {
    TdApi.PageBlock block;
    String path = "media";
    State state = new State();
  }
  static class BlockNode {
    boolean visible = true;
    int measuredAvailableWidth = -1;
    int width, height, top, contentInset, desiredHeight, draws;
    RectF viewport = new RectF();
    Spec spec = new Spec();
    int indent() {return 0;}
    int measure(int width) {this.width = width; return height = desiredHeight;}
    void setSpoilersRevealed(boolean revealed, boolean animated) {}
    void collectStreamingBlocks(ArrayList<TextStreamingAnimator.Block> blocks) {}
    String streamingKey() {return "block";}
    void drawChrome(Canvas canvas, int x, int y, int width, float alpha) {}
    void draw(MessageView view, Canvas canvas, int x, int y,
              ComplexReceiver receiver, float alpha) {draws++;}
  }
  static class AnchorNode extends BlockNode {}
  static class MediaNode extends BlockNode {
    final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
    ImageReceiver mapImage;
    AudioResult audioResult;
    Fallback fallback;
    CollageContext collageContext;
    String[] slideLabels;
    int columns, cellWidth, cellHeight;
    MediaNode(boolean slideshow) {
      spec.block = slideshow ? new TdApi.PageBlockSlideshow() : new TdApi.PageBlockPhoto();
      wrappers.add(new MediaWrapper());
      if (slideshow) {
        wrappers.add(new MediaWrapper());
        slideLabels = new String[] {"1/2", "2/2"};
      }
    }
    MEDIA_METHODS
  }
  static final int PARTIAL_MAX_HEIGHT_DP = 900, GAP_DP = 5, PARTIAL_BUTTON_HEIGHT_DP = 48;
  final TGMessage owner = new TGMessage();
  final Rect clipBounds = new Rect();
  final ArrayList<BlockNode> nodes = new ArrayList<>();
  final Map<Object, Integer> listWidths = new HashMap<>();
  final TdApi.RichMessage message = new TdApi.RichMessage();
  TextStreamingAnimator streamingAnimator;
  int measuredWidth, measuredHeight, visibleHeight, layoutRevision, showMoreDraws;
  float measuredDensity = -1, measuredFontScale = -1, measuredTextSize = -1;
  int measuredThemeId = Integer.MIN_VALUE;
  String measuredLocale = "", measuredTimeZone = "";
  boolean measuredUseAmPm, spoilersRevealed;
  void rebuildNodes() {throw new AssertionError("Unexpected environment change");}
  void rebuildSelectionLeaves() {}
  void downloadExposedMedia() {}
  void drawShowMore(Canvas canvas, int x, int y, float alpha) {
    check(y == 20 + PARTIAL_MAX_HEIGHT_DP, "Show more stays at the preview boundary");
    showMoreDraws++;
  }
  METHODS
  void render() {draw(new MessageView(), new Canvas(), 10, 20, new ComplexReceiver(), 1);}
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    for (boolean slideshow : new boolean[] {false, true}) {
      for (int textHeight : new int[] {895, 900, 1200}) {
        RichMessageLayoutHarness layout = new RichMessageLayoutHarness();
        BlockNode text = new BlockNode();
        text.desiredHeight = textHeight;
        MediaNode media = new MediaNode(slideshow);
        MediaNode later = new MediaNode(slideshow);
        layout.nodes.addAll(Arrays.asList(text, media, later));
        check(layout.measure(320) == 948, "Cap the preview and reserve Show more");
        check(media.height == 0 && media.columns == 0 && later.columns == 0,
          "Media beyond the cap must remain unmeasured");
        layout.render();
        check(text.draws == 1 && media.wrappers.get(0).draws == 0 &&
          later.wrappers.get(0).draws == 0, "Draw only the measured preview");
        check(media.viewport.isEmpty() && layout.showMoreDraws == 1,
          "Capped media has no viewport and Show more remains visible");

        layout.message.isFull = true;
        layout.measuredWidth = 0;
        layout.measure(320);
        layout.render();
        check(media.columns > 0 && media.height > 0 &&
          media.wrappers.get(0).draws == 1 && later.wrappers.get(0).draws == 1,
          "Full content measures and draws the previously capped media");
        check(!media.viewport.isEmpty(), "Expanded media has a viewport");

        layout.message.isFull = false;
        layout.measuredWidth = 0;
        layout.measure(320);
        layout.render();
        check(media.wrappers.get(0).draws == 1 && media.viewport.isEmpty(),
          "Capping reused media clears its previous viewport and skips drawing");
      }
      RichMessageLayoutHarness layout = new RichMessageLayoutHarness();
      BlockNode text = new BlockNode();
      text.desiredHeight = 890;
      MediaNode media = new MediaNode(slideshow);
      layout.nodes.addAll(Arrays.asList(text, media));
      layout.measure(320);
      layout.render();
      check(media.top == 895 && media.wrappers.get(0).draws == 1,
        "Media that starts before the cap still draws");
      check(media.viewport.bottom == 920, "Clip the crossing media at the preview boundary");
    }
  }
}
'''.replace("MEDIA_METHODS", media_methods).replace("METHODS", methods)
    with tempfile.TemporaryDirectory(prefix="rich-message-layout-") as directory:
      path = Path(directory) / "RichMessageLayoutHarness.java"
      path.write_text(harness, encoding="utf-8")
      sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java", path]
      for name, code in {
        "Nullable": "public @interface Nullable {}",
        "IntDef": "public @interface IntDef {long[] value() default {};}",
      }.items():
        path = Path(directory) / "androidx/annotation" / (name + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package androidx.annotation; " + code, encoding="utf-8")
        sources.append(path)
      compiled = subprocess.run([
        "javac", "-encoding", "UTF-8", "-d", directory,
        *map(str, sources)
      ], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
      result = subprocess.run(["java", "-cp", directory, "RichMessageLayoutHarness"],
                              capture_output=True, text=True)
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
  unittest.main()
