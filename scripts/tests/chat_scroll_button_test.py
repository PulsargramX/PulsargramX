"""Exercise chat navigation button visibility without an Android build."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ChatScrollButtonTest(unittest.TestCase):
  def test_reply_returns_and_history_bottom(self):
    source = read(
      "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesManager.java"
    )
    methods = "\n".join(java_method(source, signature) for signature in (
      "private boolean isLastMessageSponsored ()",
      "private void checkScrollButton (",
      "public void checkScrollToBottomButton ()",
      "public boolean hasReturnMessage ()",
      "public boolean isAtVeryBottom ()",
      "public void onCanLoadMoreBottomChanged ()",
    ))
    harness = r'''
import java.util.*;

public class ChatScrollButtonTest {
  final Controller controller = new Controller();
  final Adapter adapter = new Adapter();
  final LayoutManager manager = new LayoutManager();
  final Loader loader = new Loader();
  Object highlightMessageId = new Object();
  long[] returnToMessageIds;
  boolean lastScrollToBottomVisible, returnAbove;
  int getActiveMessageCount() {return adapter.getMessageCount();}
  int getRecyclerHeight() {return manager.viewport.getMeasuredHeight();}
  Controller controller() {return controller;}
  boolean isReturnAbove() {return hasReturnMessage() && returnAbove;}
  METHODS

  static class Controller {
    boolean visible, reverse;
    boolean inWallpaperPreviewMode() {return false;}
    void setScrollToBottomVisible(boolean visible, boolean reverse) {
      this.visible = visible;
      this.reverse = reverse;
    }
  }
  static class Loader {
    boolean more;
    boolean canLoadBottom() {return more;}
  }
  static class TGMessage {
    final boolean sponsored, hidden;
    TGMessage(boolean sponsored, boolean hidden) {
      this.sponsored = sponsored; this.hidden = hidden;
    }
    boolean isSponsoredMessage() {return sponsored;}
    boolean isHiddenByMessagesFilter() {return hidden;}
    int getHeight() {return hidden ? 0 : 100;}
  }
  static class Adapter {
    final List<TGMessage> messages = new ArrayList<>();
    TGMessage getBottomMessage() {return getMessage(0);}
    TGMessage getMessage(int index) {
      return index >= 0 && index < messages.size() ? messages.get(index) : null;
    }
    int getMessageCount() {return messages.size();}
  }
  static class View {
    int bottom, height = 600, paddingBottom;
    View parent;
    int getBottom() {return bottom;}
    int getMeasuredHeight() {return height;}
    int getPaddingBottom() {return paddingBottom;}
    Object getParent() {return parent;}
  }
  static class LayoutManager {
    final View viewport = new View();
    final Map<Integer, View> views = new HashMap<>();
    View findViewByPosition(int position) {return views.get(position);}
    void show(int position, int bottom) {
      View view = new View(); view.bottom = bottom; view.parent = viewport;
      views.put(position, view);
    }
  }
  ChatScrollButtonTest() {
    for (int i = 0; i < 10; i++) adapter.messages.add(new TGMessage(false, false));
    manager.show(0, 600);
  }
  void expect(boolean visible, boolean reverse, String reason) {
    check(controller.visible == visible && controller.reverse == reverse, reason);
  }
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    ChatScrollButtonTest chat = new ChatScrollButtonTest();
    chat.checkScrollButton(0, 5);
    chat.expect(false, false, "Hide at the newest message");
    chat.returnToMessageIds = new long[] {90};
    chat.checkScrollButton(0, 5);
    chat.expect(false, false, "A saved reply return must not show a downward button at bottom");
    check(chat.hasReturnMessage(), "Visibility checks must preserve a pending reply jump");

    // Reply navigation can finish without another scroll callback.
    chat.manager.views.clear();
    chat.manager.show(3, 650);
    chat.checkScrollButton(3, 8);
    chat.expect(true, false, "Keep the button while viewing older replies");
    chat.manager.views.clear();
    chat.manager.show(0, 600);
    chat.checkScrollToBottomButton();
    chat.expect(false, false, "Ignore cached visibility after jumping back to bottom");

    chat.returnAbove = true;
    chat.checkScrollToBottomButton();
    chat.expect(true, true, "Keep an upward return to an older message at bottom");
    chat.returnAbove = false;
    chat.loader.more = true;
    chat.checkScrollToBottomButton();
    chat.expect(true, false, "The bottom of a loaded slice can still have newer messages");
    chat.loader.more = false;
    chat.onCanLoadMoreBottomChanged();
    chat.expect(false, false, "Hide when pagination confirms the real bottom");

    chat.lastScrollToBottomVisible = false;
    chat.manager.show(0, 601);
    chat.checkScrollToBottomButton();
    chat.expect(true, false, "Keep reply returns while the newest message is clipped");
    chat.manager.viewport.paddingBottom = 20;
    chat.manager.show(0, 580);
    chat.checkScrollToBottomButton();
    chat.expect(false, false, "Respect the bottom viewport padding");

    // A sponsored row may be outside the viewport below the newest chat message.
    chat.adapter.messages.add(0, new TGMessage(true, false));
    chat.manager.views.clear();
    chat.manager.show(1, 580);
    chat.checkScrollButton(1, 6);
    chat.expect(false, false, "A sponsored row must not keep a reply return visible");
    chat.manager.show(1, 480);
    chat.checkScrollButton(1, 6);
    chat.expect(false, false, "Hide when the newest message is above a visible sponsored row");
    chat.adapter.messages.add(1, new TGMessage(false, true));
    chat.manager.views.clear();
    chat.manager.show(2, 580);
    chat.checkScrollButton(2, 7);
    chat.expect(false, false, "Hidden rows below the newest visible message cannot keep the button");

    chat.returnToMessageIds = null;
    chat.manager.views.clear();
    chat.manager.show(5, 650);
    chat.checkScrollButton(5, 9);
    chat.expect(true, false, "Preserve ordinary scrolling to newer messages");
    chat.adapter.messages.clear();
    chat.loader.more = true;
    chat.returnToMessageIds = new long[] {90};
    chat.checkScrollToBottomButton();
    chat.expect(false, false, "An empty history has no navigation button");
  }
}
'''.replace("METHODS", methods)
    with tempfile.TemporaryDirectory(prefix="chat-scroll-button-") as directory:
      work = Path(directory)
      test = work / "ChatScrollButtonTest.java"
      test.write_text(harness, encoding="utf-8")
      subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work), str(test)], check=True)
      subprocess.run(["java", "-cp", str(work), "ChatScrollButtonTest"], check=True)


if __name__ == "__main__":
  unittest.main()
