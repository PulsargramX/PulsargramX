"""Exercise album holds and subsequent item/text selection without an APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class MessageAlbumSelectionTest(unittest.TestCase):
    def test_album_selection_and_long_press_precedence(self):
        source = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessageView.java"
        )
        methods = "\n".join(java_method(source, signature) for signature in (
            "private static void selectMessage (",
            "private boolean performLongPress ()",
            "private void onLongPress ()",
        ))
        harness = r'''
import java.util.*;
public class MessageAlbumSelectionHarness {
  static class ViewController<T> {
    static ViewController<?> findRoot(MessageAlbumSelectionHarness view) { return view.root; }
  }
  static class MessagesController extends ViewController<Object> {
    boolean selectMode;
    int individualCalls, albumCalls;
    final Set<Long> selected = new HashSet<>();
    boolean inSelectMode() { return selectMode; }
    void selectMessage(long id, TGMessage message, float x, float y) {
      individualCalls++;
      if (!selected.add(id)) selected.remove(id);
      selectMode = !selected.isEmpty();
    }
    void selectAllMessages(TGMessage message, float x, float y) {
      albumCalls++;
      if (selected.containsAll(message.ids)) selected.removeAll(message.ids);
      else selected.addAll(message.ids);
      selectMode = !selected.isEmpty();
    }
  }
  static class TGMessage {
    final List<Long> ids = new ArrayList<>();
    boolean notSent, selectable = true, consumed;
    float textTranslation;
    TGMessage(int count) { for (long id = 1; id <= count; id++) ids.add(id); }
    long findMessageIdUnder(float x, float y) {
      // Media cells and the caption identify a constituent message; row space does not.
      if (x < 0) return 0;
      return y > 0 ? ids.get(0) : ids.get((int) x);
    }
    int getMessageCount() { return ids.size(); }
    boolean isNotSent() { return notSent; }
    boolean canBeSelected() { return selectable; }
    float getMessageTextSelectionTranslationX() { return textTranslation; }
    boolean performLongPress(Object view, float x, float y) { return consumed; }
  }
  static class TGMessageBotInfo extends TGMessage { TGMessageBotInfo() { super(1); } }
  static class TGMessageRich extends TGMessage {
    boolean cancelled;
    TGMessageRich() { super(1); }
    void cancelTouch(Object view) { cancelled = true; }
  }
  static class MessageTextSelectionManager {
    boolean start, reject;
    float receivedX;
    boolean tryStartSelection(Object view, TGMessage message, float x, float y) {
      receivedX = x;
      return start;
    }
    boolean consumeRejectedLongPress() { return reject; }
  }
  static final int FLAG_CAUGHT_CLICK = 1, FLAG_CAUGHT_MESSAGE_TOUCH = 2,
    FLAG_TEXT_SELECTION_DRAG = 4;
  TGMessage msg;
  ViewController<?> root;
  MessageTextSelectionManager textSelection;
  float touchX, touchY, touchMessageTextTranslationX;
  int flags;
  boolean longPressed, haptic;
  MessageAlbumSelectionHarness(TGMessage message, MessagesController controller) {
    msg = message;
    root = controller;
  }
  MessageTextSelectionManager getMessageTextSelectionManager() { return textSelection; }
  void setLongPressed(boolean value) { setLongPressed(value, true); }
  void setLongPressed(boolean value, boolean allowHaptic) {
    longPressed = value;
    haptic = allowHaptic;
  }
  METHODS
  static void check(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }
  static MessageAlbumSelectionHarness hold(TGMessage album, MessagesController controller,
                                          float x, float y, int touchFlag) {
    MessageAlbumSelectionHarness view = new MessageAlbumSelectionHarness(album, controller);
    view.touchX = x;
    view.touchY = y;
    view.flags = touchFlag;
    view.onLongPress();
    return view;
  }
  public static void main(String[] args) {
    // Holding any tile, its caption or the row margin must enter selection with
    // the same complete album, for either generic or media-handled touch events.
    for (int count : new int[] {2, 8, 10}) {
      for (int touchFlag : new int[] {FLAG_CAUGHT_CLICK, FLAG_CAUGHT_MESSAGE_TOUCH}) {
        for (float[] point : new float[][] {
            {0, 0}, {count / 2, 0}, {count - 1, 0}, {0, 1}, {-1, 0}}) {
          TGMessage album = new TGMessage(count);
          MessagesController controller = new MessagesController();
          MessageAlbumSelectionHarness view = hold(album, controller, point[0], point[1], touchFlag);
          check(controller.selected.equals(new HashSet<>(album.ids)),
            "Initial album hold must select all " + count + " items at " + Arrays.toString(point));
          check(controller.albumCalls == 1 && controller.individualCalls == 0,
            "Enter selection through the album selection operation");
          check(view.longPressed && view.haptic && view.flags == 0,
            "Consume the hold and keep ordinary selection haptics");
        }
      }
    }

    TGMessage album = new TGMessage(8);
    MessagesController controller = new MessagesController();
    hold(album, controller, 0, 0, FLAG_CAUGHT_MESSAGE_TOUCH);
    selectMessage(controller, album, 3, 0);
    check(controller.selected.size() == 7 && !controller.selected.contains(4L),
      "Selection-mode taps must still toggle only the touched album item");
    hold(album, controller, 6, 0, FLAG_CAUGHT_CLICK);
    check(controller.selected.size() == 6 && !controller.selected.contains(7L),
      "Selection-mode holds must still toggle only the touched album item");
    selectMessage(controller, album, 3, 0);
    check(controller.selected.size() == 7 && controller.selected.contains(4L),
      "Individual items can be reselected");
    hold(album, controller, -1, 0, FLAG_CAUGHT_CLICK);
    check(controller.selected.size() == 8, "Row-space holds complete a partial album selection");
    selectMessage(controller, album, -1, 0);
    check(controller.selected.isEmpty() && !controller.selectMode,
      "Row-space taps still clear a complete album selection");

    controller = new MessagesController();
    hold(new TGMessage(1), controller, 0, 0, FLAG_CAUGHT_MESSAGE_TOUCH);
    check(controller.selected.equals(Set.of(1L)) && controller.individualCalls == 1 &&
          controller.albumCalls == 0,
      "Keep single-message selection and its text-selection hint path");

    // Native long-press actions and caption text selection retain precedence.
    controller = new MessagesController();
    album = new TGMessage(8);
    album.consumed = true;
    MessageAlbumSelectionHarness view = hold(album, controller, 0, 0, FLAG_CAUGHT_MESSAGE_TOUCH);
    check(controller.selected.isEmpty() && view.longPressed,
      "A handled media/link action must not also select the album");
    album.consumed = false;
    hold(album, controller, 0, 0, FLAG_CAUGHT_MESSAGE_TOUCH);
    view = new MessageAlbumSelectionHarness(album, controller);
    view.textSelection = new MessageTextSelectionManager();
    view.textSelection.start = true;
    view.touchX = 10;
    view.touchY = 1;
    view.touchMessageTextTranslationX = 5;
    album.textTranslation = 20;
    view.flags = FLAG_CAUGHT_CLICK;
    view.onLongPress();
    check(controller.selected.size() == 8 && view.flags == FLAG_TEXT_SELECTION_DRAG &&
          view.longPressed && !view.haptic,
      "Selected caption text starts text selection without toggling a constituent message");
    check(view.textSelection.receivedX == 25,
      "Caption text selection keeps its translation adjustment");
    view.textSelection.start = false;
    view.textSelection.reject = true;
    view.flags = FLAG_CAUGHT_CLICK;
    view.onLongPress();
    check(controller.selected.size() == 8 && view.flags == 0 && !view.haptic,
      "Rejected spoiler/text holds stay consumed without toggling selection");

    for (TGMessage blocked : new TGMessage[] {
        new TGMessageBotInfo(), new TGMessage(8)}) {
      blocked.notSent = !(blocked instanceof TGMessageBotInfo);
      controller = new MessagesController();
      view = hold(blocked, controller, 0, 0, FLAG_CAUGHT_CLICK);
      check(controller.selected.isEmpty() && !view.longPressed, "Do not select unsent/bot-info rows");
    }
    album.selectable = false;
    controller = new MessagesController();
    view = hold(album, controller, 0, 0, FLAG_CAUGHT_CLICK);
    check(controller.selected.isEmpty() && !view.longPressed, "Keep nonselectable message restrictions");
    view.msg = new TGMessage(8);
    view.root = new ViewController<>();
    check(!view.performLongPress(), "Selection needs a messages controller");
    TGMessageRich rich = new TGMessageRich();
    hold(rich, new MessagesController(), 0, 0, FLAG_CAUGHT_CLICK);
    check(rich.cancelled, "Rich-message touch handling is cancelled before selection");
  }
}
'''
        with tempfile.TemporaryDirectory(prefix="message-album-selection-") as directory:
            java = Path(directory) / "MessageAlbumSelectionHarness.java"
            java.write_text(harness.replace("METHODS", methods), encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "--release", "17", "-d", directory, str(java)],
                capture_output=True, text=True,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "MessageAlbumSelectionHarness"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
