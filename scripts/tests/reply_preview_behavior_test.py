"""Exercise reply preview cache transitions without an Android build."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ReplyPreviewBehaviorTest(unittest.TestCase):
  def test_quote_changes_rebind_the_preview_and_clear_stale_entries(self):
    source = read(
      "app/src/main/java/org/thunderdog/challegram/component/chat/PinnedMessagesBar.java"
    )
    methods = "\n".join(java_method(source, signature) for signature in (
      "public static class Entry",
      "private void clearMessageList ()",
      "public void setMessageList (",
      "public void setStaticMessageList (",
      "public void setMessage (@Nullable Tdlib tdlib, @Nullable TdApi.Message message, "
      "@Nullable TdApi.InputTextQuote quote,",
      "private static ListItem itemOf (@NonNull Entry entry)",
      "private static ListItem itemOf (Tdlib tdlib, TdApi.Message message)",
    )).replace("@Nullable ", "").replace("@NonNull ", "")
    harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
public class ReplyPreviewTest {
  List<Entry> staticList;
  MessageListManager messageList;
  int focusIndex = RecyclerView.NO_POSITION;
  final Adapter messagesAdapter = new Adapter();
  final RecyclerView recyclerView = new RecyclerView();
  final Animator canExpand = new Animator(), countAnimator = new Animator();
  void collapse(boolean animated) {}
  METHODS
  interface Destroyable {void performDestroy();}
  static class TdlibAccentColor {}
  static class Tdlib {
    TdlibAccentColor messageAccentColor(TdApi.Message message) {return null;}
  }
  static class MessagesController {static class MessageInputContext {}}
  static class MediaToReplacePickerManager {static class LocalPickedFile {}}
  static class StringUtils {
    static boolean equalsTo(String a, String b) {return Objects.equals(a, b);}
  }
  // Value comparison stand-in; the production cache and list update methods run unchanged.
  static class Td {
    static boolean equalsTo(TdApi.InputTextQuote a, TdApi.InputTextQuote b) {
      if (a == b) return true;
      if (a == null || b == null) return false;
      if (a.position != b.position || !Objects.equals(a.text.text, b.text.text)) return false;
      TdApi.TextEntity[] x = a.text.entities, y = b.text.entities;
      if (x == y) return true;
      if (x == null || y == null || x.length != y.length) return false;
      for (int i = 0; i < x.length; i++) {
        if (x[i].offset != y[i].offset || x[i].length != y[i].length ||
            x[i].type.getConstructor() != y[i].type.getConstructor()) return false;
      }
      return true;
    }
  }
  static class RecyclerView {
    static final int NO_POSITION = -1;
    final LinearLayoutManager manager = new LinearLayoutManager();
    Object getLayoutManager() {return manager;}
  }
  static class LinearLayoutManager {
    int scrolls;
    void scrollToPositionWithOffset(int position, int offset) {scrolls++;}
  }
  static class Animator {
    void setValue(boolean value, boolean animated) {}
    void forceFactor(float value) {}
  }
  static class MessageListManager extends ArrayList<TdApi.Message> {
    Tdlib tdlib() {return new Tdlib();}
    int getTotalCount() {return size();}
    int getCount() {return size();}
    void addChangeListener(ReplyPreviewTest listener) {}
    void removeChangeListener(ReplyPreviewTest listener) {}
    void loadInitialChunk(Object callback) {}
  }
  static class R {static class id {static final int message = 1;}}
  static class ListItem {
    static final int TYPE_MESSAGE_PREVIEW = 1;
    Entry entry;
    ListItem(int type, int id) {}
    ListItem setData(Entry entry) {this.entry = entry; return this;}
  }
  static class Adapter {
    int binds;
    List<ListItem> items = new ArrayList<>();
    void setItems(List<ListItem> items, boolean checked) {
      this.items = items; binds++;
    }
    void setItems(ListItem[] items, boolean checked) {
      setItems(Arrays.asList(items), checked);
    }
    Entry displayed() {return items.get(0).entry;}
  }
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  static TdApi.Message message(String text) {
    TdApi.Message message = new TdApi.Message();
    message.chatId = -100; message.id = 42;
    message.content = new TdApi.MessageText(new TdApi.FormattedText(text, null), null, null);
    return message;
  }
  static TdApi.InputTextQuote quote(String text, int position) {
    return new TdApi.InputTextQuote(new TdApi.FormattedText(text, new TdApi.TextEntity[0]),
                                    position);
  }
  void expectQuote(TdApi.InputTextQuote quote) {
    check(messagesAdapter.displayed().quote == quote, "Bind the latest quote to the view");
    check(staticList.get(0).quote == quote, "Use the latest quote when the preview is clicked");
  }
  public static void main(String[] args) {
    ReplyPreviewTest bar = new ReplyPreviewTest();
    Tdlib tdlib = new Tdlib();
    TdApi.Message message = message("First part. Second part. First part.");
    bar.setMessage(tdlib, message, null, null);
    TdApi.InputTextQuote first = quote("First part.", 0);
    bar.setMessage(tdlib, message, first, null);
    bar.expectQuote(first);
    check(bar.messagesAdapter.binds == 2, "Refresh a full reply when a quote is chosen");
    TdApi.InputTextQuote second = quote("Second part.", 12);
    bar.setMessage(tdlib, message, second, null);
    bar.expectQuote(second);
    TdApi.InputTextQuote repeated = quote("First part.", 25);
    bar.setMessage(tdlib, message, repeated, null);
    bar.expectQuote(repeated);
    TdApi.InputTextQuote differentPosition = quote("First part.", 0);
    bar.setMessage(tdlib, message, differentPosition, null);
    bar.expectQuote(differentPosition);
    TdApi.InputTextQuote bold = quote("First part.", 0);
    bold.text.entities = new TdApi.TextEntity[]{
      new TdApi.TextEntity(0, 5, new TdApi.TextEntityTypeBold())
    };
    bar.setMessage(tdlib, message, bold, null);
    bar.expectQuote(bold);
    int binds = bar.messagesAdapter.binds;
    TdApi.InputTextQuote equivalent = quote("First part.", 0);
    equivalent.text.entities = new TdApi.TextEntity[]{
      new TdApi.TextEntity(0, 5, new TdApi.TextEntityTypeBold())
    };
    bar.setMessage(tdlib, message, equivalent, null);
    check(bar.messagesAdapter.binds == binds, "Skip a quote with identical text and entities");
    bar.setMessage(tdlib, message, null, null);
    bar.expectQuote(null);
    check(bar.messagesAdapter.binds == binds + 1, "Refresh when returning to a full reply");
    TdApi.Message edited = message("Edited contents");
    bar.setMessage(tdlib, edited, null, null);
    check(bar.messagesAdapter.displayed().message == edited,
          "Refresh new contents for the same message ID");
    MediaToReplacePickerManager.LocalPickedFile replacement =
      new MediaToReplacePickerManager.LocalPickedFile();
    bar.setMessage(tdlib, edited, null, replacement);
    check(bar.messagesAdapter.displayed().localPickedFile == replacement,
          "Refresh a changed media preview for the same message");
    bar.setMessage(tdlib, edited, null, null);
    check(bar.messagesAdapter.displayed().localPickedFile == null,
          "Clear the media override when returning to a reply");
    Tdlib otherAccount = new Tdlib();
    bar.setMessage(otherAccount, edited, null, null);
    check(bar.messagesAdapter.displayed().tdlib == otherAccount,
          "Refresh when the account changes");
    bar.setMessage(null, null, null, null);
    check(bar.staticList == null && bar.messagesAdapter.items.isEmpty(),
          "Clear a static preview when it is dismissed");
    bar.setMessage(tdlib, message, first, null);
    bar.expectQuote(first);
    bar.setStaticMessageList(Collections.emptyList(), RecyclerView.NO_POSITION);
    check(bar.staticList == null && bar.messagesAdapter.items.isEmpty(),
          "Clear a static preview when an empty list replaces it");
    bar.setMessage(tdlib, message, first, null);
    binds = bar.messagesAdapter.binds;
    bar.setStaticMessageList(Collections.singletonList(new Entry(tdlib, message, first)), 0);
    check(bar.messagesAdapter.binds == binds && bar.recyclerView.manager.scrolls == 1,
          "Keep scrolling to an unchanged preview without rebinding");
  }
}
'''.replace("METHODS", methods)
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
      test = work / "ReplyPreviewTest.java"
      test.write_text(harness)
      sources = [
        *annotations, test,
        ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java",
      ]
      subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(work), *map(str, sources)], check=True
      )
      subprocess.run(["java", "-cp", str(work), "ReplyPreviewTest"], check=True)


if __name__ == "__main__":
  unittest.main()
