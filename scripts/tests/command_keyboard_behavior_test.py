#!/usr/bin/env python3
"""Exercise composer callbacks and the bot keyboard's window draw gate together."""

import shutil
import unittest

from ime_animation_behavior_test import run_java
from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class CommandKeyboardBehaviorTest(unittest.TestCase):
    def test_keyboard_callbacks_release_window_drawing(self) -> None:
        commands = read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/CommandKeyboardLayout.java"
        )
        start = commands.index("  // Keyboard utils")
        transitions = commands[start:commands.rfind("}")].replace("EditText", "Input")
        messages = read("app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java")
        changed = java_method(messages, "public boolean onKeyboardStateChanged (")
        # Event-log positioning is unrelated to the normal composer's keyboard transitions.
        changed = "public boolean onKeyboardStateChanged (boolean visible) {\n" + changed[
            changed.index("    if (isFocused() && visible && !getKeyboardState())"):]
        closing = "\n".join(java_method(messages, signature) for signature in (
            "private void closeCommandsKeyboard (boolean byUserEvent)",
            "private void closeCommandsKeyboardImpl (",
            "private void setCommandsShown (",
            "private void forceCloseCommandsKeyboard (",
        ))
        harness = r"""
public class ImeAnimationTest {
  interface PreDraw { boolean onPreDraw(); }
  static class Input {}
  static class SystemClock {
    static long now = 1000;
    static long uptimeMillis() { return now; }
  }
  static class Keyboard {
    static int showRequests;
    static void show(Input input) { showRequests++; }
    static void hide(Input input) {}
  }
  static class UI {
    static final UI context = new UI();
    int skipRequests;
    static UI getContext(Object context) { return UI.context; }
    UI getRootView() { return this; }
    void skipNextImeAnimation(long timeout) { skipRequests++; }
  }
  static class View {
    static final int GONE = 8;
    final Observer observer = new Observer();
    void setVisibility(int visibility) {}
    protected void onAttachedToWindow() {}
    protected void onDetachedFromWindow() {}
    Observer getViewTreeObserver() { return observer; }
  }
  static class Observer {
    final java.util.Set<PreDraw> listeners = new java.util.HashSet<>();
    void addOnPreDrawListener(PreDraw listener) { listeners.add(listener); }
    void removeOnPreDrawListener(PreDraw listener) { listeners.remove(listener); }
  }
  static class Panel { void onKeyboardStateChanged(boolean visible) {} }
  static class R {
    static class drawable {
      static final int deproko_baseline_bots_command_26 = 1;
      static final int deproko_baseline_bots_keyboard_26 = 2;
    }
  }
  static class Settings {
    static Settings instance() { return new Settings(); }
    void onRequestKeyboardClose(long account, long chat, long message, boolean closed) {}
  }
  static class Tdlib { long id() { return 1; } }
  static class Chat { long id = 1, replyMarkupMessageId = 1; }
  static class CommandKeyboardLayout extends View implements PreDraw {
    Object getContext() { return null; }
    void requestLayout() {}
    __TRANSITIONS__
  }
  static class BaseComposer {
    boolean keyboardVisible;
    boolean getKeyboardState() { return keyboardVisible; }
    public boolean onKeyboardStateChanged(boolean visible) {
      boolean changed = keyboardVisible != visible;
      keyboardVisible = visible;
      return changed;
    }
  }
  static class Composer extends BaseComposer {
    boolean focused = true, commandsShown = true, commandsState = true, emojiShown;
    final Input inputView = new Input();
    final View keyboardWrapper = new View();
    final CommandKeyboardLayout keyboardLayout = new CommandKeyboardLayout();
    Panel emojiKeyboardFrameLayout;
    final Tdlib tdlib = new Tdlib();
    final Chat chat = new Chat();
    boolean isFocused() { return focused; }
    void closeEmojiKeyboard(boolean byKeyboardOpen) { emojiShown = false; }
    void updateCommandButton(int icon) {}
    void updateBottomWrapOffset() {}
    __CHANGED__
    __CLOSING__
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    Composer composer = new Composer();
    composer.keyboardLayout.hideKeyboard(composer.inputView);
    composer.onKeyboardStateChanged(false);
    check(composer.keyboardLayout.onPreDraw(), "A hidden IME must finish the bot-panel swap");
    Keyboard.showRequests = 0;
    composer.onKeyboardStateChanged(true);
    check(!composer.commandsShown, "Opening the IME must close the bot panel");
    check(Keyboard.showRequests == 0, "An IME visibility callback must not request another opening");
    check(composer.keyboardLayout.onPreDraw(), "A hidden bot panel must not discard the opening frames");

    composer = new Composer();
    composer.closeCommandsKeyboard(false);
    check(Keyboard.showRequests == 1, "Closing the bot panel by button must restore the IME");
    check(!composer.keyboardLayout.onPreDraw(), "Hold the panel replacement until its IME callback");
    composer.onKeyboardStateChanged(true);
    check(composer.keyboardLayout.onPreDraw(), "Restore callback must release drawing after the panel hides");
    for (int i = 0; i < 100; i++) {
      composer.onKeyboardStateChanged(true);
      check(composer.keyboardLayout.onPreDraw(), "Repeated visibility callbacks must never reblock drawing");
    }

    composer.keyboardLayout.hideKeyboard(composer.inputView);
    composer.focused = false;
    composer.onKeyboardStateChanged(false);
    check(composer.keyboardLayout.onPreDraw(), "An unfocused chat must still complete pending transitions");

    for (boolean showing : new boolean[] {false, true}) {
      CommandKeyboardLayout layout = new CommandKeyboardLayout();
      SystemClock.now = 1000;
      if (showing) layout.showKeyboard(new Input()); else layout.hideKeyboard(new Input());
      check(!layout.onPreDraw(), "Wait for an unacknowledged panel replacement");
      SystemClock.now += 250;
      check(layout.onPreDraw(), "Release missing callbacks by elapsed time, including low frame rates");
    }

    CommandKeyboardLayout reversed = new CommandKeyboardLayout();
    reversed.showKeyboard(new Input());
    SystemClock.now += 249;
    reversed.hideKeyboard(new Input());
    SystemClock.now++;
    reversed.onKeyboardStateChanged(true);
    check(!reversed.onPreDraw(), "An old visibility callback must not complete a reversed request");
    reversed.onKeyboardStateChanged(false);
    check(reversed.onPreDraw(), "The new visibility must complete the reversed request");

    composer = new Composer();
    composer.keyboardLayout.hideKeyboard(composer.inputView);
    composer.forceCloseCommandsKeyboard();
    check(composer.keyboardLayout.onPreDraw(), "Forced panel closure must cancel the old draw gate");

    CommandKeyboardLayout layout = new CommandKeyboardLayout();
    layout.onAttachedToWindow();
    check(layout.observer.listeners.contains(layout), "An attached bot panel must observe its window draws");
    layout.hideKeyboard(new Input());
    layout.onDetachedFromWindow();
    check(layout.onPreDraw(), "Detaching must cancel a pending transition");
    check(layout.observer.listeners.isEmpty(), "A detached chat must not keep a window-wide draw listener");
    layout.onAttachedToWindow();
    check(layout.observer.listeners.size() == 1, "Reused chats must restore one draw listener");
    layout.onDetachedFromWindow();
  }
}
"""
        harness = harness.replace("__TRANSITIONS__", transitions)
        harness = harness.replace("__CHANGED__", changed).replace("__CLOSING__", closing)
        run_java(harness)


if __name__ == "__main__":
    unittest.main()
