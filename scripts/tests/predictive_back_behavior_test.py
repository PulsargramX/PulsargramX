#!/usr/bin/env python3
"""Exercise predictive back geometry, chrome and cleanup without an Android APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class PredictiveBackBehaviorTest(unittest.TestCase):
    def test_status_strip_follows_surfaces_and_restores(self) -> None:
        root = read("app/src/main/java/org/thunderdog/challegram/navigation/RootLayout.java")
        parent = read("app/src/main/java/org/thunderdog/challegram/widget/BaseRootLayout.java")
        root_methods = "\n".join(java_method(root, signature) for signature in (
            "boolean prepareBackHeader (", "private void clearBackHeader ()",
            "void setBackPreview (", "void clearBackPreview ()", "protected void dispatchDraw (",
            "protected boolean drawChild (", "private void drawBackStatusBar (",
            "private int saveBackSurface (", "private void drawBackContent (",
        ))
        parent_methods = "\n".join(java_method(parent, signature) for signature in (
            "public void setBackStatusBar (", "public void drawBackStatusBar (",
            "protected boolean drawChild (",
        ))
        harness = r"""
import java.util.*;
public class StatusStripTest {
  @interface Nullable {}
  static class RectF {
    float left, top, right, bottom;
    RectF() {}
    RectF(float l, float t, float r, float b) { set(l,t,r,b); }
    void set(float l, float t, float r, float b) { left=l; top=t; right=r; bottom=b; }
    void set(RectF r) { set(r.left,r.top,r.right,r.bottom); }
    void setEmpty() { set(0,0,0,0); }
    boolean isEmpty() { return right<=left || bottom<=top; }
    float width() { return right-left; }
    float height() { return bottom-top; }
  }
  static class Path {
    enum Direction { CW }
    RectF bounds = new RectF();
    void rewind() {}
    void addRoundRect(RectF rect, float[] radii, Direction direction) { bounds.set(rect); }
  }
  static class Paint { int color; void setColor(int value) { color=value; } }
  static class Color {
    static int argb(int a, int r, int g, int b) { return a<<24 | r<<16 | g<<8 | b; }
  }
  static class Theme {
    static boolean dark;
    static boolean isDark() { return dark; }
    static int backgroundColor() { return -1; }
  }
  static class State {
    float x, y, sx=1, sy=1, alpha=1;
    RectF clip;
    State copy() {
      State s = new State();
      s.x=x; s.y=y; s.sx=sx; s.sy=sy; s.alpha=alpha; s.clip=clip;
      return s;
    }
  }
  record Draw(String name, RectF bounds, RectF clip, float alpha) {}
  static class Canvas {
    static final int ALL_SAVE_FLAG=31;
    State state = new State();
    List<State> stack = new ArrayList<>();
    List<Draw> draws = new ArrayList<>();
    int save() { stack.add(state.copy()); return stack.size(); }
    int saveLayerAlpha(float l, float t, float r, float b, int alpha, int flags) {
      int count=save(); state.alpha*=alpha/255f; return count;
    }
    void restoreToCount(int count) {
      state=stack.get(count-1);
      stack.subList(count-1,stack.size()).clear();
    }
    void translate(float x, float y) { state.x+=x*state.sx; state.y+=y*state.sy; }
    void scale(float x, float y) { state.sx*=x; state.sy*=y; }
    void concat(Object matrix) {}
    void clipPath(Path path) { state.clip=new RectF(); state.clip.set(path.bounds); }
    void drawColor(int color) {}
    void drawRect(float l, float t, float r, float b, Paint paint) {
      if ((paint.color & 0xffffff)==0) {
        draws.add(new Draw("dim",new RectF(l,t,r,b),state.clip,(paint.color>>>24)/255f));
      }
    }
    void strip(String name, float width, float height) {
      draws.add(new Draw(name,new RectF(state.x,state.y,
        state.x+width*state.sx,state.y+height*state.sy),state.clip,state.alpha));
    }
  }
  static class View {
    static final int VISIBLE=0, GONE=8;
    int width=1000, height=60, visibility=VISIBLE, left, top, windowX, windowY;
    float alpha=1;
    boolean failCapture;
    View parent;
    int getWidth() { return width; }
    int getHeight() { return height; }
    int getLeft() { return left; }
    int getTop() { return top; }
    int getScrollX() { return 0; }
    int getScrollY() { return 0; }
    int getVisibility() { return visibility; }
    float getAlpha() { return alpha; }
    Object getAnimation() { return null; }
    Object getMatrix() { return null; }
    View getParent() { return parent; }
    void getLocationInWindow(int[] location) { location[0]=windowX; location[1]=windowY; }
    void draw(Canvas canvas) {}
  }
  static class Frame extends View {
    List<View> children = new ArrayList<>();
    int invalidations;
    void invalidate() { invalidations++; }
    void add(View view) { children.add(view); view.parent=this; }
    long getDrawingTime() { return 123; }
    protected boolean drawChild(Canvas canvas, View child, long time) {
      int save=canvas.save();
      canvas.translate(child.left,child.top); canvas.state.alpha*=child.alpha;
      child.draw(canvas); canvas.restoreToCount(save); return true;
    }
    protected void dispatchDraw(Canvas canvas) {
      for (View child : children) if (child.visibility==VISIBLE) drawChild(canvas,child,123);
    }
    public void draw(Canvas canvas) { dispatchDraw(canvas); }
  }
  static class NetworkStatusBarView extends View {
    String text="connected";
    public void draw(Canvas canvas) { canvas.strip("live:"+text,width,height); }
  }
  static class HeaderView extends View {}
  static class FloatingButton extends View {}
  static class NavigationLayout extends View {
    void drawBackContent(Canvas c, View preview, boolean foreground, long time) {}
  }
  static class BackSnapshot {
    final View view;
    final String text;
    BackSnapshot(View view) {
      if (view.failCapture) throw new OutOfMemoryError("Simulated capture failure");
      this.view=view;
      text=view instanceof NetworkStatusBarView ? ((NetworkStatusBarView)view).text : null;
    }
    void draw(Canvas canvas) {
      if (text!=null) canvas.strip("snapshot:"+text,view.width,view.height);
    }
  }
  static class Activity {
    NetworkStatusBarView bar;
    NetworkStatusBarView getNetworkStatusBar() { return bar; }
  }
  static class UI { static Activity getContext(Activity context) { return context; } }
  static class Log { static void w(String message, Throwable error) {} }
  static class BaseRootLayout extends Frame {
    View backStatusBar;
    __PARENT_METHODS__
  }
  static class RootLayout extends Frame {
    View backPreview;
    NavigationLayout backPreviewParent;
    RectF backClosingRect=new RectF(), backEnteringRect=new RectF();
    float[] backCornerRadii=new float[8], backScaledCornerRadii=new float[8];
    float backForegroundAlpha=1, backDimFactor;
    int backHeaderColor;
    HeaderView backHeader;
    FloatingButton backFloatingButton;
    BackSnapshot backHeaderSnapshot, backFloatingButtonSnapshot, backStatusBarSnapshot;
    BaseRootLayout backStatusBarParent;
    int backStatusBarOffsetX, backStatusBarOffsetY;
    Path backClip=new Path();
    Paint backPaint=new Paint();
    Activity activity=new Activity();
    RootLayout() { height=2000; }
    Activity getContext() { return activity; }
    void prepareBackCorners() {}
    __ROOT_METHODS__
  }
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  static void close(float actual, float expected) {
    check(Math.abs(actual-expected)<.01f,actual+" != "+expected);
  }
  public static void main(String[] args) {
    BaseRootLayout parent=new BaseRootLayout();
    RootLayout root=new RootLayout();
    NetworkStatusBarView bar=new NetworkStatusBarView();
    root.activity.bar=bar;
    parent.add(root); parent.add(bar);
    HeaderView header=new HeaderView();
    FloatingButton floating=new FloatingButton(); floating.visibility=View.GONE;
    root.add(header); root.add(floating);
    NavigationLayout content=new NavigationLayout(); root.add(content);
    View preview=new View(); preview.parent=content;
    Canvas normal=new Canvas(); parent.draw(normal);
    check(normal.draws.size()==1,"One status strip before gesture");
    close(normal.draws.get(0).bounds().top,0);

    check(root.prepareBackHeader(header,floating),"Capture chrome");
    check(parent.invalidations==1,"Invalidate standalone status drawing when borrowing it");
    check(bar.parent==parent && bar.alpha==1 && bar.visibility==View.VISIBLE,
      "Preserve ownership, visibility and alpha");
    RectF closing=new RectF(50,100,900,1800), entering=new RectF(-150,100,700,1800);
    root.setBackPreview(preview,closing,entering,0x112233,1,1);
    bar.text="connecting";
    Canvas gesture=new Canvas(); parent.draw(gesture);
    check(gesture.draws.size()==3,"Two moving status strips and one destination dim");
    Draw destination=gesture.draws.get(0), dim=gesture.draws.get(1), outgoing=gesture.draws.get(2);
    check(destination.name().equals("live:connecting"),"Destination stays live");
    check(outgoing.name().equals("snapshot:connected"),"Preserve outgoing chrome");
    close(destination.bounds().top,100); close(outgoing.bounds().top,100);
    close(outgoing.bounds().left,50); close(outgoing.bounds().right,900);
    close(outgoing.bounds().bottom,151);
    check(destination.clip().left==entering.left && outgoing.clip().left==closing.left,
      "Use each surface's rounded clip");
    close(dim.alpha(),.2f); close(outgoing.alpha(),1);

    Theme.dark=true;
    Canvas dark=new Canvas(); parent.draw(dark);
    close(dark.draws.get(1).alpha(),.8f);
    close(dark.draws.get(2).bounds().top,100);
    Theme.dark=false;
    root.setBackPreview(preview,closing,entering,0x112233,.4f,.5f);
    Canvas fading=new Canvas(); parent.draw(fading);
    close(fading.draws.get(2).alpha(),.4f); close(fading.draws.get(1).alpha(),26/255f);
    root.setBackPreview(preview,closing,entering,0x112233,0,0);
    Canvas committed=new Canvas(); parent.draw(committed);
    check(committed.draws.size()==2,"Status strip survives on destination after foreground fades");
    close(committed.draws.get(0).bounds().top,100);
    root.clearBackPreview();
    root.clearBackPreview();
    Canvas restored=new Canvas(); parent.draw(restored);
    check(restored.draws.size()==1 && restored.draws.get(0).name().equals("live:connecting"),
      "Cleanup restores live strip exactly once");
    close(restored.draws.get(0).bounds().top,0);
    check(parent.backStatusBar==null && root.backStatusBarSnapshot==null,
      "Release rendering ownership and captured pixels");
    check(parent.invalidations==2,"Invalidate restoration once, even with repeated cleanup");

    root.windowX=20; root.windowY=40;
    check(root.prepareBackHeader(header,floating),"Recapture at offset origin");
    root.setBackPreview(preview,closing,entering,0x112233,1,1);
    Canvas offset=new Canvas(); parent.draw(offset);
    close(offset.draws.get(0).bounds().top,100-40*.85f);
    close(offset.draws.get(2).bounds().left,50-20*.85f);
    bar.failCapture=true;
    check(!root.prepareBackHeader(header,floating),"Capture failure falls back safely");
    check(parent.backStatusBar==null && root.backStatusBarSnapshot==null,
      "Failed or interrupted preparation restores standalone drawing");
    root.clearBackPreview();
    root.activity.bar=null;
    check(root.prepareBackHeader(header,floating),"Missing status strip remains supported");
    root.clearBackPreview();
  }
}
"""
        harness = (harness.replace("__ROOT_METHODS__", root_methods)
                   .replace("__PARENT_METHODS__", parent_methods))
        with tempfile.TemporaryDirectory(prefix="predictive-back-status-test-") as directory:
            java = Path(directory) / "StatusStripTest.java"
            java.write_text(harness, encoding="utf-8")
            result = subprocess.run(
                ["javac", "--release", "17", "-d", directory, str(java)],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "StatusStripTest"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

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
