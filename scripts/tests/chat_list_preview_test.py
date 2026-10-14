"""Exercise shared preview presentation and album refreshes without an APK build."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
DATA = "app/src/main/java/org/thunderdog/challegram/data/"
TOPIC = "app/src/main/java/org/thunderdog/challegram/ui/ForumTopicView.java"


class ChatListPreviewSourceTest(unittest.TestCase):
    def test_chat_and_topic_rows_use_shared_presentation(self):
        topic = read(TOPIC)
        chat = read(DATA + "TGChat.java")
        view = read("app/src/main/java/org/thunderdog/challegram/component/dialogs/ChatView.java")
        for source in (topic, chat):
            for method in ("buildText", "buildPrefix", "getSenderName", "getIconIds", "getIconColorId"):
                self.assertIn("ChatListPreview." + method + "(", source)
            self.assertIn("preview.buildFormattedText(true)", source)
        for source in (topic, view):
            self.assertIn("ChatListPreview.draw(", source)
        bind = java_method(topic, "public void setTopic (Tdlib tdlib, TdApi.ForumTopic topic, @Nullable String highlightQuery)")
        self.assertIn("currentPreview = null;", bind)
        self.assertIn("previewIconIds = null;", bind)
        self.assertIn("currentPreview = null;", java_method(topic, "public void destroy ()"))
        search = java_method(topic, "public void setMessageSearchResult (")
        self.assertIn("showingDraft = false;", search)
        self.assertIn("setMessagePreview(foundMessage);", search)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ChatListPreviewBehaviorTest(unittest.TestCase):
    def test_icons_captions_layout_and_recycled_refreshes(self):
        presentation = read(DATA + "ChatListPreview.java")
        presentation = presentation[presentation.index("public final class ChatListPreview"):]
        presentation = presentation.replace("public final class", "static final class", 1)
        content = read(DATA + "ContentPreview.java")
        content_methods = "\n".join(java_method(content, signature) for signature in (
            "public static ContentPreview getAlbumPreview (",
            "public static TdApi.Message getAlbumCaptionMessage (",
            "public TdApi.FormattedText buildFormattedText (",
            "public ContentPreview setRefresher (",
            "public boolean hasRefresher ()",
            "public void refreshContent (",
        ))
        start = content.index("    Refresher refresher = null;")
        album_refresh = content[start:content.index("    TdApi.FormattedText argument;", start)]
        topic = read(TOPIC)
        topic_methods = "\n".join(java_method(topic, signature) for signature in (
            "private void setContentPreview (",
            "public void onContentPreviewChanged (",
        ))
        resource_source = presentation + content_methods
        resources = "\n".join(
            "static class " + kind + " {static final int " + ", ".join(
                f"{name} = {index}" for index, name in enumerate(sorted(set(re.findall(
                    r"R\." + kind + r"\.(\w+)", resource_source))), 1)
            ) + ";}" for kind in ("drawable", "string")
        )
        harness = r'''
import java.util.*;
import java.util.function.Consumer;
import org.drinkless.tdlib.TdApi;
public class ChatListPreviewTest {
  @interface Nullable {}
  @interface NonNull {}
  @interface PorterDuffColorId {}
  static class R { RESOURCES }
  static class Settings {static final int CHAT_MODE_2LINE = 0, CHAT_MODE_3LINE = 1, CHAT_MODE_3LINE_BIG = 2;}
  static class ColorId {static final int NONE = 0, chatListIcon = 1;}
  static class Screen {static int dp(float value) {return Math.round(value);}}
  static class StringUtils {static boolean isEmpty(String text) {return text == null || text.isEmpty();}}
  static class TextStyleProvider {float size; TextStyleProvider(float size) {this.size = size;}}
  static class Paints {static TextStyleProvider robotoStyleProvider(float size) {return new TextStyleProvider(size);}}
  static class PorterDuffPaint {static int get(int color, float alpha) {return color;}}
  static class Lang {
    static String getString(int id) {return "label:" + id;}
    static String plural(int id, int count) {return count + " label:" + id;}
  }
  static class TD {
    static final int COMBINE_MODE_NONE = 0;
    static int getCombineMode(TdApi.Message message) {return 1;}
    static boolean isOut(TdApi.Message message) {return message.isOutgoing;}
    static TdApi.FormattedText withPrefix(String prefix, TdApi.FormattedText text) {
      return new TdApi.FormattedText(prefix + text.text, null);
    }
  }
  static class Td {
    static boolean isCall(TdApi.MessageContent content) {return content instanceof TdApi.MessageCall;}
    static boolean isProximityAlertTriggered(TdApi.MessageContent content) {return content instanceof TdApi.MessageProximityAlertTriggered;}
    static long getSenderId(TdApi.Message message) {
      return message.senderId instanceof TdApi.MessageSenderUser ? getSenderUserId(message) :
        ((TdApi.MessageSenderChat) message.senderId).chatId;
    }
    static long getSenderUserId(TdApi.Message message) {
      return message.senderId instanceof TdApi.MessageSenderUser ? ((TdApi.MessageSenderUser) message.senderId).userId : 0;
    }
    static long getMessageAuthorId(TdApi.Message message) {return getSenderId(message);}
    static void assertMessageOrigin_f2224a59() {}
    static RuntimeException unsupported(Object object) {return new IllegalArgumentException();}
    static boolean isEmpty(TdApi.FormattedText text) {return text == null || StringUtils.isEmpty(text.text);}
    static TdApi.FormattedText textOrCaption(TdApi.MessageContent content) {
      if (content instanceof TdApi.MessagePhoto) return ((TdApi.MessagePhoto) content).caption;
      if (content instanceof TdApi.MessageVideo) return ((TdApi.MessageVideo) content).caption;
      return null;
    }
  }
  static class CallItem {
    static int getSubtitleIcon(TdApi.MessageCall call, boolean outgoing) {return 99;}
    static int getSubtitleIconColorId(TdApi.MessageCall call) {return 88;}
  }
  static class IntList {
    ArrayList<Integer> ids = new ArrayList<>();
    IntList(int capacity) {}
    void append(int id) {ids.add(id);}
    int get(int index) {return ids.get(index);}
    int size() {return ids.size();}
  }
  static class SparseIntArray {
    TreeMap<Integer, Integer> counts = new TreeMap<>();
    int size() {return counts.size();}
    int keyAt(int index) {return new ArrayList<>(counts.keySet()).get(index);}
  }
  static class ArrayUtils {
    static void increment(SparseIntArray array, int key) {array.counts.merge(key, 1, Integer::sum);}
  }
  static class Tdlib {
    static class Album {
      List<TdApi.Message> messages;
      boolean more;
      Album(boolean more, TdApi.Message... messages) {this.more = more; this.messages = Arrays.asList(messages);}
      boolean mayHaveMoreItems() {return more;}
    }
    Album local, remote;
    ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    void getAlbum(TdApi.Message message, boolean onlyLocal, Album previous, Consumer<Album> callback) {
      if (onlyLocal) callback.accept(local);
      else callbacks.add(() -> callback.accept(remote));
    }
    TdApi.FormattedText getFormattedText(TdApi.Message message) {return Td.textOrCaption(message.content);}
    boolean isMultiChat(long chatId) {return chatId < 0;}
    String senderName(TdApi.Message message, boolean forward, boolean shorten) {return shorten ? "First" : "First Last";}
    void flush() {while (!callbacks.isEmpty()) callbacks.remove().run(); UI.flush();}
  }
  static class Canvas {}
  static class Drawable {
    int id;
    Drawable(int id) {this.id = id;}
    int getMinimumHeight() {return 16;}
  }
  static class Drawables {
    static ArrayList<Integer> xs = new ArrayList<>();
    static void drawRtl(Canvas canvas, Drawable drawable, int x, int y, int paint, int width, boolean rtl) {xs.add(x);}
  }
  static class DrawableProvider {Drawable getSparseDrawable(int id, int color) {return new Drawable(id);}}
  static class ComplexReceiver {}
  static class ViewProvider {}
  static class TextEntity {}
  static class TextColorSets {static class Regular {static final int NEGATIVE = 1, NORMAL = 2, LIGHT = 3;}}
  interface LineMargin {int width(int index, int y, int maxWidth, int lineHeight);}
  static class Text {
    static final int FLAG_ELLIPSIZE_NO_FILL = 1, FLAG_ELLIPSIZE_NEWLINE = 2;
    interface TextMediaListener {void changed(Text text, Object media);}
    int width, color, maxLines, x, y, lines = 1;
    boolean clickable = true, ignoreNewLines;
    String value, suffix;
    TextEntity[] entities;
    LineMargin margin;
    static class Builder {
      Text text = new Text();
      Builder(String value, int width, TextStyleProvider style, int color) {text.value = value; text.width = width; text.color = color;}
      Builder singleLine() {text.maxLines = 1; return this;}
      Builder textFlags(int flags) {return this;}
      Builder suffix(String suffix) {text.suffix = suffix; return this;}
      Builder maxLineCount(int count) {text.maxLines = count; return this;}
      Builder ignoreContinuousNewLines() {return this;}
      Builder ignoreNewLines(boolean ignore) {text.ignoreNewLines = ignore; return this;}
      Builder lineMarginProvider(LineMargin margin) {text.margin = margin; return this;}
      Builder viewProvider(ViewProvider provider) {return this;}
      Builder entities(TextEntity[] entities, TextMediaListener listener) {text.entities = entities; return this;}
      Builder noClickable() {text.clickable = false; return this;}
      Text build() {return text;}
    }
    int getWidth() {return Math.min(width, value.length() * 5);}
    int getNextLineHeight() {return 18;}
    int getLineHeight(boolean unused) {return 18;}
    int getLineCount() {return lines;}
    void draw(Canvas canvas, int x, int y, Object colors, float alpha) {this.x = x; this.y = y;}
    void draw(Canvas canvas, int x, int y, Object colors, float alpha, ComplexReceiver receiver) {draw(canvas, x, y, colors, alpha);}
  }
  PRESENTATION
  static class ContentPreview {
    static class Emoji {
      String textRepresentation;
      int iconRepresentation;
      Emoji(String text, int icon) {textRepresentation = text; iconRepresentation = icon;}
    }
    static final Emoji EMOJI_PHOTO = new Emoji("photo", R.drawable.baseline_camera_alt_16);
    static final Emoji EMOJI_ALBUM_PHOTOS = new Emoji("photos", 70), EMOJI_ALBUM_VIDEOS = new Emoji("videos", 71),
      EMOJI_ALBUM_FILES = new Emoji("files", 72), EMOJI_ALBUM_AUDIO = new Emoji("audio", 73), EMOJI_ALBUM_MEDIA = new Emoji("media", 74);
    Emoji emoji, parentEmoji;
    int placeholderText;
    TdApi.FormattedText formattedText;
    boolean hideAuthor, isHighlighted, isMediaGroup;
    Tdlib.Album album;
    Refresher refresher;
    ContentPreview(Emoji emoji, int placeholder, TdApi.FormattedText text, boolean translatable) {
      this.emoji = emoji; placeholderText = placeholder; formattedText = text;
    }
    interface Refresher {void runRefresher(ContentPreview oldPreview, RefreshCallback callback);}
    interface RefreshCallback {
      void onContentPreviewChanged(long chatId, long messageId, ContentPreview newPreview, ContentPreview oldPreview);
      default void onContentPreviewNotChanged(long chatId, long messageId, ContentPreview oldPreview) {}
    }
    static ContentPreview applyMessageFilterToPreview(ContentPreview preview) {return preview;}
    CONTENT_METHODS
    static ContentPreview forMessage(Tdlib tdlib, TdApi.Message message) {
      boolean allowContent = true;
      ALBUM_REFRESH
      return new ContentPreview(EMOJI_PHOTO, 0, Td.textOrCaption(message.content), false).setRefresher(refresher, true);
    }
  }
  static class UI {
    static ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    static void post(Runnable runnable) {callbacks.add(runnable);}
    static void flush() {while (!callbacks.isEmpty()) callbacks.remove().run();}
  }
  static class TopicRow implements ContentPreview.RefreshCallback {
    Tdlib tdlib;
    TdApi.ForumTopic topic = new TdApi.ForumTopic();
    TdApi.Message visibleMessage;
    ContentPreview currentPreview;
    TdApi.FormattedText previewFormattedText;
    String senderText;
    IntList previewIconIds;
    int previewIconColorId, listMode, lastMeasuredWidth = 320, layouts, invalidations;
    boolean showingDraft, isPreviewHighlighted;
    TopicRow(Tdlib tdlib, TdApi.Message message) {this.tdlib = tdlib; visibleMessage = message;}
    void buildTextLayouts() {layouts++;}
    void invalidate() {invalidations++;}
    TOPIC_METHODS
  }
  static void check(boolean value, String message) {if (!value) throw new AssertionError(message);}
  static TdApi.Message photo(long id, TdApi.FormattedText caption) {
    TdApi.Message message = new TdApi.Message();
    message.id = id; message.chatId = -10; message.mediaAlbumId = 7;
    message.senderId = new TdApi.MessageSenderUser(5);
    TdApi.MessagePhoto content = new TdApi.MessagePhoto(); content.caption = caption; message.content = content;
    return message;
  }
  public static void main(String[] args) {
    TdApi.FormattedText caption = new TdApi.FormattedText("caption with custom emoji", new TdApi.TextEntity[] {
      new TdApi.TextEntity(13, 12, new TdApi.TextEntityTypeCustomEmoji(42))});
    TdApi.Message first = photo(100, caption), last = photo(101, null);
    Tdlib tdlib = new Tdlib();
    ContentPreview single = new ContentPreview(ContentPreview.EMOJI_PHOTO, 0, caption, false);
    check(single.buildFormattedText(true) == caption, "Attachment icons must preserve caption text and entity offsets");
    IntList icons = ChatListPreview.getIconIds(single, first, true);
    check(icons.size() == 1 && icons.get(0) == R.drawable.baseline_camera_alt_16, "Photo uses a drawable");
    TdApi.MessageForwardInfo forward = new TdApi.MessageForwardInfo();
    forward.origin = new TdApi.MessageOriginHiddenUser("Hidden"); first.forwardInfo = forward;
    icons = ChatListPreview.getIconIds(single, first, true);
    check(icons.size() == 2 && icons.get(0) == R.drawable.baseline_share_arrow_16, "Forwarded attachment keeps both icons");
    single.parentEmoji = new ContentPreview.Emoji("pin", 80);
    icons = ChatListPreview.getIconIds(single, first, true);
    check(icons.size() == 2 && icons.get(0) == 80, "Pinned preview uses its parent icon");
    single.parentEmoji = null; first.forwardInfo = null;
    single.emoji = new ContentPreview.Emoji("sticker", 0);
    check(ChatListPreview.getIconIds(single, first, true).size() == 0, "Iconless emoji has no empty icon slot");
    check(single.buildFormattedText(true).text.startsWith("sticker "), "Iconless emoji remains in the preview");
    check(ChatListPreview.getSenderName(tdlib, first, 0).equals("First"), "Compact sender uses short name");
    first.senderId = new TdApi.MessageSenderChat(first.chatId);
    check(ChatListPreview.getSenderName(tdlib, first, 0).equals(Lang.getString(R.string.FromAnonymous)), "Anonymous compact sender");
    first.isOutgoing = true;
    check(ChatListPreview.getSenderName(tdlib, first, 1).equals(Lang.getString(R.string.FromYouAnonymous)), "Anonymous outgoing sender");
    first.isOutgoing = false; first.senderId = new TdApi.MessageSenderUser(5);
    Text prefix = ChatListPreview.buildPrefix("Sender", 300, 0, false, true);
    check(prefix.width == 120 && prefix.suffix.equals(": "), "Compact sender is capped and separated from caption");
    check(ChatListPreview.buildPrefix("Draft", 300, 0, true, false).suffix == null, "Empty draft has no colon");
    check(ChatListPreview.buildPrefix("Draft", 300, 1, true, true).color == TextColorSets.Regular.NEGATIVE, "Draft label keeps its color");
    TextEntity[] entities = {new TextEntity()};
    Text text = ChatListPreview.buildText(caption.text, entities, 200, 0, true, icons, new ViewProvider(), null);
    check(text.entities == entities && !text.clickable && text.maxLines == 1, "Caption entities survive nonclickable layout");
    check(text.margin.width(0, 0, 200, 18) == 38 && text.margin.width(1, 18, 200, 18) == 0, "Icons reserve only first line width");
    ChatListPreview.draw(new Canvas(), 0, 72, 40, 320, false, 1f, prefix, text, icons, 1, new ComplexReceiver(), new DrawableProvider());
    check(text.x == 72 + prefix.getWidth() && text.y == 40, "Compact preview follows sender on same line");
    check(Drawables.xs.get(1) - Drawables.xs.get(0) == 18, "Attachment icons use chat list spacing");
    ChatListPreview.draw(new Canvas(), 1, 72, 32, 320, false, 1f, prefix, text, icons, 1, new ComplexReceiver(), new DrawableProvider());
    check(text.x == 72 && text.y == 50, "Three line preview follows sender on next line");
    text = ChatListPreview.buildText(caption.text, entities, 200, 1, false, icons, new ViewProvider(), null);
    check(text.maxLines == 2 && !text.ignoreNewLines, "Authorless service preview can wrap");
    check(ChatListPreview.buildText(caption.text, entities, 0, 0, true, icons, new ViewProvider(), null) == null, "No preview overlaps badges when there is no width");
    tdlib.local = new Tdlib.Album(true, last); tdlib.remote = new Tdlib.Album(false, last, first);
    TopicRow row = new TopicRow(tdlib, last);
    ContentPreview original = ContentPreview.forMessage(tdlib, last);
    row.setContentPreview(original);
    check(row.currentPreview == original, "Wait for remaining album items");
    tdlib.flush();
    check(row.previewFormattedText == caption && row.previewIconIds.get(0) == 70, "Remote album caption replaces last item placeholder");
    check(row.layouts == 1 && row.invalidations == 1, "Accepted caption refresh rebuilds and redraws");
    row.setContentPreview(ContentPreview.forMessage(tdlib, last));
    ContentPreview replacement = new ContentPreview(ContentPreview.EMOJI_PHOTO, 0, new TdApi.FormattedText("Another topic", null), false);
    row.setContentPreview(replacement); tdlib.flush();
    check(row.currentPreview == replacement, "Recycled row rejects the old topic album callback");
    row.setContentPreview(ContentPreview.forMessage(tdlib, last)); row.showingDraft = true; tdlib.flush();
    check(row.currentPreview.album == null, "Draft rejects a pending message refresh");
    row.showingDraft = false; row.setContentPreview(ContentPreview.forMessage(tdlib, last)); row.topic = null; tdlib.flush();
    check(row.currentPreview.album == null, "Destroyed row rejects album callbacks");
    tdlib.local = new Tdlib.Album(false, last, first);
    ContentPreview album = ContentPreview.forMessage(tdlib, last);
    final boolean[] seen = {false};
    album.refreshContent((chatId, messageId, updated, old) -> {
      check(old == album && updated.formattedText == caption, "Cached album uses original preview and caption"); seen[0] = true;
    });
    check(seen[0], "Cached album refresh completes");
    TdApi.Message secondCaption = photo(102, new TdApi.FormattedText("Other caption", null));
    ContentPreview multiple = ContentPreview.getAlbumPreview(tdlib, last, new Tdlib.Album(false, last, first, secondCaption), true);
    check(multiple.formattedText.text.startsWith("3 "), "Albums with multiple captions use media count");
    TdApi.Message video = new TdApi.Message(); video.content = new TdApi.MessageVideo();
    ContentPreview mixed = ContentPreview.getAlbumPreview(tdlib, last, new Tdlib.Album(false, last, video), true);
    check(mixed.emoji == ContentPreview.EMOJI_ALBUM_MEDIA, "Mixed album uses shared media icon");
    System.out.println("Shared preview icons, caption entities, layout, album refresh and recycling checks passed");
  }
}
'''
        for marker, value in (("RESOURCES", resources), ("PRESENTATION", presentation),
                              ("CONTENT_METHODS", content_methods), ("ALBUM_REFRESH", album_refresh),
                              ("TOPIC_METHODS", topic_methods)):
            harness = harness.replace(marker, value)
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"]
            for name, source in {
                "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
                "androidx/annotation/IntDef.java": "package androidx.annotation; public @interface IntDef {long[] value() default {};}",
                "ChatListPreviewTest.java": harness,
            }.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
                sources.append(path)
            compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                                       *map(str, sources)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", str(work), "ChatListPreviewTest"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
