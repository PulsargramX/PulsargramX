"""Exercise rich button row sizing and gesture handling without an APK."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
DATA = "app/src/main/java/org/thunderdog/challegram/data/"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class RichMessageButtonsTest(unittest.TestCase):
  def test_row_layout_and_touch(self):
    source = read(DATA + "RichMessageLayout.java")
    row = source[source.index("  private final class ButtonRowNode"):]
    methods = "\n".join(java_method(row, signature) for signature in (
      "public int measure (", "private RichButton buttonAt (", "public boolean onTouchEvent ("))
    harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
public class RichButtonsHarness {
  @interface NonNull {}
  static class Screen {static int dp(float n) {return Math.round(n);}}
  static class TGMessage {
    static TGMessage getTextStyleProvider() {return new TGMessage();}
    float getTextSize() {return 16;}
  }
  static class Owner {void invalidate() {}}
  static class View {Object getContext() {return null;}}
  static class ViewConfiguration {
    static ViewConfiguration get(Object c) {return new ViewConfiguration();}
    int getScaledTouchSlop() {return 8;}
  }
  static class MotionEvent {
    static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
    int action; float x,y;
    MotionEvent(int a, float x, float y) {action=a;this.x=x;this.y=y;}
    int getActionMasked() {return action;} float getX() {return x;} float getY() {return y;}
  }
  static class Label {
    int width;
    Label(int w) {width=w;}
    void prepare(int n) {} int getWidth() {return width;}
  }
  static class Action {int clicks;void performClick(View v) {clicks++;}}
  static class RichButton {
    int left,width; boolean disabled,icon;
    Label label; Action action=new Action();
    RichButton(int w) {label=new Label(w);}
    boolean disabled() {return disabled;} boolean hasIcon() {return icon;}
  }
  static class Spec {TdApi.PageBlock block=new TdApi.PageBlockButtonRow(null,null);}
  static class Row {
    ArrayList<RichButton> buttons=new ArrayList<>();
    int width,height; RichButton pressed; float downX,downY;
    Spec spec=new Spec(); Owner owner=new Owner();
    METHODS
  }
  static void check(boolean b, String message) {if (!b) throw new AssertionError(message);}
  static void tap(Row row, int action, float x, float y) {
    row.onTouchEvent(new View(),new MotionEvent(action,x,y),10,20);
  }
  public static void main(String[] args) {
    Row row=new Row(); RichButton a=new RichButton(20),b=new RichButton(80);
    row.buttons.add(a);row.buttons.add(b);
    row.measure(300);
    check(a.width==146 && b.width==147 && b.left==153,"Fill row including rounding");
    b.label.width=180;row.measure(300);
    check(a.width==73 && b.width==220,"Preserve long labels when stretching");
    b.label.width=80;((TdApi.PageBlockButtonRow)row.spec.block).align=new TdApi.PageBlockHorizontalAlignmentCenter();
    row.measure(300);
    check(a.left==56 && b.left==123,"Center natural widths");
    ((TdApi.PageBlockButtonRow)row.spec.block).align=new TdApi.PageBlockHorizontalAlignmentRight();
    row.measure(300);check(b.left+b.width==300,"Right alignment");
    ((TdApi.PageBlockButtonRow)row.spec.block).align=null;
    row.measure(70);check(a.width+b.width+7==70,"Squeeze within viewport");
    row.measure(300);float x=10+a.left+a.width/2f;
    tap(row,0,x,30);tap(row,1,x,30);check(a.action.clicks==1,"Tap executes once");
    tap(row,0,x,30);tap(row,2,x+20,30);tap(row,1,x,30);
    check(a.action.clicks==1,"Scrolling cancels click");
    tap(row,0,x,30);tap(row,3,x,30);tap(row,1,x,30);
    check(a.action.clicks==1,"Cancel never clicks");
    a.disabled=true;tap(row,0,x,30);tap(row,1,x,30);
    check(a.action.clicks==1,"Disabled button never clicks");
    a.disabled=false;tap(row,0,x,30);tap(row,1,10+b.left+1,30);
    check(b.action.clicks==0 && a.action.clicks==1,"Release on another button never clicks");
    row.buttons.clear();check(row.measure(300)==0,"Empty rows have no height");
    for(int count=1;count<=8;count++) {
      row.buttons.clear();for(int i=0;i<count;i++)row.buttons.add(new RichButton(i*31));
      for(int width=1;width<400;width++) {
        row.measure(width);RichButton last=row.buttons.get(count-1);
        check(last.left+last.width==width,"Fill and squeeze stay inside row");
      }
    }
  }
}
'''.replace("METHODS", methods)
    with tempfile.TemporaryDirectory(prefix="rich-buttons-") as directory:
      work = Path(directory)
      path = work / "RichButtonsHarness.java"
      path.write_text(harness, encoding="utf-8")
      sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java", path]
      for name, code in {
        "Nullable": "public @interface Nullable {}",
        "IntDef": "public @interface IntDef {long[] value() default {};}",
      }.items():
        path = work / "androidx/annotation" / (name + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package androidx.annotation; " + code, encoding="utf-8")
        sources.append(path)
      compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory,
                                 *map(str, sources)], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
      result = subprocess.run(["java", "-cp", directory, "RichButtonsHarness"],
                              capture_output=True, text=True)
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
