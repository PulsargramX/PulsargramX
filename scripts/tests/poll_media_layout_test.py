"""Run the poll gallery and compact row code with real TDLib data and small UI stubs."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class PollMediaLayoutTest(unittest.TestCase):
    def run_java(self, files, production, main):
        files = {
            "androidx/annotation/Nullable.java":
                "package androidx.annotation; public @interface Nullable {}",
            "androidx/annotation/IntDef.java":
                "package androidx.annotation; public @interface IntDef {long[] value() default {};}",
            **files,
        }
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"]
            sources.extend(ROOT / name for name in production)
            for name, content in files.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                sources.append(path)
            result = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                                     *map(str, sources)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            subprocess.run(["java", "-cp", str(work), main], check=True)

    def test_poll_only_gallery_and_live_updates(self):
        viewer = read("app/src/main/java/org/thunderdog/challegram/mediaview/MediaViewController.java")
        methods = "\n".join(java_method(viewer, signature) for signature in (
            "public static boolean openFromPoll (", "public void onMessageContentChanged (",
            "public void onMessagesDeleted ("))
        files = {
            "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
            "org/thunderdog/challegram/data/TGMessage.java":
                "package org.thunderdog.challegram.data; public class TGMessage {}",
            "org/thunderdog/challegram/navigation/ViewController.java": '''
package org.thunderdog.challegram.navigation;
public class ViewController<T> {
  public boolean locked;
  public boolean isStackLocked() {return locked;}
  public Object context() {return null;}
  public Object tdlib() {return null;}
}
''',
            "org/thunderdog/challegram/mediaview/data/MediaItem.java": '''
package org.thunderdog.challegram.mediaview.data;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.TGMessage;
public class MediaItem {
  public long id;
  public TdApi.FormattedText caption;
  public TdApi.Message message;
  public TGMessage source;
  public TdApi.MessageSender sender;
  public int date;
  private MediaItem(long id, TdApi.FormattedText caption) {this.id = id; this.caption = caption;}
  public static MediaItem valueOf(Object c, Object t, TdApi.Photo p, TdApi.FormattedText text) {
    return new MediaItem(p.sizes[0].photo.id, text);
  }
  public static MediaItem valueOf(Object c, Object t, TdApi.Video v,
      TdApi.AlternativeVideo[] alternatives, TdApi.Photo cover, TdApi.FormattedText text) {
    return new MediaItem(v.video.id, text);
  }
  public static MediaItem valueOf(Object c, Object t, TdApi.Animation a, TdApi.FormattedText text) {
    return new MediaItem(a.animation.id, text);
  }
  public static MediaItem valueOf(Object c, Object t, TdApi.Document d, TdApi.FormattedText text) {
    return null;
  }
  public static MediaItem valueOf(Object c, Object t, TdApi.Message message) {return null;}
  public MediaItem setSourceMessage(TdApi.Message message) {this.message = message; return this;}
  public MediaItem setSourceMessage(TGMessage source) {this.source = source; return this;}
  public MediaItem setSourceSender(TdApi.MessageSender sender) {this.sender = sender; return this;}
  public MediaItem setSourceDate(int date) {this.date = date; return this;}
  public TdApi.Message getMessage() {return message;}
  public long getSourceChatId() {return message.chatId;}
  public long getSourceMessageId() {return message.id;}
}
''',
            "org/thunderdog/challegram/mediaview/data/MediaStack.java": '''
package org.thunderdog.challegram.mediaview.data;
import java.util.*;
public class MediaStack {
  public List<MediaItem> items;
  public int index;
  public MediaStack(Object context, Object tdlib) {}
  public void set(int index, List<MediaItem> items) {this.index = index; this.items = items;}
  public MediaItem get(int index) {return items.get(index);}
  public MediaItem getCurrent() {return get(index);}
  public int getCurrentIndex() {return index;}
  public List<MediaItem> getAll() {return items;}
  public int indexOfMessage(long chat, long message) {
    for (int i = 0; i < items.size(); i++)
      if (get(i).getSourceChatId() == chat && get(i).getSourceMessageId() == message) return i;
    return -1;
  }
}
''',
            "PollGalleryHarness.java": r'''
import java.util.*;
import androidx.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.mediaview.data.*;
class PollGalleryHarness {
  static final int MODE_SIMPLE = 5;
  static class Args {
    int mode; MediaStack stack; boolean noLoadMore, forceThumbs;
    Args(ViewController<?> context, int mode, MediaStack stack) {this.mode = mode; this.stack = stack;}
  }
  static Args opened;
  static void openWithArgs(ViewController<?> context, Args args) {opened = args;}
  MediaStack stack;
  Object tdlib;
  int closes, replacements, deletions;
  static class Header {void setSubtitle(Object subtitle) {}}
  Header headerCell;
  Object genSubtitle() {return null;}
  Object context() {return null;}
  void runOnUiThreadOptional(Runnable task) {task.run();}
  void replaceMedia(int index, MediaItem oldItem, MediaItem newItem) {replacements++;}
  void deleteMedia(int index, MediaItem item) {deletions++;}
  void forceClose() {closes++;}
  static class ArrayUtils {
    static int indexOf(long[] items, long target) {
      for (int i = 0; i < items.length; i++) if (items[i] == target) return i;
      return -1;
    }
  }
  METHODS
  static void check(boolean value) {if (!value) throw new AssertionError();}
  static TdApi.PollMediaPhoto photo(long id) {
    TdApi.Photo photo = new TdApi.Photo();
    TdApi.File file = new TdApi.File(); file.id = (int) id;
    photo.sizes = new TdApi.PhotoSize[]{new TdApi.PhotoSize("s", file, 36, 36, null)};
    return new TdApi.PollMediaPhoto(photo, null);
  }
  static TdApi.PollOption option(String label, TdApi.PollMedia media) {
    TdApi.PollOption option = new TdApi.PollOption();
    option.id = label; option.text = new TdApi.FormattedText(label, null); option.media = media;
    return option;
  }
  static void ids(MediaStack stack, long... ids) {
    check(stack.items.size() == ids.length);
    for (int i = 0; i < ids.length; i++) check(stack.get(i).id == ids[i]);
  }
  public static void main(String[] args) {
    ViewController<?> context = new ViewController<>();
    TGMessage source = new TGMessage();
    TdApi.Poll poll = new TdApi.Poll(); poll.type = new TdApi.PollTypeRegular();
    poll.options = new TdApi.PollOption[10];
    for (int i = 0; i < 10; i++) poll.options[i] = option("Answer " + i, photo(i + 1));
    TdApi.Message message = new TdApi.Message(); message.id = 100; message.chatId = 7;
    message.canBeSaved = false; message.senderId = new TdApi.MessageSenderChat(7); message.date = 55;
    TdApi.MessagePoll content = new TdApi.MessagePoll(poll, null, null, false);
    message.content = content;
    MediaStack gallery = PollMediaGallery.createStack(context, message, source, 7);
    ids(gallery, 1,2,3,4,5,6,7,8,9,10); check(gallery.index == 7);
    for (MediaItem item : gallery.items) {
      check(item.message == message && !item.message.canBeSaved && item.source == source);
      check(item.sender == message.senderId && item.date == 55);
    }
    poll.optionOrder = new int[]{9,2,1,0,3,4,5,6,7,8};
    gallery = PollMediaGallery.createStack(context, message, null, 2);
    ids(gallery, 10,3,2,1,4,5,6,7,8,9); check(gallery.index == 1);
    // The same photo in two answers still opens the answer that was tapped.
    poll.options[9].media = poll.options[2].media;
    gallery = PollMediaGallery.createStack(context, message, null, 2);
    check(gallery.index == 1 && gallery.getCurrent().caption.text.equals("Answer 2"));
    TdApi.Animation animation = new TdApi.Animation(); animation.animation = new TdApi.File();
    animation.animation.id = 20;
    TdApi.Video video = new TdApi.Video(); video.video = new TdApi.File(); video.video.id = 30;
    poll.options = new TdApi.PollOption[]{option("photo", photo(1)),
      option("link", new TdApi.PollMediaLink()),
      option("animation", new TdApi.PollMediaAnimation(animation)),
      option("video", new TdApi.PollMediaVideo(video, null, null, null, 0)),
      option("document", new TdApi.PollMediaDocument()), option("audio", new TdApi.PollMediaAudio())};
    poll.optionOrder = new int[]{3,1,0,5,2,4};
    content.media = photo(40); content.description = new TdApi.FormattedText("Description", null);
    TdApi.PollTypeQuiz quiz = new TdApi.PollTypeQuiz(); poll.type = quiz;
    quiz.explanationMedia = photo(50); quiz.explanation = new TdApi.FormattedText("Explanation", null);
    gallery = PollMediaGallery.createStack(context, message, source, 2);
    ids(gallery, 40,30,1,20,50); check(gallery.index == 3);
    check(PollMediaGallery.createStack(context, message, source, 1) == null);
    check(PollMediaGallery.createStack(context, message, source, 99) == null);
    check(PollMediaGallery.indexOf(content, null) == -1);
    check(PollMediaGallery.indexOf(content, content.media) == PollMediaGallery.DESCRIPTION);
    check(PollMediaGallery.indexOf(content, quiz.explanationMedia) == PollMediaGallery.EXPLANATION);
    check(openFromPoll(context, message, source, 3));
    check(opened.mode == MODE_SIMPLE && opened.noLoadMore && opened.forceThumbs);
    ids(opened.stack, 40,30,1,20,50); check(opened.stack.index == 1);
    context.locked = true; Args previous = opened;
    check(!openFromPoll(context, message, source, 3) && opened == previous);
    PollGalleryHarness h = new PollGalleryHarness(); h.stack = gallery;
    TdApi.MessagePoll updated = new TdApi.MessagePoll(poll, content.description, content.media, false);
    h.onMessageContentChanged(7, 100, updated);
    check(h.closes == 0 && h.deletions == 0 && h.replacements == 0);
    check(h.stack.getCurrent().message.content == updated);
    h.onMessagesDeleted(8, new long[]{100}); check(h.closes == 0);
    h.onMessagesDeleted(7, new long[]{999}); check(h.closes == 0);
    h.onMessagesDeleted(7, new long[]{100}); check(h.closes == 1 && h.deletions == 0);
  }
}
'''.replace("METHODS", methods),
        }
        self.run_java(files, [
            "app/src/main/java/org/thunderdog/challegram/mediaview/data/PollMediaGallery.java",
            "app/src/main/java/org/thunderdog/challegram/data/SharedPollVotePolicy.java",
        ], "PollGalleryHarness")

    def test_compact_rows_reserve_thumbnail_and_counter_space(self):
        poll = read("app/src/main/java/org/thunderdog/challegram/data/TGMessagePoll.java")
        methods = "\n".join(java_method(poll, signature) for signature in (
            "protected void buildContent (", "private int getOptionTextRight (",
            "private int getOptionTextY (", "private int getOptionHeight (",
            "public float voteRatio ("))
        harness = r'''
import org.drinkless.tdlib.TdApi;
@interface Nullable {}
class PollLayoutHarness {
  static class Screen {
    static int density = 1;
    static int dp(float value) {return Math.round(value * density);}
    static int separatorSize() {return 1;}
  }
  static class TextWrapper {
    int width, lines = 1;
    void prepare(int width) {this.width = width;}
    int getLineHeight() {return Screen.dp(18);}
    int getHeight() {return lines * getLineHeight();}
  }
  static class PollMediaAttachment {
    int thumbnailSize, fullSize;
    void build(int width) {fullSize = width;}
    void buildThumbnail(int size) {thumbnailSize = size;}
  }
  static class OptionEntry {
    TextWrapper text = new TextWrapper(); String voterCountStr; int voterCountStrWidth;
  }
  static class Lang {static String badgeCounter(int count) {return Integer.toString(count);}}
  static class Paints {static float getRegularTextPaint(float size) {return Screen.dp(size);}}
  static class U {static float measureText(String value, float size) {return value.length() * size / 2;}}
  static class State {TdApi.Poll poll;}
  TdApi.Poll poll = new TdApi.Poll();
  State state = new State();
  OptionEntry[] options = new OptionEntry[10];
  PollMediaAttachment[] optionMedia = new PollMediaAttachment[10];
  TextWrapper questionText = new TextWrapper(), descriptionText = new TextWrapper(), explanationText;
  PollMediaAttachment pollMedia = new PollMediaAttachment(), explanationMedia;
  TdApi.Poll getPoll() {return poll;}
  void updateDescription() {}
  void setRecentVoters(Object voters, boolean animated) {}
  void setQuestion(Object question) {}
  void setOptions(Object options, int[] order) {}
  void prepareProgress(Object options, int[] order) {}
  void setTexts() {}
  void setButton(boolean animated) {}
  int getOptionVotersWidth(int index) {return index == 4 ? Screen.dp(46) : 0;}
  METHODS
  static void check(boolean value) {if (!value) throw new AssertionError();}
  public static void main(String[] args) {
    for (int density : new int[]{1,2,3}) {
      Screen.density = density;
      PollLayoutHarness h = new PollLayoutHarness(); h.state.poll = h.poll;
      h.poll.canSeeResults = true; h.poll.options = new TdApi.PollOption[10];
      for (int i = 0; i < 10; i++) {
        h.options[i] = new OptionEntry(); h.optionMedia[i] = new PollMediaAttachment();
        h.poll.options[i] = new TdApi.PollOption(); h.poll.options[i].voterCount = 48 + i;
        h.poll.options[i].votePercentage = 8;
      }
      int width = Screen.dp(280); h.buildContent(width);
      int height = 0;
      for (int i = 0; i < 10; i++) {
        check(h.optionMedia[i].thumbnailSize == Screen.dp(36) && h.optionMedia[i].fullSize == 0);
        check(h.options[i].text.width + Screen.dp(34) + h.options[i].voterCountStrWidth +
          h.getOptionVotersWidth(i) + Screen.dp(8) <= h.getOptionTextRight(width));
        height += h.getOptionHeight(h.options[i].text);
      }
      check(height <= Screen.dp(470));
      check(h.pollMedia.fullSize == width && h.pollMedia.thumbnailSize == 0);
      check(Math.abs(h.voteRatio(0) - .08f) < .0001f);
      h.options[0].text.lines = 4;
      check(h.getOptionHeight(h.options[0].text) > Screen.dp(80));
      check(h.getOptionTextY(0, h.options[0].text) + h.options[0].text.getHeight() <
        h.getOptionHeight(h.options[0].text));
      h.optionMedia = new PollMediaAttachment[10]; h.buildContent(width);
      check(h.getOptionTextRight(width) == width);
      check(h.options[0].text.width > Screen.dp(200));
    }
  }
}
'''.replace("METHODS", methods)
        self.run_java({"PollLayoutHarness.java": harness}, [], "PollLayoutHarness")

    def test_thumbnail_requests_and_touch_bounds(self):
        source = read("app/src/main/java/org/thunderdog/challegram/data/PollMediaAttachment.java")
        methods = "\n".join(java_method(source, signature) for signature in (
            "void buildThumbnail (", "boolean touch ("))
        constructor = java_method(source, "PollMediaAttachment (TGMessagePoll owner,")
        constructor = constructor.replace("PollMediaAttachment (", "PollThumbnailHarness (")
        harness = r'''
import java.util.function.BiConsumer;
import org.drinkless.tdlib.TdApi;
class PollThumbnailHarness {
  static class Screen {static int dp(float value) {return Math.round(value);}}
  static class Views {boolean hasAnyTargetToInvalidate() {return true;}}
  static class TGMessagePoll {
    Views currentViews = new Views();
    Object tdlib() {return null;}
    void invalidate() {}
  }
  static class ComplexReceiver {
    int requests, clears;
    void setUpdateListener(BiConsumer<Object, Long> listener) {}
    void clear() {clears++;}
  }
  static class MediaPreview {
    int size, radius;
    MediaPreview(int size, int radius) {this.size = size; this.radius = radius;}
    static MediaPreview valueOf(Object tdlib, Object media, int size, int radius, boolean spoiler) {
      return new MediaPreview(size, radius);
    }
    static MediaPreview valueOf(Object tdlib, Object media, int size, int radius) {
      return new MediaPreview(size, radius);
    }
    static MediaPreview valueOf(Object tdlib, Object media, Object extra, int size, int radius) {
      return new MediaPreview(size, radius);
    }
    void requestFiles(ComplexReceiver receiver, boolean invalidate) {receiver.requests++;}
  }
  static class MediaPreviewSimple extends MediaPreview {
    MediaPreviewSimple(Object tdlib, int size, int radius, TdApi.Sticker sticker) {super(size, radius);}
  }
  static class MessageView {}
  static class FullAttachment {
    boolean onTouchEvent(MessageView view, MotionEvent event) {throw new AssertionError();}
  }
  static class MotionEvent {
    static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_CANCEL = 3;
    int action; float x, y;
    MotionEvent(int action, float x, float y) {this.action = action; this.x = x; this.y = y;}
    float getX() {return x;} float getY() {return y;} int getAction() {return action;}
  }
  final TdApi.PollMedia media;
  final TGMessagePoll owner;
  final ComplexReceiver receiver = new ComplexReceiver();
  MediaPreview preview;
  FullAttachment visual = new FullAttachment(), file = new FullAttachment();
  int width, height, left = 200, top = 100, opened;
  boolean thumbnail, pressed, attached;
  void attach(boolean attached) {this.attached = attached;}
  void open() {opened++;}
  CONSTRUCTOR
  METHODS
  static void check(boolean value) {if (!value) throw new AssertionError();}
  public static void main(String[] args) {
    TdApi.PollMedia[] media = {new TdApi.PollMediaPhoto(), new TdApi.PollMediaVideo(),
      new TdApi.PollMediaAnimation(), new TdApi.PollMediaAudio(), new TdApi.PollMediaDocument(),
      new TdApi.PollMediaSticker(), new TdApi.PollMediaLocation(), new TdApi.PollMediaVenue(),
      new TdApi.PollMediaLink("https://example.com", null)};
    for (TdApi.PollMedia item : media) {
      PollThumbnailHarness h = new PollThumbnailHarness(new TGMessagePoll(), item);
      h.buildThumbnail(36);
      check(h.thumbnail && h.width == 36 && h.height == 36 && h.attached);
      check(h.preview.size == 36 && h.preview.radius == 5 && h.receiver.requests == 1);
      h.buildThumbnail(36); check(h.receiver.requests == 1);
      MessageView view = new MessageView();
      check(!h.touch(view, new MotionEvent(0, 50, 110))); // Answer text remains a voting target.
      check(!h.touch(view, new MotionEvent(1, 50, 110)) && h.opened == 0);
      check(h.touch(view, new MotionEvent(0, 205, 110)));
      check(h.touch(view, new MotionEvent(1, 205, 110)) && h.opened == 1);
      check(h.touch(view, new MotionEvent(0, 205, 110)));
      check(!h.touch(view, new MotionEvent(3, 205, 110)));
      check(!h.touch(view, new MotionEvent(1, 205, 110)) && h.opened == 1);
      h.buildThumbnail(72); check(h.receiver.requests == 2 && h.width == 72);
    }
  }
}
'''.replace("CONSTRUCTOR", constructor).replace("METHODS", methods)
        self.run_java({"PollThumbnailHarness.java": harness}, [], "PollThumbnailHarness")


if __name__ == "__main__":
    unittest.main()
