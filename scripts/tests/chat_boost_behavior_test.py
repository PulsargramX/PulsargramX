#!/usr/bin/env python3
"""Exercise chat boost actions with controlled TDLib replies and time."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ChatBoostBehaviorTest(unittest.TestCase):
    def test_boost_and_reassignment_flow(self):
        source = read("app/src/main/java/org/thunderdog/challegram/ui/ChatBoostController.java")
        methods = "\n".join(java_method(source, signature) for signature in (
            "private boolean isUsable (",
            "private TdApi.ChatBoostSlot findUsableSlot (",
            "private TdApi.ChatBoostSlot unusedSlot ()",
            "private List<TdApi.ChatBoostSlot> reassignmentSlots ()",
            "private void addReassignmentSlots (",
            "private void updateBoostButton ()",
            "private void onBoostButtonClick ()",
            "private void applySelectedBoosts ()",
            "private void applyBoost (",
        ))
        resources = "static class R {\n"
        for kind in ("string", "drawable", "id"):
            names = sorted(set(re.findall(r"R\." + kind + r"\.(\w+)", methods)))
            resources += "static class " + kind + " {\n"
            resources += "\n".join(
                f"static final int {name} = {index};"
                for index, name in enumerate(names, 1)
            ) + "\n}\n"
        resources += "}\n"
        harness = r"""
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class ChatBoostTest {
  static class TdApi {
    static class ChatBoostSlot {
      int slotId, expirationDate, cooldownUntilDate;
      long currentlyBoostedChatId;
      ChatBoostSlot(int id, long chatId, int expiry, int cooldown) {
        slotId = id;
        currentlyBoostedChatId = chatId;
        expirationDate = expiry;
        cooldownUntilDate = cooldown;
      }
    }
    static class ChatBoostStatus {
      int nextLevelBoostCount = 10;
      int[] appliedSlotIds = {};
      String boostUrl = "boost-link";
    }
    static class ChatBoostSlots {
      ChatBoostSlot[] slots;
      ChatBoostSlots(ChatBoostSlot[] slots) { this.slots = slots; }
    }
    static class BoostChat {
      long chatId;
      int[] slots;
      BoostChat(long chatId, int[] slots) { this.chatId = chatId; this.slots = slots; }
    }
    static class GetSupergroupFullInfo {
      long chatId;
      GetSupergroupFullInfo(long chatId) { this.chatId = chatId; }
    }
    static class Error {}
  }
  static class Tdlib {
    long now = 1000;
    final List<TdApi.BoostChat> requests = new ArrayList<>();
    final List<Long> fullInfoRequests = new ArrayList<>();
    BiConsumer<TdApi.ChatBoostSlots, TdApi.Error> reply;
    long currentTime(TimeUnit unit) { return now; }
    boolean isChannel(long id) { return true; }
    String chatTitle(long id) { return "Chat " + id; }
    void send(TdApi.BoostChat request,
              BiConsumer<TdApi.ChatBoostSlots, TdApi.Error> reply) {
      requests.add(request);
      this.reply = reply;
    }
    void send(TdApi.GetSupergroupFullInfo request, BiConsumer<Object, TdApi.Error> reply) {
      fullInfoRequests.add(request.chatId);
    }
  }
  static class Lang {
    static String getString(int id, Object... args) { return "string:" + id; }
    static String plural(int id, int count) { return "plural:" + id + ":" + count; }
  }
  static class UI {
    static void showError(Object error) {}
    static void showToast(String text, int duration) {}
  }
  static class Toast { static final int LENGTH_SHORT = 0; }
  static class OptionColor { static final int BLUE = 0; }
  static class ChatId { static long toSupergroupId(long id) { return id; } }
  static class Button {
    String text;
    boolean enabled;
    void setText(CharSequence text) { this.text = text.toString(); }
    void setEnabled(boolean enabled) { this.enabled = enabled; }
    void setAlpha(float alpha) {}
  }
  static class Sheet {
    boolean hidden;
    void hidePopupWindow(boolean animated) { hidden = true; }
  }
  static class ListItem {
    static final int TYPE_SEPARATOR = 1, TYPE_DESCRIPTION = 2, TYPE_PROGRESS = 3;
    Object data;
    ListItem(int type) {}
    ListItem(int type, int id, int icon, int text) {}
  }
  final Tdlib tdlib = new Tdlib();
  final Sheet sheet = new Sheet();
  final Button boostButton = new Button();
  final Set<Integer> selectedSlots = new HashSet<>();
  TdApi.ChatBoostSlot[] slots = {};
  TdApi.ChatBoostStatus status = new TdApi.ChatBoostStatus();
  boolean applying, slotsFailed, loadFailed, destroyed, reassign, pickerShown, shared;
  int pendingLoads, refreshes, resetLists, successCallbacks;
  static final int CELL_SLOT = 2;
  Runnable confirmation;
  class Args {
    Runnable onBoostApplied;
  }
  final Args arguments = new Args();
  void enablePicker() {
    reassign = true;
    arguments.onBoostApplied = () -> successCallbacks++;
  }
  Args getArgumentsStrict() { return arguments; }
  long chatId() { return 100; }
  boolean isReassignment() { return reassign; }
  boolean isDestroyed() { return destroyed; }
  void runOnUiThreadOptional(Runnable action) { if (!destroyed) action.run(); }
  void runOnUiThread(Runnable action) { action.run(); }
  void refresh() { refreshes++; }
  void resetBoosters() { resetLists++; }
  void buildCells() { selectedSlots.removeIf(id -> findUsableSlot(id) == null); }
  void showReassignment() { pickerShown = true; }
  void shareBoostLink() { shared = true; }
  void showConfirm(String text, String button, int icon, int color, Runnable action) {
    confirmation = action;
  }
  void addCustom(List<ListItem> items, int type, Object data, long id, int actionId) {
    ListItem item = new ListItem(type);
    item.data = data;
    items.add(item);
  }
  static TdApi.ChatBoostSlot slot(int id, long chat, int cooldown) {
    return new TdApi.ChatBoostSlot(id, chat, 2000, cooldown);
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  static void testActions() {
    ChatBoostTest c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(1, 200, 0), slot(2, 0, 0), slot(3, 0, 0)};
    c.onBoostButtonClick();
    check(c.tdlib.requests.size() == 1, "Boost immediately with an unused slot");
    check(Arrays.equals(c.tdlib.requests.get(0).slots, new int[] {2}),
      "Use one unused slot before considering reassignment");
    check(c.confirmation == null && !c.pickerShown, "Unused boosts need no selection dialog");
    c.onBoostButtonClick();
    check(c.tdlib.requests.size() == 1, "Repeated clicks cannot submit twice");
    c.updateBoostButton();
    check(!c.boostButton.enabled, "Disable the button while applying");
    c.slots[1].currentlyBoostedChatId = 100;
    c.status.appliedSlotIds = new int[] {2};
    c.tdlib.reply.accept(new TdApi.ChatBoostSlots(c.slots), null);
    check(!c.applying && !c.sheet.hidden && c.refreshes == 1,
      "Direct boosting reloads the chat sheet and keeps it open");
    c.onBoostButtonClick();
    check(c.tdlib.requests.size() == 2 &&
      Arrays.equals(c.tdlib.requests.get(1).slots, new int[] {3}),
      "A second click can give another unused boost");

    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 200, 0)};
    c.onBoostButtonClick();
    check(c.confirmation != null && c.tdlib.requests.isEmpty(),
      "Confirm before moving the sole existing boost");
    c.confirmation.run();
    check(c.tdlib.requests.size() == 1, "Confirmation applies the single boost");

    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 200, 0)};
    c.onBoostButtonClick();
    c.slots[0].currentlyBoostedChatId = 300;
    c.confirmation.run();
    check(c.tdlib.requests.isEmpty() && c.refreshes == 1,
      "A changed assignment cannot reuse an old confirmation");

    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 200, 0)};
    c.onBoostButtonClick();
    c.tdlib.now = 2000;
    c.confirmation.run();
    check(c.tdlib.requests.isEmpty() && c.refreshes == 1,
      "A boost expiring during confirmation cannot be moved");

    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 200, 0), slot(5, 300, 0)};
    c.updateBoostButton();
    check(c.boostButton.enabled, "Keep the chat boost action available for reassignment");
    c.onBoostButtonClick();
    check(c.pickerShown && c.tdlib.requests.isEmpty() && c.confirmation == null,
      "Multiple assigned boosts open the dedicated picker");

    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 200, 1100)};
    c.onBoostButtonClick();
    check(c.pickerShown && c.tdlib.requests.isEmpty(), "Show a cooling boost without moving it");
    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 100, 0)};
    c.status.appliedSlotIds = new int[] {4};
    c.onBoostButtonClick();
    check(c.sheet.hidden && c.tdlib.requests.isEmpty(), "Already applied boosts finish the sheet");
    c = new ChatBoostTest();
    c.onBoostButtonClick();
    check(c.shared && c.tdlib.requests.isEmpty(), "No boosts keeps the link sharing fallback");
    c = new ChatBoostTest();
    c.slots = new TdApi.ChatBoostSlot[] {slot(4, 0, 0)};
    c.status.nextLevelBoostCount = 0;
    c.onBoostButtonClick();
    check(c.sheet.hidden && c.tdlib.requests.isEmpty(), "Maximum level prevents boosting");
    c = new ChatBoostTest();
    c.pendingLoads = 1;
    c.onBoostButtonClick();
    check(c.tdlib.requests.isEmpty() && !c.shared, "Wait for both server queries");
    c.pendingLoads = 0;
    c.slotsFailed = true;
    c.onBoostButtonClick();
    check(c.refreshes == 1 && c.tdlib.requests.isEmpty(), "Retry failed slots instead of applying");
  }
  static void testPicker() {
    ChatBoostTest c = new ChatBoostTest();
    c.enablePicker();
    c.slots = new TdApi.ChatBoostSlot[] {
      slot(1, 0, 0), slot(2, 100, 0), slot(3, 200, 0), slot(4, 300, 1100),
      new TdApi.ChatBoostSlot(5, 400, 999, 0)
    };
    List<ListItem> items = new ArrayList<>();
    c.addReassignmentSlots(items);
    List<Integer> shown = new ArrayList<>();
    for (ListItem item : items) {
      if (item.data instanceof TdApi.ChatBoostSlot) {
        shown.add(((TdApi.ChatBoostSlot) item.data).slotId);
      }
    }
    check(shown.equals(List.of(3, 4)), "Picker shows only active boosts assigned elsewhere");
    check(c.findUsableSlot(1) == null && c.findUsableSlot(2) == null &&
      c.findUsableSlot(4) == null && c.findUsableSlot(5) == null,
      "Unused, target, cooling and expired boosts cannot be selected");
    c.updateBoostButton();
    check(!c.boostButton.enabled, "Empty selection cannot reassign");
    c.selectedSlots.add(3);
    c.tdlib.now = 1100;
    c.selectedSlots.add(4);
    c.updateBoostButton();
    check(c.boostButton.enabled, "Cooling boosts become usable at the server deadline");
    c.onBoostButtonClick();
    check(c.tdlib.requests.size() == 1 && c.tdlib.requests.get(0).slots.length == 2,
      "Apply the chosen boosts together");
    check(c.confirmation == null, "The picker action authorizes reassignment directly");
    c.slots[2].currentlyBoostedChatId = 100;
    c.slots[3].currentlyBoostedChatId = 100;
    c.tdlib.reply.accept(new TdApi.ChatBoostSlots(c.slots), null);
    check(c.successCallbacks == 1 && c.sheet.hidden && c.selectedSlots.isEmpty(),
      "Successful reassignment refreshes the parent and closes the picker");
    check(new HashSet<>(c.tdlib.fullInfoRequests).equals(Set.of(100L, 200L, 300L)),
      "Refresh group permissions for the target and previous chats");

    c = new ChatBoostTest();
    c.enablePicker();
    c.slots = new TdApi.ChatBoostSlot[] {slot(3, 200, 0)};
    c.selectedSlots.add(3);
    c.onBoostButtonClick();
    c.tdlib.reply.accept(null, new TdApi.Error());
    check(!c.applying && !c.sheet.hidden && c.successCallbacks == 0 && c.refreshes == 1,
      "Failed requests leave the picker open and reload server state");

    c = new ChatBoostTest();
    c.enablePicker();
    c.slots = new TdApi.ChatBoostSlot[] {slot(3, 200, 0)};
    c.selectedSlots.add(3);
    c.slots[0].expirationDate = 1000;
    c.buildCells();
    c.onBoostButtonClick();
    check(c.selectedSlots.isEmpty() && c.tdlib.requests.isEmpty(),
      "Prune expired selections without submitting a boost");

    c = new ChatBoostTest();
    c.enablePicker();
    c.slots = new TdApi.ChatBoostSlot[] {slot(3, 200, 0)};
    c.selectedSlots.add(3);
    c.onBoostButtonClick();
    c.destroyed = true;
    c.tdlib.reply.accept(new TdApi.ChatBoostSlots(c.slots), null);
    check(c.successCallbacks == 1 && !c.sheet.hidden &&
      new HashSet<>(c.tdlib.fullInfoRequests).equals(Set.of(100L, 200L)),
      "Dismissing an in-flight picker still updates the parent and group permissions");
  }
  public static void main(String[] args) {
    testActions();
    testPicker();
  }
"""
        with tempfile.TemporaryDirectory(prefix="chat-boost-test-") as directory:
            java_file = Path(directory) / "ChatBoostTest.java"
            java_file.write_text(harness + resources + methods + "\n}\n", encoding="utf-8")
            result = subprocess.run(
                ["javac", "--release", "17", "-d", directory, str(java_file)],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "ChatBoostTest"], capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
