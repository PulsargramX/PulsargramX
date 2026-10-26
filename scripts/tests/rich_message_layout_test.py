"""Exercise rich-message previews and slideshow gestures without an APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class RichMessageLayoutTest(unittest.TestCase):
  def test_preview_measurement_drawing_and_slideshow_swipes(self):
    source = read("app/src/main/java/org/thunderdog/challegram/data/RichMessageLayout.java")
    media = source[source.index("private final class MediaNode extends BlockNode "):]
    methods = "\n".join(java_method(source, signature) for signature in (
      "public int measure (", "public void draw (@NonNull MessageView view,",
      "private boolean isExposed ("))
    media_methods = "\n".join(java_method(media, signature) for signature in (
      "public int measure (", "void draw (MessageView view,",
      "public boolean onTouchEvent (", "private void updateSlideOffset (",
      "private void animateSlideOffset (", "private void resetSlideAnimation (",
      "private void recycleSlideVelocity (", "public void onFactorChanged (",
      "private void cancelMediaTouch (", "public void detach (", "public void performDestroy ("))
    harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;

public class RichMessageLayoutHarness {
  @interface NonNull {}
  @interface Nullable {}
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
    int clipLeft, clipTop, clipRight, clipBottom;
    String count;
    void getClipBounds(Rect bounds) {bounds.top = 0; bounds.bottom = 5000;}
    void clipRect(int l, int t, int r, int b) {
      clipLeft=l; clipTop=t; clipRight=r; clipBottom=b;
    }
    void drawRoundRect(RectF bounds, int x, int y, Paint paint) {}
    void drawText(String text, float x, float y, Paint paint) {count=text;}
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
    void invalidate() {}
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
  static class View {
    final Parent parent = new Parent();
    Object getContext() {return this;}
    Parent getParent() {return parent;}
  }
  static class Parent {
    boolean disallowed;
    void requestDisallowInterceptTouchEvent(boolean value) {disallowed=value;}
  }
  static class MessageView extends View {}
  static class MotionEvent {
    static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3,
      ACTION_POINTER_DOWN=5;
    int action;
    float x, y, velocity;
    MotionEvent(int action, float x, float y, float velocity) {
      this.action=action; this.x=x; this.y=y; this.velocity=velocity;
    }
    static MotionEvent obtain(MotionEvent e) {return new MotionEvent(e.action,e.x,e.y,e.velocity);}
    int getActionMasked() {return action;}
    float getX() {return x;}
    float getY() {return y;}
    void setAction(int value) {action=value;}
    void recycle() {}
  }
  static class ViewConfiguration {
    static ViewConfiguration get(Object context) {return new ViewConfiguration();}
    int getScaledTouchSlop() {return 8;}
    int getScaledMinimumFlingVelocity() {return 50;}
    int getScaledMaximumFlingVelocity() {return 8000;}
  }
  static class VelocityTracker {
    float velocity;
    static VelocityTracker obtain() {return new VelocityTracker();}
    void addMovement(MotionEvent e) {velocity=e.velocity;}
    void computeCurrentVelocity(int units, int max) {}
    float getXVelocity() {return velocity;}
    void recycle() {}
  }
  static class AnimatorUtils {static Object DECELERATE_INTERPOLATOR = new Object();}
  static class FactorAnimator {
    interface Target {void onFactorChanged(int id, float factor, float fraction, FactorAnimator c);}
    final Target target;
    float factor, destination;
    boolean animating;
    FactorAnimator(int id, Target target, Object interpolator, long duration) {this.target=target;}
    void cancel() {animating=false;}
    void forceFactor(float value) {cancel(); factor=value; target.onFactorChanged(0,value,1,this);}
    void animateTo(float value) {destination=value; animating=factor!=value;}
    void advance(float fraction) {
      check(animating, "An animation must be scheduled");
      factor += (destination-factor)*fraction;
      target.onFactorChanged(0,factor,fraction,this);
      if (fraction==1) animating=false;
    }
  }
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
    int draws, drawX, drawY, lastAction=-1;
    void destroy() {}
    boolean onTouchEvent(View view, MotionEvent e) {lastAction=e.action; return true;}
    int getContentWidth() {return 320;}
    int getContentHeight() {return 200;}
    void buildContent(int width, int height) {}
    boolean needGif() {return false;}
    void draw(MessageView view, Canvas canvas, int x, int y,
              DoubleImageReceiver preview, Receiver target, float alpha) {draws++; drawX=x; drawY=y;}
  }
  static class Fallback {
    void performDestroy() {}
    void prepare(int width) {}
    int getHeight() {return 20;}
    void draw(Canvas canvas, int x, int right, int z, int y,
              Object unused, float alpha, ComplexReceiver receiver) {}
  }
  static class AudioResult {
    void detachFromView(View view) {}
    void performRichMessageDestroy() {}
    boolean onTouchEvent(View view, MotionEvent e) {return false;}
    void layout(int width, Object unused) {}
    int getHeight() {return 40;}
    void drawRichMessage(MessageView view, Canvas canvas, int x, int y, int width,
                         int height, ComplexReceiver receiver, long key, float alpha) {}
  }
  static class CollageContext {
    boolean onTouchEvent(View view, MotionEvent e, int x, int y) {return false;}
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
    public boolean onTouchEvent(View view, MotionEvent e, int x, int y) {return false;}
    public void detach(View view) {}
    public void performDestroy() {}
  }
  static class AnchorNode extends BlockNode {}
  class MediaNode extends BlockNode implements FactorAnimator.Target {
    final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
    ImageReceiver mapImage;
    AudioResult audioResult;
    Fallback fallback;
    CollageContext collageContext;
    String[] slideLabels;
    int columns, cellWidth, cellHeight, initialSlide;
    float touchStartX, touchStartY, slideOffset, touchStartSlideOffset;
    boolean sliding, slideshowTracking, mapPressed;
    FactorAnimator slideAnimator;
    VelocityTracker slideVelocity;
    View touchView;
    MediaWrapper pressedMedia;
    boolean openMap() {return false;}
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
  final Map<View, Boolean> attachedViews = new HashMap<>();
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
  static void near(float actual, float expected, String reason) {
    check(Math.abs(actual-expected)<.001f, reason + ": " + actual + " != " + expected);
  }
  static void touch(MediaNode node, View view, int action, float dx, float dy, float velocity) {
    node.onTouchEvent(view, new MotionEvent(action,170+dx,100+dy,velocity),10,20);
  }
  static void swipeTests() {
    for (boolean rtl : new boolean[] {false,true}) {
      RichMessageLayoutHarness layout = new RichMessageLayoutHarness();
      layout.message.isRtl=rtl;
      MediaNode media = layout.new MediaNode(true);
      MessageView view = new MessageView();
      Canvas canvas = new Canvas();
      ComplexReceiver receiver = new ComplexReceiver();
      media.measure(320);
      int sign = rtl ? 1 : -1;
      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,sign*100,0,0);
      near(media.slideOffset,100f/320,"Preview follows the finger");
      check(view.parent.disallowed && media.pressedMedia==null,
        "Horizontal drag claims the gesture and cancels the photo tap");
      media.draw(view,canvas,10,20,receiver,1);
      check(media.wrappers.get(0).drawX==10+sign*100 &&
        media.wrappers.get(1).drawX==10-sign*220,"Both photos move together in each direction");
      check(canvas.clipLeft==10 && canvas.clipRight==330 && canvas.clipTop==20 &&
        canvas.clipBottom==20+media.height,"Clip slides to the preview bounds");
      int before=media.wrappers.get(0).drawX;
      touch(media,view,MotionEvent.ACTION_UP,sign*100,0,0);
      check(media.spec.state.slideshowIndex==1 && media.slideAnimator.animating,
        "A long drag animates into the next photo");
      media.draw(view,canvas,10,20,receiver,1);
      check(media.wrappers.get(0).drawX==before,"Release must not jump");
      near(media.spec.state.slideshowIndex+media.slideOffset,100f/320,
        "Changing selection preserves the visual position");
      media.slideAnimator.advance(.5f);
      near(media.spec.state.slideshowIndex+media.slideOffset,(1+100f/320)/2,
        "Settling renders an intermediate position");
      media.slideAnimator.advance(1);
      check(media.slideOffset==0 && media.slideVelocity==null && !view.parent.disallowed,
        "Settling cleans up the gesture");
      media.draw(view,canvas,10,20,receiver,1);
      check(canvas.count.equals("2/2"),"The photo counter follows the visible slide");

      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,sign*200,0,0);
      near(media.slideOffset,0,"Last photo cannot move beyond the end");
      touch(media,view,MotionEvent.ACTION_UP,sign*200,0,0);
      check(media.spec.state.slideshowIndex==1,"Last photo remains selected");

      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,-sign*20,0,0);
      touch(media,view,MotionEvent.ACTION_UP,-sign*20,0,0);
      check(media.spec.state.slideshowIndex==1 && media.slideAnimator.animating,
        "A short slow swipe animates back");
      media.slideAnimator.advance(1);
      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,-sign*20,0,-sign*1000);
      touch(media,view,MotionEvent.ACTION_UP,-sign*20,0,-sign*1000);
      check(media.spec.state.slideshowIndex==0,"A quick flick changes photo");
      media.slideAnimator.advance(1);

      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,-sign*100,0,0);
      near(media.slideOffset,0,"First photo cannot move before the beginning");
      touch(media,view,MotionEvent.ACTION_CANCEL,-sign*100,0,0);
      for (int action : new int[] {MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN}) {
        touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
        touch(media,view,MotionEvent.ACTION_MOVE,sign*200,0,0);
        touch(media,view,action,sign*200,0,0);
        check(media.spec.state.slideshowIndex==0 && media.slideAnimator.animating,
          "Cancellation and multitouch return to the original photo");
        media.slideAnimator.advance(1);
      }
      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,sign*160,0,0);
      touch(media,view,MotionEvent.ACTION_UP,sign*160,0,0);
      media.slideAnimator.advance(.5f);
      float position=media.spec.state.slideshowIndex+media.slideOffset;
      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,-sign*16,0,0);
      near(media.spec.state.slideshowIndex+media.slideOffset,position-.05f,
        "A new drag continues from the current animation frame");
      touch(media,view,MotionEvent.ACTION_CANCEL,0,0,0);
      media.detach(view);
      check(!media.slideAnimator.animating && media.slideOffset==0 &&
        media.slideVelocity==null,"Detaching clears transient animation state");

      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_MOVE,0,50,0);
      check(!media.slideshowTracking && !media.sliding && !view.parent.disallowed &&
        media.slideVelocity==null,"Vertical scrolling yields to the parent");
      touch(media,view,MotionEvent.ACTION_UP,0,50,0);
      touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
      touch(media,view,MotionEvent.ACTION_UP,0,0,0);
      check(media.wrappers.get(1).lastAction==MotionEvent.ACTION_UP,
        "A tap still reaches the selected photo");
      media.performDestroy();
      check(!media.slideAnimator.animating,"Destroyed media stops animating");

      media = layout.new MediaNode(true);
      media.wrappers.add(new MediaWrapper());
      media.slideLabels = new String[] {"1/3","2/3","3/3"};
      media.measure(320);
      for (int target : new int[] {0,2}) {
        media.spec.state.slideshowIndex=1;
        int direction=target-1;
        touch(media,view,MotionEvent.ACTION_DOWN,0,0,0);
        touch(media,view,MotionEvent.ACTION_MOVE,sign*direction*100,0,0);
        media.draw(view,canvas,10,20,receiver,1);
        check(media.wrappers.get(1).drawX==10+sign*direction*100,
          "The middle photo moves in either direction");
        touch(media,view,MotionEvent.ACTION_UP,sign*direction*100,0,0);
        check(media.spec.state.slideshowIndex==target,"Select either neighbor from the middle");
        media.slideAnimator.advance(1);
      }
    }
  }
  public static void main(String[] args) {
    swipeTests();
    for (boolean slideshow : new boolean[] {false, true}) {
      for (int textHeight : new int[] {895, 900, 1200}) {
        RichMessageLayoutHarness layout = new RichMessageLayoutHarness();
        BlockNode text = new BlockNode();
        text.desiredHeight = textHeight;
        MediaNode media = layout.new MediaNode(slideshow);
        MediaNode later = layout.new MediaNode(slideshow);
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
      MediaNode media = layout.new MediaNode(slideshow);
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
