#!/usr/bin/env python3
"""Exercise production IME callbacks without assembling an Android APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from message_options_keyboard_source_test import java_method, read


def run_java(source: str) -> None:
    with tempfile.TemporaryDirectory(prefix="ime-animation-test-") as directory:
        java_file = Path(directory) / "ImeAnimationTest.java"
        java_file.write_text(source, encoding="utf-8")
        subprocess.run(
            ["javac", "--release", "17", "-d", directory, str(java_file)],
            check=True, capture_output=True, text=True,
        )
        result = subprocess.run(
            ["java", "-cp", directory, "ImeAnimationTest"],
            capture_output=True, text=True,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ImeAnimationBehaviorTest(unittest.TestCase):
    def test_panel_switches_and_predictive_back(self) -> None:
        source = read(
            "app/src/main/java/org/thunderdog/challegram/widget/RootFrameLayout.java"
        )
        interface = java_method(source, "private interface ImeInsetsAnimation")
        callback = java_method(source, "private static final class ImeInsetsAnimationCallback")
        callback = callback.replace("android.view.WindowInsetsAnimation", "InsetsAnimation")
        callback = callback.replace("android.view.WindowInsets", "Insets")
        visibility = java_method(source, "protected void onWindowVisibilityChanged (")
        messages = read("app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java")
        composer = messages[messages.index("inputView = new InputView(context, tdlib, this)"):]
        touch = java_method(composer, "public boolean onTouchEvent (MotionEvent event)")
        frame = read("app/src/main/java/org/thunderdog/challegram/widget/KeyboardFrameLayout.java")
        start = frame.index("  private static final int STATE_NONE")
        end = frame.index("  private static final int ANIMATOR_ADDITIONAL_HEIGHT", start)
        transitions = frame[start:end].replace("android.widget.EditText", "InputView")
        harness = r"""
import java.util.*;
public class ImeAnimationTest {
  static class SystemClock {
    static long now = 1000;
    static long uptimeMillis() { return now; }
  }
  static class Insets {
    final int height;
    Insets(int height) { this.height = height; }
    static class Type { static int ime() { return 1; } }
  }
  static class InsetsAnimation {
    final int mask;
    final long duration;
    InsetsAnimation(int mask) { this(mask, 200); }
    InsetsAnimation(int mask, long duration) { this.mask = mask; this.duration = duration; }
    int getTypeMask() { return mask; }
    long getDurationMillis() { return duration; }
    static class Bounds {}
    abstract static class Callback {
      static final int DISPATCH_MODE_STOP = 0;
      Callback(int mode) {}
      void onPrepare(InsetsAnimation animation) {}
      Bounds onStart(InsetsAnimation animation, Bounds bounds) { return bounds; }
      void onEnd(InsetsAnimation animation) {}
      abstract Insets onProgress(Insets insets, List<InsetsAnimation> animations);
    }
  }
  static class View {
    static final int VISIBLE = 0, GONE = 8;
    int windowVisibility = VISIBLE;
    int getWindowVisibility() { return windowVisibility; }
    protected void onWindowVisibilityChanged(int visibility) { windowVisibility = visibility; }
  }
  static class RootFrameLayout extends View {
    ImeInsetsAnimation imeInsetsAnimation;
    Object animationCallback;
    Insets staticInsets = new Insets(0);
    final List<Integer> applied = new ArrayList<>();
    Runnable nextFrame;
    final Map<Runnable, Long> delayed = new HashMap<>();
    int insetRequests;
    void setWindowInsetsAnimationCallback(Object callback) { animationCallback = callback; }
    void requestApplyInsets() { insetRequests++; }
    void requestLayout() {}
    void removeCallbacks(Runnable action) {
      if (nextFrame == action) nextFrame = null;
      delayed.remove(action);
    }
    void postDelayed(Runnable action, long delay) {
      delayed.put(action, SystemClock.now + delay);
    }
    void postOnAnimation(Runnable action) { nextFrame = action; }
    Insets getRootWindowInsets() { return staticInsets; }
    void processWindowInsets(Object insets, boolean force) {
      applied.add(((Insets) insets).height);
    }
    void frame() {
      Runnable action = nextFrame;
      nextFrame = null;
      if (action != null) action.run();
    }
    void advance(long millis) {
      SystemClock.now += millis;
      for (Runnable action : new ArrayList<>(delayed.keySet())) {
        if (delayed.get(action) <= SystemClock.now) {
          delayed.remove(action);
          action.run();
        }
      }
    }
    void apply(ImeInsetsAnimationCallback callback, int height) {
      staticInsets = new Insets(height);
      if (!callback.deferInsets(staticInsets)) processWindowInsets(staticInsets, false);
    }
    void skipNextImeAnimation(long timeoutMs) { UI.callback.skipNextAnimation(timeoutMs); }
    __WINDOW_VISIBILITY__
  }
  static class UI {
    static final UI context = new UI();
    static RootFrameLayout root;
    static ImeInsetsAnimationCallback callback;
    static UI getContext(Object ignored) { return context; }
    RootFrameLayout getRootView() { return root; }
  }
  static class MotionEvent {
    static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_CANCEL = 3;
    final int action;
    MotionEvent(int action) { this.action = action; }
    int getAction() { return action; }
  }
  static class InputView {
    boolean enabled = true;
    InsetsAnimation opening;
    boolean isEnabled() { return enabled; }
    boolean onTouchEvent(MotionEvent event) {
      if (enabled && event.getAction() == MotionEvent.ACTION_UP) {
        // Android prepares its animation while handling the touch itself.
        opening = new InsetsAnimation(1);
        UI.callback.onPrepare(opening);
      }
      return true;
    }
  }
  interface PreDraw { boolean onPreDraw(); }
  static class TouchHelper {
    void onTouchEvent(InputView view, MotionEvent event) {}
    void onInputViewTouchEvent(MotionEvent event) {}
  }
  static class Content {
    final TouchHelper textFormattingLayout = new TouchHelper();
    void setKeyboardVisible(boolean visible) {}
    void requestLayout() {}
  }
  static class Keyboard {
    static int showRequests;
    static void show(InputView input) { showRequests++; }
    static void hide(InputView input) {}
  }
  static class KeyboardFrameLayout implements PreDraw {
    final Content contentView = new Content();
    Object getContext() { return null; }
    void requestLayout() {}
    __TRANSITIONS__
  }
  static class Composer {
    boolean emojiShown, commandsShown;
    KeyboardFrameLayout emojiKeyboardFrameLayout;
    KeyboardFrameLayout keyboardLayout;
    final TouchHelper inputViewDisabledClickHelper = new TouchHelper();
    final InputView input = new InputView() {
      __TOUCH__
    };
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  static void progress(ImeInsetsAnimationCallback callback, InsetsAnimation animation, int height) {
    callback.onProgress(new Insets(height), List.of(animation));
  }
  public static void main(String[] args) {
    RootFrameLayout root = new RootFrameLayout();
    ImeInsetsAnimationCallback callback = new ImeInsetsAnimationCallback(root);
    UI.root = root;
    UI.callback = callback;
    root.imeInsetsAnimation = callback;
    Composer composer = new Composer();
    root.applied.clear();
    composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
    root.apply(callback, 320);
    for (int height : new int[] {0, 80, 160, 240, 320}) {
      progress(callback, composer.input.opening, height);
    }
    callback.onEnd(composer.input.opening);
    root.frame();
    check(root.applied.equals(List.of(0, 80, 160, 240, 320, 320)),
      "Ordinary composer taps must retain the keyboard opening animation");

    composer.emojiKeyboardFrameLayout = new KeyboardFrameLayout();
    for (boolean keyboardWasOpen : new boolean[] {true, false}) {
      composer.emojiShown = true;
      if (keyboardWasOpen) {
        composer.emojiKeyboardFrameLayout.hideKeyboard(composer.input);
        InsetsAnimation hiding = new InsetsAnimation(1);
        callback.onPrepare(hiding);
        root.apply(callback, 0);
        progress(callback, hiding, 0);
        composer.emojiKeyboardFrameLayout.onKeyboardStateChanged(false);
        callback.onEnd(hiding);
        root.frame();
      } else {
        root.apply(callback, 0);
      }
      // The original panel-opening suppression has expired before the next tap.
      SystemClock.now += 1000;
      root.applied.clear();
      composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN));
      composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
      root.apply(callback, 320);
      composer.emojiShown = false;
      composer.emojiKeyboardFrameLayout.onKeyboardStateChanged(true);
      for (int height : new int[] {0, 80, 160, 240, 320}) {
        progress(callback, composer.input.opening, height);
      }
      check(root.applied.equals(List.of(320)),
        "Tapping the composer must replace the emoji panel without replaying IME frames");
      check(composer.emojiKeyboardFrameLayout.onPreDraw(),
        "The visible keyboard must complete the prepared composer transition");
      callback.onEnd(composer.input.opening);
      root.frame();
    }
    check(Keyboard.showRequests == 0, "Android must still handle the tap and show the IME");

    composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
    check(callback.isRunning(), "A hidden emoji panel must not suppress ordinary keyboard opening");
    callback.onEnd(composer.input.opening);
    root.frame();

    composer.emojiShown = true;
    for (boolean enabled : new boolean[] {false, true}) {
      composer.input.enabled = enabled;
      if (enabled) {
        composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN));
        composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_CANCEL));
      } else {
        composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
      }
      check(composer.emojiKeyboardFrameLayout.onPreDraw(),
        "Disabled or cancelled touches must not freeze drawing");
      InsetsAnimation nextAnimation = new InsetsAnimation(1);
      callback.onPrepare(nextAnimation);
      check(callback.isRunning(), "Disabled or cancelled touches must not suppress the next IME");
      callback.onEnd(nextAnimation);
      root.frame();
    }

    // Bot panels need the same preparation before Android handles a composer
    // tap. Otherwise their removal exposes the IME's intermediate heights.
    composer.emojiShown = false;
    composer.commandsShown = true;
    composer.keyboardLayout = new KeyboardFrameLayout();
    root.applied.clear();
    composer.input.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
    root.apply(callback, 320);
    composer.commandsShown = false;
    composer.keyboardLayout.onKeyboardStateChanged(true);
    for (int height : new int[] {0, 80, 160, 240, 320}) {
      progress(callback, composer.input.opening, height);
    }
    check(root.applied.equals(List.of(320)), "Tapping a bot panel must replace it at the destination height");
    check(composer.keyboardLayout.onPreDraw(), "The bot-panel swap must release the window immediately");
    callback.onEnd(composer.input.opening);
    root.frame();

    for (int destination : new int[] {0, 320}) {
      root.applied.clear();
      callback.skipNextAnimation(250);
      InsetsAnimation animation = new InsetsAnimation(1);
      callback.onPrepare(animation);
      root.apply(callback, destination);
      check(!callback.isRunning(), "Nested roots must commit panel switches immediately");
      for (int height : new int[] {0, 80, 160, 240, 320}) {
        progress(callback, animation, height);
      }
      check(root.applied.equals(List.of(destination)), "Panel switches must not replay IME frames");
      callback.onEnd(animation);
      root.frame();
      check(root.applied.equals(List.of(destination, destination)), "Settle at the destination");
    }

    root.applied.clear();
    InsetsAnimation gesture = new InsetsAnimation(1, -1);
    callback.onPrepare(gesture);
    root.apply(callback, 0);
    check(root.applied.isEmpty(), "Predictive back must defer destination insets");
    for (int height : new int[] {320, 240, 160, 240, 320}) {
      progress(callback, gesture, height);
    }
    check(callback.isRunning(), "Predictive back remains animated");
    callback.onEnd(gesture);
    root.frame();
    check(root.applied.equals(List.of(320, 240, 160, 240, 320, 320)),
      "A cancelled gesture must retain its restored keyboard height");

    callback.skipNextAnimation(250);
    SystemClock.now += 250;
    callback.onPrepare(gesture);
    check(callback.isRunning(), "An unhandled panel request must expire");
    root.apply(callback, 0);
    progress(callback, gesture, 200);
    int requests = root.insetRequests;
    callback.skipNextAnimation(250);
    check(root.insetRequests == requests + 1, "Switching during a gesture requests final insets");
    root.apply(callback, 320);
    progress(callback, gesture, 80);
    check(root.applied.get(root.applied.size() - 1) == 320, "An interrupted animation stays settled");
    callback.onEnd(gesture);

    InsetsAnimation replacement = new InsetsAnimation(1);
    callback.onPrepare(replacement);
    root.apply(callback, 0);
    progress(callback, replacement, 240);
    check(root.applied.get(root.applied.size() - 1) == 0, "Rapid reversal keeps its new destination");
    root.frame();
    callback.onEnd(replacement);
    root.frame();

    callback.skipNextAnimation(250);
    InsetsAnimation systemBar = new InsetsAnimation(2);
    callback.onPrepare(systemBar);
    callback.onEnd(systemBar);
    callback.onPrepare(gesture);
    check(!callback.isRunning(), "System bar animations must not consume the panel request");
    callback.detach();
    callback.attach();
    callback.onPrepare(gesture);
    check(callback.isRunning(), "Detaching clears pending panel requests");
    callback.detach();

    // A cancelled platform animation can disappear without delivering onEnd.
    // Suppression for a panel switch must not leak into later composer taps.
    callback.attach();
    callback.skipNextAnimation(250);
    InsetsAnimation abandoned = new InsetsAnimation(1);
    callback.onPrepare(abandoned);
    root.apply(callback, 0);
    progress(callback, abandoned, 160);
    root.advance(1000);
    InsetsAnimation opening = new InsetsAnimation(1);
    callback.onPrepare(opening);
    check(callback.isRunning(), "A new IME must discard abandoned panel suppression");
    root.applied.clear();
    root.apply(callback, 320);
    for (int height : new int[] {0, 80, 160, 240, 320}) {
      progress(callback, opening, height);
    }
    callback.onEnd(abandoned);
    check(callback.isRunning(), "A late end must not finish the replacement IME");
    callback.onEnd(opening);
    root.frame();
    check(!callback.isRunning(), "Abandoned animations must not prevent settlement");
    check(root.applied.equals(List.of(0, 80, 160, 240, 320, 320)),
      "Ordinary keyboard animation recovers without restarting the app");

    // Cancellation between preparation and the first frame must also recover
    // when there is no later animation to repair the callback's state.
    callback.onPrepare(new InsetsAnimation(1));
    root.apply(callback, 0);
    root.applied.clear();
    root.advance(1000);
    check(!callback.isRunning(), "A preparation with no start must expire");
    check(root.applied.equals(List.of(0)), "Recover the static insets after cancelled preparation");

    callback.skipNextAnimation(250);
    callback.onPrepare(new InsetsAnimation(1));
    callback.detach();
    root.applied.clear();
    root.advance(1000);
    check(root.applied.isEmpty(), "Detaching cancels prepared-animation recovery");
    callback.attach();
    callback.onPrepare(gesture);
    callback.onStart(gesture, new InsetsAnimation.Bounds());
    root.advance(5000);
    check(callback.isRunning(), "A started keyboard gesture must not time out before progress");
    progress(callback, gesture, 240);
    root.advance(5000);
    check(callback.isRunning(), "A held predictive keyboard gesture must not time out");
    callback.onEnd(gesture);
    root.frame();
    callback.detach();

    // The settlement runnable has not run yet, but the panel request is already
    // too old to suppress a new, ordinary opening/closing animation.
    callback.attach();
    for (int destination : new int[] {0, 320}) {
      callback.skipNextAnimation(250);
      InsetsAnimation panelSwitch = new InsetsAnimation(1);
      callback.onPrepare(panelSwitch);
      callback.onStart(panelSwitch, new InsetsAnimation.Bounds());
      root.apply(callback, 320 - destination);
      root.advance(251);
      callback.onEnd(panelSwitch);
      InsetsAnimation ordinary = new InsetsAnimation(1);
      callback.onPrepare(ordinary);
      check(callback.isRunning(), "An ended panel switch must not suppress an expired replacement");
      root.applied.clear();
      root.apply(callback, destination);
      progress(callback, ordinary, 160);
      check(root.applied.equals(List.of(160)), "The replacement must render intermediate IME insets");
      callback.onEnd(ordinary);
      root.frame();
    }

    // A newer panel request can arrive while the previous animation is settling.
    // Settling must not erase the request before Android prepares its animation.
    InsetsAnimation finishing = new InsetsAnimation(1);
    callback.onPrepare(finishing);
    progress(callback, finishing, 320);
    callback.onEnd(finishing);
    callback.skipNextAnimation(250);
    root.frame();
    InsetsAnimation requested = new InsetsAnimation(1);
    callback.onPrepare(requested);
    check(!callback.isRunning(), "Settlement must preserve an unconsumed panel request");
    root.applied.clear();
    root.apply(callback, 0);
    progress(callback, requested, 160);
    check(root.applied.equals(List.of(0)), "The pending panel switch must still suppress intermediate frames");
    callback.onEnd(requested);
    root.frame();
    InsetsAnimation next = new InsetsAnimation(1);
    callback.onPrepare(next);
    check(callback.isRunning(), "A settled switch must consume its suppression request");
    callback.onEnd(next);
    root.frame();

    // Missing end callbacks after onStart must recover too, whether or not the
    // abandoned animation was suppressing IME frames for a panel switch.
    for (boolean suppressed : new boolean[] {false, true}) {
      if (suppressed) callback.skipNextAnimation(250);
      InsetsAnimation stalled = new InsetsAnimation(1);
      callback.onPrepare(stalled);
      callback.onStart(stalled, new InsetsAnimation.Bounds());
      root.apply(callback, 320);
      progress(callback, stalled, 160);
      root.advance(1200);
      check(callback.animations.isEmpty(), "A started fixed-duration animation must recover without onEnd");
      check(!callback.isRunning(), "Recovery must release static inset dispatch");
      check(root.applied.get(root.applied.size() - 1) == 320,
        "Recovery must use current window insets instead of the abandoned intermediate height");
      root.applied.clear();
      root.apply(callback, 0);
      check(root.applied.equals(List.of(0)), "Static insets must work after a stalled animation");
    }

    // Normal progress renews recovery, including a long animation. A held
    // predictive gesture has no fixed duration and must never expire.
    InsetsAnimation slow = new InsetsAnimation(1, 3000);
    callback.onPrepare(slow);
    callback.onStart(slow, new InsetsAnimation.Bounds());
    root.advance(3999);
    check(callback.isRunning(), "Respect the platform animation duration before recovering");
    progress(callback, slow, 160);
    root.advance(3999);
    check(callback.isRunning(), "Fresh progress must postpone stalled-animation recovery");
    callback.onEnd(slow);
    root.frame();
    root.advance(1);
    check(!callback.isRunning(), "An ended animation must cancel recovery");

    // A system-bar frame may reveal that the IME disappeared without onEnd.
    callback.onPrepare(gesture);
    callback.onStart(gesture, new InsetsAnimation.Bounds());
    root.apply(callback, 0);
    progress(callback, gesture, 160);
    root.applied.clear();
    callback.onProgress(new Insets(320), List.of(new InsetsAnimation(2)));
    root.frame();
    check(!callback.isRunning(), "An absent IME must settle even for a durationless gesture");
    check(root.applied.equals(List.of(320)), "Use current progress when the IME stops running");

    // Progress from an interrupted animation may arrive while its replacement
    // is prepared but not started. It must not consume the replacement's state.
    callback.onPrepare(gesture);
    callback.onStart(gesture, new InsetsAnimation.Bounds());
    progress(callback, gesture, 240);
    callback.skipNextAnimation(250);
    InsetsAnimation prepared = new InsetsAnimation(1);
    callback.onPrepare(prepared);
    root.applied.clear();
    root.apply(callback, 0);
    progress(callback, gesture, 160);
    callback.onProgress(new Insets(160), List.of(new InsetsAnimation(2)));
    check(callback.animations.contains(prepared), "Older progress must retain the prepared replacement");
    check(root.applied.equals(List.of(0)), "Older progress must not render into a panel replacement");
    callback.onStart(prepared, new InsetsAnimation.Bounds());
    progress(callback, prepared, 80);
    check(root.applied.equals(List.of(0)), "The replacement must retain panel suppression");
    callback.onEnd(gesture);
    callback.onEnd(prepared);
    root.frame();

    // Hiding a retained activity root must clear even a durationless gesture.
    // Keep the callback installed so future keyboard animations still arrive.
    for (boolean suppressed : new boolean[] {false, true}) {
      if (suppressed) callback.skipNextAnimation(250);
      callback.onPrepare(gesture);
      callback.onStart(gesture, new InsetsAnimation.Bounds());
      root.apply(callback, 0);
      progress(callback, gesture, 160);
      root.onWindowVisibilityChanged(View.GONE);
      check(!callback.isRunning(), "Backgrounding must clear a retained root's animation state");
      check(callback.animations.isEmpty(), "Backgrounding must also clear suppressed gestures");
      check(root.animationCallback == callback, "Visibility changes must preserve callback registration");
      root.applied.clear();
      root.advance(5000);
      root.frame();
      callback.skipNextAnimation(250);
      callback.onPrepare(gesture);
      callback.onStart(gesture, new InsetsAnimation.Bounds());
      progress(callback, gesture, 80);
      callback.onEnd(gesture);
      check(callback.animations.isEmpty(), "Late hidden-window callbacks must not restart an animation");
      check(root.applied.isEmpty(), "Backgrounding must cancel queued settlement and recovery");
      requests = root.insetRequests;
      root.onWindowVisibilityChanged(View.VISIBLE);
      check(root.insetRequests == requests + 1, "Returning to the app must request current insets");
      InsetsAnimation resumed = new InsetsAnimation(1);
      callback.onPrepare(resumed);
      check(callback.isRunning(), "Returning to the app must restore ordinary keyboard animation");
      root.apply(callback, 320);
      progress(callback, resumed, 160);
      check(root.applied.equals(List.of(160)), "The resumed keyboard must render intermediate frames");
      callback.onEnd(resumed);
      root.frame();
    }

    InsetsAnimation detached = new InsetsAnimation(1);
    callback.onPrepare(detached);
    callback.onStart(detached, new InsetsAnimation.Bounds());
    progress(callback, detached, 160);
    callback.detach();
    root.applied.clear();
    root.advance(5000);
    root.frame();
    check(root.applied.isEmpty(), "Detaching must cancel recovery after an animation has started");
  }
"""
        harness = harness.replace("__TRANSITIONS__", transitions).replace("__TOUCH__", touch)
        harness = harness.replace("__WINDOW_VISIBILITY__", visibility)
        run_java(harness + interface + callback + "\n}\n")

    def test_read_details_do_not_delay_menu_opening(self) -> None:
        source = read("app/src/main/java/org/thunderdog/challegram/data/TGMessage.java")
        methods = "\n".join(java_method(source, signature) for signature in (
            "public final void checkReadDate (",
            "private void loadReadDate (",
        ))
        harness = r"""
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
public class ImeAnimationTest {
  interface RunnableBool { void runWithBool(boolean value); }
  interface RunnableData<T> { void runWithData(T value); }
  static class TdApi {
    static class MessageReadDate {}
    static class MessageProperties { boolean canGetReadDate = true; }
    static class GetMessageReadDate { GetMessageReadDate(long chatId, long messageId) {} }
    static class Message { long id = 1, chatId = 2; }
  }
  static class Tdlib {
    BiConsumer<TdApi.MessageReadDate, Object> reply;
    int requests;
    void send(TdApi.GetMessageReadDate request, BiConsumer<TdApi.MessageReadDate, Object> reply) {
      requests++;
      this.reply = reply;
    }
  }
  final TdApi.Message msg = new TdApi.Message();
  TdApi.MessageReadDate readDate;
  final Tdlib tdlib = new Tdlib();
  final Map<Long, TdApi.MessageProperties> cachedProperties = new HashMap<>();
  final List<Runnable> queued = new ArrayList<>();
  int propertyRequests;
  boolean isUnread() { return false; }
  boolean noUnread() { return false; }
  void getMessageProperties(long id, RunnableData<TdApi.MessageProperties> after) {
    propertyRequests++;
    after.runWithData(new TdApi.MessageProperties());
  }
  void runOnUiThreadOptional(Runnable action) { queued.add(action); }
  void runOnUiThreadOptional(Runnable action, long delay) { queued.add(action); }
  void flush() {
    while (!queued.isEmpty()) queued.remove(0).run();
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    ImeAnimationTest message = new ImeAnimationTest();
    message.cachedProperties.put(1L, new TdApi.MessageProperties());
    List<Boolean> callbacks = new ArrayList<>();
    message.checkReadDate(callbacks::add, 0);
    message.flush();
    check(callbacks.equals(List.of(true)), "Open the menu while read details are pending");
    check(message.propertyRequests == 0, "Reuse properties refreshed for the menu");
    TdApi.MessageReadDate date = new TdApi.MessageReadDate();
    message.tdlib.reply.accept(date, null);
    message.flush();
    check(callbacks.equals(List.of(true, false)), "Inject read details after opening");
    check(message.readDate == date, "Keep the fetched read date");

    callbacks.clear();
    message.checkReadDate(callbacks::add, 0);
    check(callbacks.equals(List.of(false)), "Cached read details are ready immediately");
    check(message.tdlib.requests == 1, "Do not refetch cached read details");

    message = new ImeAnimationTest();
    callbacks.clear();
    message.checkReadDate(callbacks::add, -1);
    message.flush();
    check(callbacks.isEmpty(), "Negative timeout continues waiting for data");
    message.tdlib.reply.accept(null, new Object());
    message.flush();
    check(callbacks.equals(List.of(false)), "A failed read query still opens the menu");

    message = new ImeAnimationTest();
    TdApi.MessageProperties properties = new TdApi.MessageProperties();
    properties.canGetReadDate = false;
    message.cachedProperties.put(1L, properties);
    callbacks.clear();
    message.checkReadDate(callbacks::add, 0);
    message.flush();
    check(callbacks.equals(List.of(false)), "Channel posts need no read query");
    check(message.tdlib.requests == 0, "Do not request unsupported read details");
  }
"""
        run_java(harness + methods + "\n}\n")


if __name__ == "__main__":
    unittest.main()
