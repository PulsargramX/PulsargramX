"""Exercise quote routing, destination permissions and topic handoff without Android."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
UI = "app/src/main/java/org/thunderdog/challegram/ui/"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class QuoteSharingBehaviorTest(unittest.TestCase):
  def test_channel_quotes_open_a_composer_with_the_original_source(self):
    controller = read(UI + "MessagesController.java")
    methods = "\n".join(java_method(controller, signature) for signature in (
      "public static class ReplyInfo",
      "public boolean canQuoteMessage (",
      "public void quoteMessage (",
      "private static boolean shouldIgnoreDraftLoad (",
    ))
    shared_reply = java_method(controller, "if (item instanceof ReplyInfo)")
    topics = read(UI + "ForumTopicsController.java")
    topic_methods = "\n".join(java_method(topics, signature) for signature in (
      "public static class Arguments",
      "private void openTopic (",
      "private void openTopicAtMessage (",
    ))
    harness = r'''
import java.util.*;
import java.util.function.Predicate;
import org.drinkless.tdlib.TdApi;
public class QuoteSharingTest {
  static class MessageWithProperties {
    final TdApi.Message message;
    final TdApi.MessageProperties properties = new TdApi.MessageProperties();
    MessageWithProperties(long chatId, long id) {
      message = new TdApi.Message(); message.chatId = chatId; message.id = id;
    }
  }
  static class Tdlib {
    final Set<Long> unwritable = new HashSet<>();
    final List<Runnable> queued = new ArrayList<>();
    boolean canSendBasicMessage(TdApi.Chat chat) {return !unwritable.contains(chat.id);}
    Tdlib ui() {return this;}
    void post(Runnable action) {queued.add(action);}
    void drain() {
      for (Runnable action : new ArrayList<>(queued)) action.run();
      queued.clear();
    }
  }
  static class Td {
    static boolean isEmpty(TdApi.InputTextQuote quote) {
      return quote == null || quote.text == null || quote.text.text.isEmpty();
    }
    static boolean equalsTo(Object a, Object b) {return Objects.equals(a, b);}
  }
  static class TD {
    static boolean isScheduled(TdApi.Message message) {return message.schedulingState != null;}
    CAN_REPLY
  }
  static class TGSwitchInline {}
  static class MessageId {
    MessageId(TdApi.Message message) {}
    MessageId(long chat, long id) {}
    static long fromServerMessageId(int id) {return (long) id << 20;}
  }
  static class TdlibUi {
    static class ChatOpenParameters {
      boolean keep;
      ChatOpenParameters keepStack() {keep = true; return this;}
    }
  }
  static class R {static class string {static final int YouCantSendMessages = 1, QuoteTo = 2;}}
  static class Toast {static final int LENGTH_SHORT = 0;}
  static class UI {static void showToast(int text, int duration) {}}
  static class ChatsController {
    interface PickerDelegate {
      boolean onChatPicked(TdApi.Chat chat, Runnable onDone);
      Object getShareItem();
      int getTitleStringRes();
      void modifyChatOpenParams(TdlibUi.ChatOpenParameters params);
    }
    static class Arguments {
      Predicate<TdApi.Chat> filter;
      PickerDelegate delegate;
      Arguments(Predicate<TdApi.Chat> filter, PickerDelegate delegate) {
        this.filter = filter; this.delegate = delegate;
      }
    }
    Arguments args;
    ChatsController(Object context, Tdlib tdlib) {}
    void setArguments(Arguments args) {this.args = args;}
  }
  static class ChatPickerController extends ChatsController {
    ChatPickerController(Object context, Tdlib tdlib) {super(context, tdlib);}
  }
  static class MessagesController {
    final Object context = new Object();
    final Tdlib tdlib;
    boolean writable, secret, preview, forceTouch;
    ChatsController picker;
    ReplyInfo displayed;
    Arguments arguments;
    boolean userReply, keyboard;
    int replies, selectionFinishes;
    boolean selecting, animatedSelectionHeader;
    MessagesController(Object context, Tdlib tdlib) {this.tdlib = tdlib;}
    METHODS
    void shareItem(Object item) {SHARED_REPLY}
    boolean canWriteMessages() {return writable;}
    boolean hasSendBasicMessagePermission() {return writable;}
    boolean isSecretChat() {return secret;}
    boolean inPreviewMode() {return preview;}
    boolean isInForceTouchMode() {return forceTouch;}
    boolean inSelectMode() {return selecting;}
    void finishSelectMode(int position) {selectionFinishes++;}
    void finishSelectMode(int position, boolean animatedHeader) {
      check(position == -1, "Finish all message selection before navigating");
      selectionFinishes++; animatedSelectionHeader = animatedHeader; selecting = false;
    }
    void navigateTo(ChatsController picker) {
      check(!selecting && !animatedSelectionHeader,
            "Restore the source header synchronously before opening the picker");
      this.picker = picker;
    }
    void showReply(MessageWithProperties message, TdApi.InputTextQuote quote,
                   int taskId, String optionId, boolean byUser, boolean showKeyboard) {
      displayed = new ReplyInfo(tdlib, message, quote, taskId, optionId);
      userReply = byUser; keyboard = showKeyboard; replies++;
    }
    Object itemToShare;
    void setShareItem(Object item) {itemToShare = item;}
    void setArguments(Arguments args) {arguments = args;}
    static class Arguments {
      TdApi.MessageTopic topic;
      TdApi.ForumTopic forumTopic;
      Arguments(Object list, TdApi.Chat chat, Object thread, TdApi.MessageTopic topic,
                MessageId highlight, int mode, Object filter) {this.topic = topic;}
      void setForumTopic(TdApi.ForumTopic topic) {forumTopic = topic;}
    }
  }
  static class MessagesManager {
    static final int HIGHLIGHT_MODE_NONE = 0, HIGHLIGHT_MODE_UNREAD = 1;
    static final int HIGHLIGHT_MODE_NORMAL = 2;
  }
  static class ForumTopicsController {
    final Object context = new Object();
    final Tdlib tdlib;
    long chatId;
    TdApi.Chat chat;
    Arguments arguments;
    MessagesController opened;
    boolean navigatedFromSearch;
    ForumTopicsController(Tdlib tdlib, TdApi.Chat chat, Object share) {
      this.tdlib = tdlib; this.chat = chat; chatId = chat.id;
      arguments = new Arguments(chat).setShareItem(share);
    }
    Arguments getArguments() {return arguments;}
    void navigateTo(MessagesController controller) {opened = controller;}
    TOPIC_METHODS
  }
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  static TdApi.Chat chat(long id, TdApi.ChatType type) {
    TdApi.Chat chat = new TdApi.Chat(); chat.id = id; chat.type = type; return chat;
  }
  public static void main(String[] args) {
    Tdlib tdlib = new Tdlib();
    MessagesController source = new MessagesController(null, tdlib);
    MessageWithProperties post = new MessageWithProperties(-100, 42);
    post.properties.canBeRepliedInAnotherChat = true;
    TdApi.TextEntity bold = new TdApi.TextEntity(0, 6, new TdApi.TextEntityTypeBold());
    TdApi.InputTextQuote quote = new TdApi.InputTextQuote(
      new TdApi.FormattedText("quoted part", new TdApi.TextEntity[]{bold}), 13);
    check(source.canQuoteMessage(post), "Offer Quote in a channel without a composer");
    source.selecting = true;
    source.quoteMessage(post, quote);
    check(source.picker != null && source.replies == 0 && source.selectionFinishes == 1,
          "Open the destination picker immediately without waiting for a header animation");
    check(tdlib.queued.isEmpty(), "Do not queue a delayed picker navigation");
    ChatsController.Arguments picker = source.picker.args;
    check(picker.delegate.getTitleStringRes() == R.string.QuoteTo,
          "Give the quote picker its own title");
    TdApi.Chat destination = chat(20, new TdApi.ChatTypePrivate(20));
    TdApi.Chat forum = chat(-200, new TdApi.ChatTypeSupergroup(200, false));
    TdApi.Chat secret = chat(30, new TdApi.ChatTypeSecret(30, 20));
    check(picker.filter.test(destination) && picker.filter.test(forum),
          "Allow writable chats and forums");
    check(!picker.filter.test(secret) && !picker.delegate.onChatPicked(secret, () -> {}),
          "Exclude secret chats from external replies");
    tdlib.unwritable.add(destination.id);
    check(!picker.filter.test(destination) &&
          !picker.delegate.onChatPicked(destination, () -> {}),
          "Recheck destination write permissions when selected");
    tdlib.unwritable.clear();
    check(picker.delegate.onChatPicked(destination, () -> {}), "Accept a writable destination");
    TdlibUi.ChatOpenParameters params = new TdlibUi.ChatOpenParameters();
    picker.delegate.modifyChatOpenParams(params);
    check(params.keep, "Preserve the source channel in navigation history");
    MessagesController.ReplyInfo payload = (MessagesController.ReplyInfo) picker.delegate.getShareItem();
    MessagesController target = new MessagesController(null, tdlib);
    target.writable = true;
    target.shareItem(payload);
    check(target.displayed.message == post && target.displayed.quote == quote &&
          target.userReply && target.keyboard, "Open the destination composer with the selected quote");
    TdApi.InputMessageReplyToExternalMessage external = (TdApi.InputMessageReplyToExternalMessage)
      target.displayed.withTopic(destination.id, null).toInputMessageReply();
    check(external.chatId == post.message.chatId && external.messageId == post.message.id &&
          external.quote == quote && external.quote.position == 13 &&
          external.quote.text.entities[0] == bold,
          "Preserve source attribution, selection offset and formatting when sending");
    check(MessagesController.shouldIgnoreDraftLoad(payload),
          "Keep an old draft reply from replacing the selected quote");
    target.secret = true; target.shareItem(payload);
    target.secret = false; target.writable = false; target.shareItem(payload);
    MessagesController otherAccount = new MessagesController(null, new Tdlib());
    otherAccount.writable = true; otherAccount.shareItem(payload);
    check(target.replies == 1 && otherAccount.replies == 0,
          "Reject secret, unwritable and other-account destination composers");
    post.properties.canBeRepliedInAnotherChat = false;
    check(!source.canQuoteMessage(post), "Respect the source's external reply permission");
    source.picker = null; source.quoteMessage(post, quote);
    check(source.picker == null, "Keep protected messages out of the destination picker");
    source.writable = true; post.properties.canBeReplied = true;
    source.quoteMessage(post, quote);
    check(source.displayed.quote == quote && source.picker == null,
          "Continue quoting locally when a local reply is allowed");
    check(source.displayed.withTopic(post.message.chatId, null).toInputMessageReply()
          instanceof TdApi.InputMessageReplyToMessage, "Keep local replies local");
    source.secret = true;
    check(!source.canQuoteMessage(post), "Do not quote secret chat messages");
    source.secret = false; source.preview = true;
    check(!source.canQuoteMessage(post), "Do not quote from a preview");
    source.preview = false; source.forceTouch = true;
    check(!source.canQuoteMessage(post), "Do not quote from a force touch preview");
    source.forceTouch = false;
    post.message.schedulingState = new TdApi.MessageSchedulingStateSendWhenOnline();
    check(!source.canQuoteMessage(post), "Do not quote a scheduled post");
    post.message.schedulingState = null;
    int replies = source.replies;
    source.quoteMessage(post, new TdApi.InputTextQuote(new TdApi.FormattedText("", null), 0));
    check(source.replies == replies, "Ignore an empty selection");
    post.properties.canBeRepliedInAnotherChat = true;
    ForumTopicsController topics = new ForumTopicsController(tdlib, forum, payload);
    TdApi.ForumTopic topic = new TdApi.ForumTopic();
    topic.info = new TdApi.ForumTopicInfo(); topic.info.forumTopicId = 9;
    topics.openTopic(topic);
    check(topics.opened.itemToShare == payload && topics.opened.arguments.forumTopic == topic &&
          ((TdApi.MessageTopicForum) topics.opened.arguments.topic).forumTopicId == 9,
          "Carry the quote through topic selection");
    topics.openTopicAtMessage(topic, post.message);
    check(topics.opened.itemToShare == payload && topics.navigatedFromSearch,
          "Keep the quote when the topic is chosen through message search");
    TdApi.InputMessageReplyToExternalMessage inTopic = (TdApi.InputMessageReplyToExternalMessage)
      payload.withTopic(forum.id, topics.opened.arguments.topic).toInputMessageReply();
    check(inTopic.chatId == post.message.chatId && inTopic.quote == quote,
          "Keep the original source when quoting into a topic");
  }
}
'''.replace("TOPIC_METHODS", topic_methods).replace("METHODS", methods)
    harness = harness.replace("SHARED_REPLY", shared_reply).replace(
      "CAN_REPLY", java_method(
        read("app/src/main/java/org/thunderdog/challegram/data/TD.java"),
        "public static boolean canReplyTo (",
      )
    ).replace("@Nullable ", "").replace("@NonNull ", "")
    with tempfile.TemporaryDirectory() as directory:
      work = Path(directory)
      annotations = []
      for name, body in (
        ("Nullable", "public @interface Nullable {}"),
        ("IntDef", "public @interface IntDef {long[] value() default {}; "
         "boolean flag() default false;}"),
      ):
        path = work / "androidx/annotation" / (name + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package androidx.annotation; " + body)
        annotations.append(path)
      test = work / "QuoteSharingTest.java"
      test.write_text(harness)
      subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(work), *map(str, annotations), str(test),
         str(ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java")], check=True
      )
      subprocess.run(["java", "-cp", str(work), "QuoteSharingTest"], check=True)

  def test_selection_and_topic_picker_use_the_quote_handoff(self):
    selection = read(
      "app/src/main/java/org/thunderdog/challegram/component/chat/MessageTextSelectionManager.java"
    )
    self.assertIn("controller.canQuoteMessage(", java_method(selection, "private boolean canQuote ()"))
    self.assertIn("controller.quoteMessage(", java_method(selection, "private void quote ()"))
    navigation = java_method(
      read("app/src/main/java/org/thunderdog/challegram/telegram/TdlibUi.java"),
      "public void openChat (final TdlibDelegate context, final @NonNull TdApi.Chat chatFinal,",
    )
    self.assertIn("new ForumTopicsController.Arguments(chat).setShareItem(shareItem)", navigation)
    self.assertIn("chat.viewAsTopics || shareItem instanceof MessagesController.ReplyInfo", navigation)
    self.assertIn("closeSelectMode(animatedHeader)", java_method(
      read(UI + "MessagesController.java"),
      "private void finishSelectMode (int position, boolean animatedHeader)",
    ))
    nav = "app/src/main/java/org/thunderdog/challegram/navigation/"
    self.assertIn("headerView.closeSelectMode(animated)", java_method(
      read(nav + "ViewController.java"), "public final void closeSelectMode (boolean animated)",
    ))
    self.assertIn("closeSelectMode(false, animated)", java_method(
      read(nav + "HeaderView.java"), "public void closeSelectMode (boolean animated)",
    ))


if __name__ == "__main__":
  unittest.main()
