#!/usr/bin/env python3
"""Keyboard restoration checks and isolated Java transition behavior tests."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def java_method(source: str, signature: str) -> str:
    start = source.index(signature)
    body_start = source.index("{", start)
    depth = 0
    for index in range(body_start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start : index + 1]
    raise AssertionError(f"Unterminated Java method: {signature}")


class MessageOptionsKeyboardSourceTest(unittest.TestCase):
    def test_software_keyboard_restoration_closes_emoji_panel_first(self) -> None:
        messages = read(
            "app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java"
        )
        restore = java_method(messages, "private void onHideMessageOptions ()")

        keyboard_branch = restore.index(
            "else if (needShowKeyboardAfterHideMessageOptions)"
        )
        cancel = restore.index(
            "emojiKeyboardFrameLayout.cancelKeyboardTransition();",
            keyboard_branch,
        )
        close = restore.index("closeEmojiKeyboard(true);", keyboard_branch)
        show = restore.index("emojiKeyboardFrameLayout.showKeyboard(inputView);", keyboard_branch)

        self.assertLess(cancel, close)
        self.assertLess(close, show)

    def test_cancelled_transition_cannot_keep_blocking_pre_draw(self) -> None:
        frame = read(
            "app/src/main/java/org/thunderdog/challegram/widget/KeyboardFrameLayout.java"
        )
        cancel = java_method(frame, "public void cancelKeyboardTransition ()")

        self.assertIn("keyboardState = STATE_NONE;", cancel)
        self.assertIn("keyboardTransitionDeadline = 0;", cancel)
        self.assertIn("requestLayout();", cancel)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class KeyboardTransitionBehaviorTest(unittest.TestCase):
    def test_transition_completion_and_timeout(self) -> None:
        source = read(
            "app/src/main/java/org/thunderdog/challegram/widget/KeyboardFrameLayout.java"
        )
        # Run the production transition methods with a controllable clock and inert views.
        # This exercises state behavior; Android layout/IME integration still needs a device.
        start = source.index("  private static final int STATE_NONE")
        end = source.index("  private static final int ANIMATOR_ADDITIONAL_HEIGHT", start)
        transitions = source[start:end]
        visibility = java_method(source, "public void setVisible (boolean visible)")
        harness = r"""
interface PreDraw { boolean onPreDraw(); }
public class KeyboardTransitionTest implements PreDraw {
  static class SystemClock {
    static long now;
    static long uptimeMillis() { return now; }
  }
  static class Keyboard {
    static void show(android.widget.EditText input) {}
    static void hide(android.widget.EditText input) {}
  }
  static class UI {
    static final UI context = new UI();
    static UI getContext(Object context) { return UI.context; }
    UI getRootView() { return this; }
    void skipNextImeAnimation(long timeoutMs) {
      check(timeoutMs == KEYBOARD_TRANSITION_TIMEOUT_MS, "Use the transition deadline");
    }
  }
  static class Content {
    boolean visible;
    void setKeyboardVisible(boolean visible) { this.visible = visible; }
    void requestLayout() {}
  }
  static class BitwiseUtils {
    static boolean hasFlag(int flags, int flag) { return (flags & flag) != 0; }
    static int setFlag(int flags, int flag, boolean value) {
      return value ? flags | flag : flags & ~flag;
    }
  }
  static final int FLAG_VISIBLE = 1;
  int flags;
  final Content contentView = new Content();
  Object getContext() { return null; }
  void requestLayout() {}
  void setVisibleImpl(boolean visible) {}
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  void begin(boolean showing) {
    if (showing) showKeyboard(null); else hideKeyboard(null);
  }
  public static void main(String[] args) {
    for (boolean showing : new boolean[] {true, false}) {
      KeyboardTransitionTest view = new KeyboardTransitionTest();
      SystemClock.now = 1000;
      view.begin(showing);
      check(!view.onPreDraw(), "Wait for the requested state");
      view.onKeyboardStateChanged(!showing);
      check(!view.onPreDraw(), "Opposite callback must not complete the wait");
      view.onKeyboardStateChanged(showing);
      check(view.onPreDraw(), "Matching callback must release drawing immediately");
      check(view.contentView.visible == showing, "Update content keyboard visibility");
      for (int i = 0; i < 100; i++) {
        view.onKeyboardStateChanged(showing);
        check(view.onPreDraw(), "Repeated callbacks must not restart the wait");
      }

      view.begin(showing);
      for (int i = 0; i < 120; i++) {
        check(!view.onPreDraw(), "Frame count must not determine the timeout");
      }
      SystemClock.now += KEYBOARD_TRANSITION_TIMEOUT_MS - 1;
      view.onKeyboardStateChanged(!showing);
      check(!view.onPreDraw(), "Wait until the deadline");
      SystemClock.now++;
      check(view.onPreDraw(), "Missing callback must release drawing at the deadline");
      view.onKeyboardStateChanged(showing);
      check(view.onPreDraw(), "Late callback must not restart an expired wait");

      view.begin(showing);
      SystemClock.now += KEYBOARD_TRANSITION_TIMEOUT_MS - 1;
      view.begin(!showing);
      SystemClock.now++;
      check(!view.onPreDraw(), "Reversed request must have its own deadline");
      view.onKeyboardStateChanged(showing);
      check(!view.onPreDraw(), "Old callback must not complete the reversed request");
      view.onKeyboardStateChanged(!showing);
      check(view.onPreDraw(), "Reversed request must complete on its matching callback");

      view.begin(showing);
      view.cancelKeyboardTransition();
      check(view.onPreDraw(), "Explicit cancellation must release drawing");
      view.onKeyboardStateChanged(showing);
      check(view.onPreDraw(), "Callback after cancellation must not restart the wait");
    }
    KeyboardTransitionTest view = new KeyboardTransitionTest();
    view.setVisible(true);
    view.hideKeyboard(null);
    view.setVisible(false);
    check(view.onPreDraw(), "Closing the panel must cancel its previous wait");
    view.showKeyboard(null);
    check(!view.onPreDraw(), "Hidden panel can still await keyboard restoration");
    view.onKeyboardStateChanged(true);
    check(view.onPreDraw(), "Restoration must complete while the panel is hidden");
  }
""" + transitions + visibility + "\n}\n"
        with tempfile.TemporaryDirectory(prefix="keyboard-transition-test-") as directory:
            work = Path(directory)
            edit_text = work / "android/widget/EditText.java"
            edit_text.parent.mkdir(parents=True)
            edit_text.write_text("package android.widget; public class EditText {}\n")
            java_file = work / "KeyboardTransitionTest.java"
            java_file.write_text(harness)
            subprocess.run(
                ["javac", "--release", "17", "-d", directory, str(edit_text), str(java_file)],
                check=True, capture_output=True, text=True,
            )
            result = subprocess.run(
                ["java", "-cp", directory, "KeyboardTransitionTest"],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
