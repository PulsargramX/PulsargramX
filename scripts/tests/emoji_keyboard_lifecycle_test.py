#!/usr/bin/env python3
"""Exercise emoji draw observation across chat removal and controller reuse."""

import shutil
import unittest

from ime_animation_behavior_test import run_java
from message_options_keyboard_source_test import read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class EmojiKeyboardLifecycleTest(unittest.TestCase):
    def test_removed_chats_release_the_window_observer(self) -> None:
        source = read("app/src/main/java/org/thunderdog/challegram/widget/KeyboardFrameLayout.java")
        start = source.index("  public void setParentView (")
        binding = source[start:source.index("  public void useHideByDetachView", start)]
        start = source.index("  private static final int STATE_NONE")
        transitions = source[start:source.index("  private static final int ANIMATOR_ADDITIONAL_HEIGHT", start)]
        transitions = transitions.replace("android.widget.EditText", "Input")
        harness = r"""
import java.util.*;
public class ImeAnimationTest {
  interface PreDraw { boolean onPreDraw(); }
  static class ViewTreeObserver {
    final List<PreDraw> listeners = new ArrayList<>();
    boolean alive = true;
    boolean isAlive() { return alive; }
    void addOnPreDrawListener(PreDraw listener) { listeners.add(listener); }
    void removeOnPreDrawListener(PreDraw listener) { listeners.remove(listener); }
    boolean draw() {
      for (PreDraw listener : new ArrayList<>(listeners)) {
        if (!listener.onPreDraw()) return false;
      }
      return true;
    }
  }
  static class View {
    interface OnAttachStateChangeListener {
      default void onViewAttachedToWindow(View view) {}
      default void onViewDetachedFromWindow(View view) {}
    }
    final List<OnAttachStateChangeListener> attachmentListeners = new ArrayList<>();
    ViewTreeObserver floatingObserver = new ViewTreeObserver();
    ViewTreeObserver windowObserver;
    boolean isAttachedToWindow() { return windowObserver != null; }
    ViewTreeObserver getViewTreeObserver() {
      return isAttachedToWindow() ? windowObserver : floatingObserver;
    }
    void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
      attachmentListeners.add(listener);
    }
    void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
      attachmentListeners.remove(listener);
    }
    void attach(ViewTreeObserver observer) {
      windowObserver = observer;
      // Android merges a detached view's observer before dispatching attachment.
      observer.listeners.addAll(floatingObserver.listeners);
      floatingObserver.alive = false;
      floatingObserver = null;
      for (OnAttachStateChangeListener listener : new ArrayList<>(attachmentListeners)) {
        listener.onViewAttachedToWindow(this);
      }
    }
    void detach() {
      // Android retains the window observer until detach listeners have run.
      for (OnAttachStateChangeListener listener : new ArrayList<>(attachmentListeners)) {
        listener.onViewDetachedFromWindow(this);
      }
      windowObserver = null;
      floatingObserver = new ViewTreeObserver();
    }
  }
  static class ViewGroup extends View {}
  static class ViewCompat {
    static boolean isAttachedToWindow(View view) { return view.isAttachedToWindow(); }
  }
  static class Input {}
  static class SystemClock {
    static long now = 1000;
    static long uptimeMillis() { return now; }
  }
  static class Keyboard {
    static void show(Input input) {}
    static void hide(Input input) {}
  }
  static class UI {
    static final UI context = new UI();
    static UI getContext(Object context) { return UI.context; }
    UI getRootView() { return this; }
    void skipNextImeAnimation(long timeout) {}
  }
  static class Content {
    void setKeyboardVisible(boolean visible) {}
    void requestLayout() {}
  }
  static class KeyboardFrameLayout extends View implements PreDraw, View.OnAttachStateChangeListener {
    private ViewGroup parentViewForAttachedMode;
    private ViewGroup parentViewForDetachedMode;
    private ViewGroup parentViewForFreezeRender;
    final Content contentView = new Content();
    Object getContext() { return null; }
    void requestLayout() {}
    __BINDING__
    __TRANSITIONS__
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    ViewTreeObserver window = new ViewTreeObserver();
    ViewGroup chat = new ViewGroup();
    chat.attach(window);
    KeyboardFrameLayout panel = new KeyboardFrameLayout();
    panel.setParentView(chat, chat, chat);
    check(window.listeners.size() == 1, "An attached chat must observe its window draws");
    panel.hideKeyboard(new Input());
    check(!window.draw(), "A pending panel swap must hold the attached window draw");
    chat.detach();
    check(window.listeners.isEmpty(), "A removed chat must release the live window observer");
    check(window.draw(), "A removed chat's pending IME request must not discard another chat's frames");
    check(panel.onPreDraw(), "Removing the chat must cancel its old keyboard wait state");

    chat.attach(window);
    check(window.listeners.size() == 1, "A reused chat must restore exactly one draw listener");
    panel.setParentView(chat, chat, chat);
    check(window.listeners.size() == 1, "Repeated parent binding must not add observers");

    panel.attach(window);
    panel.detach();
    panel.showKeyboard(new Input());
    check(!window.draw(), "A hidden detached emoji panel must still coordinate IME restoration");
    panel.onKeyboardStateChanged(true);
    check(window.draw(), "Restoring the IME must finish while the emoji panel is detached");

    ViewTreeObserver anotherWindow = new ViewTreeObserver();
    ViewGroup anotherChat = new ViewGroup();
    anotherChat.attach(anotherWindow);
    panel.setParentView(anotherChat, anotherChat, anotherChat);
    check(window.listeners.isEmpty(), "Moving a panel must release its old window observer");
    check(chat.attachmentListeners.isEmpty(), "Moving a panel must release its old parent attachment listener");
    check(anotherWindow.listeners.size() == 1, "Moving a panel must observe its new window");
    anotherChat.detach();

    ViewGroup detachedChat = new ViewGroup();
    panel.setParentView(detachedChat, detachedChat, detachedChat);
    check(detachedChat.getViewTreeObserver().listeners.isEmpty(), "Detached chats must not retain floating draw observers");
    detachedChat.attach(window);
    check(window.listeners.size() == 1, "A chat bound before attachment must register when attached");
    detachedChat.detach();
    detachedChat.attach(anotherWindow);
    check(window.listeners.isEmpty(), "Reattaching elsewhere must leave the original window clear");
    check(anotherWindow.listeners.size() == 1, "Reattaching elsewhere must follow the new observer");
    detachedChat.detach();
  }
}
"""
        harness = harness.replace("__BINDING__", binding)
        harness = harness.replace("__TRANSITIONS__", transitions)
        run_java(harness)


if __name__ == "__main__":
    unittest.main()
