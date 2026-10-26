"""Exercise forwarding shortcuts and delayed permission checks without Android."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ForwardSendShortcutsTest(unittest.TestCase):
  def test_shortcut_options_and_permission_refresh(self):
    source = read("app/src/main/java/org/thunderdog/challegram/ui/ShareController.java")
    click = java_method(source, "public void onClick (View v)")
    click = click[:click.index("} else if (viewId == R.id.btn_done)")] + "}}"
    send = java_method(source, "private void sendMessages (boolean forceGoToChat, boolean isSingleTap, @Nullable TdApi.MessageSendOptions finalSendOptions, boolean hideAuthor")
    # Stop at the transport boundary; exercise the production dispatch and async guards.
    send = send[:send.index("// Double check if there are any new restrictions")]
    send += "sent++; sentCopy = hideAuthor; sentWithoutCaptions = removeCaptions; isSent = true;}"
    methods = "\n".join(java_method(source, signature) for signature in (
      "private boolean canCopyMessages ()", "private void updateCopyButtons ()",
      "private void sendMessages (boolean forceGoToChat, boolean isSingleTap, @Nullable TdApi.MessageSendOptions finalSendOptions)"))
    harness = HARNESS.replace("METHODS", methods + click + send)
    harness = harness.replace("@Nullable ", "")
    with tempfile.TemporaryDirectory() as directory:
      work = Path(directory)
      java = work / "ForwardSendShortcutsTest.java"
      java.write_text(harness, encoding="utf-8")
      compiled = subprocess.run(["javac", "-d", str(work), str(java)],
                                capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stderr)
      result = subprocess.run(["java", "-cp", str(work), "ForwardSendShortcutsTest"],
                              capture_output=True, text=True)
      self.assertEqual(result.returncode, 0, result.stderr)


HARNESS = r'''
import java.util.*;
import java.util.function.Consumer;
public class ForwardSendShortcutsTest {
  static class TdApi {
    static class MessageProperties {
      boolean canBeCopied;
      MessageProperties(boolean value) {canBeCopied = value;}
    }
    static class MessageSendOptions {}
  }
  static class R {
    static class id {static final int btn_send = 1, btn_forwardAsCopy = 2, btn_forwardWithoutCaption = 3;}
    static class string {static final int MessageContainsPremiumFeatures = 1;}
  }
  static class View {
    final int id;
    View(int id) {this.id = id;}
    int getId() {return id;}
  }
  static class SendButton {
    boolean visible;
    void setCopyButtonsVisible(boolean value) {visible = value;}
  }
  static class Input {
    boolean premium;
    boolean hasOnlyPremiumFeatures() {return premium;}
  }
  static class Tdlib {boolean hasPremium() {return false;}}
  static class Td {static TdApi.MessageSendOptions newSendOptions() {return new TdApi.MessageSendOptions();}}
  static class Lang {static String getString(int id) {return "premium";}}
  static class Strings {static String buildMarkdown(Object a, String b, Object c) {return b;}}
  static final int MODE_MESSAGES = 0;
  int mode, sent, shared, warnings;
  boolean needHideAuthor, needRemoveCaptions, isSent, sentCopy, sentWithoutCaptions;
  boolean canShareLink = true, topicsReady = true, selfOnly;
  final List<Integer> selectedChats = new ArrayList<>(List.of(1));
  TdApi.MessageProperties[] messageProperties = properties(true), nextProperties = properties(true);
  final SendButton sendButton = new SendButton();
  final Input inputView = new Input();
  final Tdlib tdlib = new Tdlib();
  Runnable pending;
  METHODS
  boolean hasRequiredPrivateTopicSelections() {return topicsReady;}
  boolean selectedSelfChatOnly() {return selfOnly;}
  void shareLink() {shared++;}
  void performSend(boolean keyboard, TdApi.MessageSendOptions options, boolean open) {
    sendMessages(open, false, options);
  }
  void reloadMessageProperties(Consumer<Object> callback) {
    pending = () -> {messageProperties = nextProperties; updateCopyButtons(); callback.accept(null);};
  }
  void drain() {Runnable action = pending; pending = null; if (action != null) action.run();}
  ForwardSendShortcutsTest context() {return this;}
  ForwardSendShortcutsTest tooltipManager() {return this;}
  ForwardSendShortcutsTest builder(Object view) {return this;}
  ForwardSendShortcutsTest show(Object td, Object text) {warnings++; return this;}
  void hideDelayed() {}
  static TdApi.MessageProperties[] properties(boolean... values) {
    TdApi.MessageProperties[] result = new TdApi.MessageProperties[values.length];
    for (int i = 0; i < values.length; i++) result[i] = new TdApi.MessageProperties(values[i]);
    return result;
  }
  static void check(boolean value, String message) {if (!value) throw new AssertionError(message);}
  public static void main(String[] args) {
    for (int button : new int[] {R.id.btn_forwardAsCopy, R.id.btn_forwardWithoutCaption}) {
      ForwardSendShortcutsTest c = new ForwardSendShortcutsTest();
      c.needRemoveCaptions = true;
      c.onClick(new View(button));
      check(c.sent == 0 && c.pending != null, "Refresh permissions before sending");
      c.drain();
      check(c.sent == 1 && c.sentCopy, "Both shortcuts remove attribution");
      check(c.sentWithoutCaptions == (button == R.id.btn_forwardWithoutCaption), "Only the second removes captions");
      check(!c.needHideAuthor && c.needRemoveCaptions, "Shortcut leaves ordinary Send settings intact");
      c.isSent = false;
      c.onClick(new View(R.id.btn_send)); c.drain();
      check(!c.sentCopy && c.sentWithoutCaptions, "Ordinary Send retains its settings");
    }
    ForwardSendShortcutsTest c = new ForwardSendShortcutsTest();
    c.onClick(new View(R.id.btn_send));
    c.needHideAuthor = c.needRemoveCaptions = true;
    c.drain();
    check(!c.sentCopy && !c.sentWithoutCaptions, "Freeze options while permissions reload");
    c = new ForwardSendShortcutsTest();
    c.onClick(new View(R.id.btn_forwardAsCopy));
    c.nextProperties = properties(true, false); c.drain();
    check(c.sent == 0 && !c.sendButton.visible, "Revoked copy permission cancels the shortcut");
    c = new ForwardSendShortcutsTest();
    c.updateCopyButtons(); check(c.sendButton.visible, "Show shortcuts for copyable messages");
    c.topicsReady = false; c.updateCopyButtons();
    c.onClick(new View(R.id.btn_forwardAsCopy));
    check(c.pending == null && !c.sendButton.visible, "Require a target topic");
    c = new ForwardSendShortcutsTest();
    c.selectedChats.clear(); c.updateCopyButtons();
    c.onClick(new View(R.id.btn_forwardAsCopy));
    check(c.shared == 0 && c.pending == null && !c.sendButton.visible, "Shortcut needs recipients");
    c.onClick(new View(R.id.btn_send)); check(c.shared == 1, "Keep the share-link action");
    c = new ForwardSendShortcutsTest(); c.mode = 1; c.updateCopyButtons();
    c.onClick(new View(R.id.btn_forwardAsCopy));
    check(c.pending == null && !c.sendButton.visible, "No shortcuts for other share modes");
    c = new ForwardSendShortcutsTest(); c.inputView.premium = true;
    c.onClick(new View(R.id.btn_forwardWithoutCaption));
    check(c.pending == null && c.warnings == 1, "Keep premium-comment validation");
    c = new ForwardSendShortcutsTest(); c.messageProperties = null;
    check(!c.canCopyMessages(), "Wait for message properties");
    c.messageProperties = properties(); check(!c.canCopyMessages(), "Require source messages");
    c.messageProperties = new TdApi.MessageProperties[] {null};
    check(!c.canCopyMessages(), "Missing properties cannot grant copy permission");
    c = new ForwardSendShortcutsTest();
    c.onClick(new View(R.id.btn_forwardAsCopy)); c.isSent = true; c.drain();
    check(c.sent == 0, "Do not send again after another action finishes");
  }
}
'''
