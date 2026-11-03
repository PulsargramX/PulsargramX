"""Run caption layout, pending edits, and album requests with the checked-in TDLib API."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
JAVA = "app/src/main/java/org/thunderdog/challegram/"


def methods(path, *signatures):
    source = read(JAVA + path)
    return "\n".join(java_method(source, signature) for signature in signatures)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class MediaCaptionPositionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.work = Path(cls.directory.name)
        source = HARNESS.replace("ALBUM_METHODS", methods("data/TD.java",
            "public static List<TdApi.Function<?>> toFunctions (",
            "public static int getCombineMode (TdApi.InputMessageContent content)"))
        source = source.replace("PENDING_METHODS", methods("telegram/Tdlib.java",
            "public void editMessageCaption (", "public TdApi.FormattedText getPendingMessageCaption (",
            "public boolean getMessageShowCaptionAboveMedia (", "private <T extends TdApi.Object> void performEdit ("))
        source = source.replace("CAPTION_MESSAGE_METHOD", methods("data/ContentPreview.java",
            "public static TdApi.Message getAlbumCaptionMessage ("))
        source = source.replace("LAYOUT_METHODS", methods("data/TGMessageMedia.java",
            "private boolean checkCommonCaption (boolean force)", "private boolean isCaptionAboveMedia ()",
            "private int getCaptionTopPadding ()", "private int getCaptionMediaMargin ()",
            "private int getMediaTopOffset ()",
            "private int getCaptionTopOffset ()", "protected int getContentHeight ()",
            "protected boolean drawBubbleTimeOverContent ()", "protected int getBottomLineContentWidth ()",
            "protected int getFooterPaddingTop ()", "private void updateRounds ()",
            "protected void drawContent ("))
        source = source.replace("PREVIEW_METHODS", methods("mediaview/MediaViewController.java",
            "private boolean canMoveCaption ()", "private boolean isCaptionAboveMediaForSending ()",
            "private void toggleCaptionPosition ()", "private void checkCaptionButtonsY ()",
            "private int getTopCaptionMargin ()", "private void setTopOffset (int topOffset)",
            "private void updateCaptionLayout ()", "private void checkBottomWrapY ()")
            .replace("View captionControl", "Control captionControl")
            .replace("View control", "Control control").replace("new View[]", "new Control[]"))
        receiver = methods("mediaview/MediaViewController.java", "private LinearLayout buildReceiverRowView (")
        receiver_layout = receiver[receiver.index("    FrameLayoutFix.LayoutParams fp ="):
                                   receiver.index("    LinearLayout receiverView =")]
        source = source.replace("RECEIVER_LAYOUT", receiver_layout)
        sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"]
        for name, content in {
            "CaptionPositionTest.java": source,
            "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
            "androidx/annotation/IntDef.java": "package androidx.annotation; public @interface IntDef {long[] value() default {};}",
        }.items():
            path = cls.work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        result = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(cls.work),
                                 *map(str, sources)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case):
        result = subprocess.run(["java", "-cp", str(self.work), "CaptionPositionTest", case],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_album_limits_and_caption_ownership(self):
        self.run_case("albums")

    def test_rendering_and_pending_edits(self):
        self.run_case("layout")

    def test_preview_toggle_and_keyboard_position(self):
        self.run_case("preview")

    def test_picker_caption_clears_header_controls(self):
        self.run_case("picker_header")


class CaptionPositionWiringTest(unittest.TestCase):
    def test_resources_and_send_paths(self):
        resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        strings = {item.get("name"): item.text for item in resources.findall("string")}
        self.assertEqual(strings["CaptionAbove"], "Move Caption Up")
        self.assertEqual(strings["CaptionBelow"], "Move Caption Down")
        send = methods("ui/MessagesController.java", "public boolean sendPhotosAndVideosCompressed (")
        self.assertIn("showCaptionAboveMedia && !isSecretChat && !asFiles", send)
        self.assertEqual(send.count("caption, captionAboveMedia,"), 4)
        attachment = methods("component/attach/MediaLayout.java", "public boolean sendPhotosOrVideos (")
        self.assertIn("showCaptionAboveMedia() && !asFiles", attachment)
        gallery = methods("component/attach/MediaBottomGalleryController.java", "public boolean sendSelectedItems (")
        self.assertIn("mediaLayout.setShowCaptionAboveMedia(showCaptionAboveMedia)", gallery)
        edit = methods("ui/MessagesController.java", "public boolean takeOutputShowCaptionAboveMedia ()")
        self.assertIn("canMoveCaption() && showCaptionAboveMedia", edit)

    def test_text_and_media_interaction_use_relocated_bounds(self):
        source = read(JAVA + "data/TGMessageMedia.java")
        for signature in ("public boolean onTouchEvent (", "public boolean allowLongPress (",
                          "protected void onHotInvalidate ("):
            self.assertIn("getContentY() + getMediaTopOffset()", java_method(source, signature))
        for signature in ("protected boolean updateMessageContent (",
                          "protected int onMessagePendingContentChanged (",
                          "protected void onMessageCombinedWithOtherMessage (",
                          "protected void onMessageCombinationRemoved ("):
            self.assertIn("checkCommonCaption()", java_method(source, signature))


HARNESS = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
public class CaptionPositionTest {
  @interface Nullable {} @interface NonNull {}
  static void check(boolean value, String message) {if (!value) throw new AssertionError(message);}
  static TdApi.FormattedText text(String s) {return new TdApi.FormattedText(s, null);}
  static class TdConstants {static final int MAX_MESSAGE_GROUP_SIZE = 10;}
  static class Td {
    static boolean isEmpty(TdApi.FormattedText t) {return t == null || t.text.isEmpty();}
    static TdApi.FormattedText textOrCaption(TdApi.MessageContent c) {
      if (c instanceof TdApi.MessagePhoto) return ((TdApi.MessagePhoto)c).caption;
      if (c instanceof TdApi.MessageVideo) return ((TdApi.MessageVideo)c).caption;
      if (c instanceof TdApi.MessageAnimation) return ((TdApi.MessageAnimation)c).caption;
      return null;
    }
    static boolean showCaptionAboveMedia(TdApi.MessageContent c) {
      if (c instanceof TdApi.MessagePhoto) return ((TdApi.MessagePhoto)c).showCaptionAboveMedia;
      if (c instanceof TdApi.MessageVideo) return ((TdApi.MessageVideo)c).showCaptionAboveMedia;
      if (c instanceof TdApi.MessageAnimation) return ((TdApi.MessageAnimation)c).showCaptionAboveMedia;
      return false;
    }
  }
  static class TD {
    static final int COMBINE_MODE_NONE = 0, COMBINE_MODE_MEDIA = 1, COMBINE_MODE_FILES = 2, COMBINE_MODE_AUDIO = 3;
    static void parseEntities(TdApi.FormattedText caption) {}
    ALBUM_METHODS
  }
  interface ResultHandler {void onResult(TdApi.Object result);}
  static class Client {
    ArrayList<ResultHandler> handlers = new ArrayList<>();
    void send(TdApi.Function<?> request, ResultHandler handler) {handlers.add(handler);}
  }
  static class UI {static void showError(TdApi.Object result) {}}
  static class MessageEditMediaPending {
    TdApi.FormattedText caption; boolean above;
    TdApi.FormattedText getCaption() {return caption;}
    boolean showCaptionAboveMedia() {return above;}
  }
  static class Tdlib {
    HashMap<String, TdApi.EditMessageCaption> pendingMessageCaptions = new HashMap<>();
    MessageEditMediaPending media;
    Client client = new Client(); Client client() {return client;}
    Tdlib tdlib() {return this;} Tdlib ui() {return this;} void post(Runnable r) {r.run();}
    Tdlib listeners = this; void updateMessagePendingContentChanged(long c, long m) {}
    MessageEditMediaPending getPendingMessageMedia(long c, long m) {return m == 2 ? media : null;}
    TdApi.FormattedText getPendingFormattedText(long c, long m) {return getPendingMessageCaption(c, m);}
    TdApi.FormattedText getFormattedText(TdApi.Message m) {
      TdApi.FormattedText pending = getPendingFormattedText(m.chatId, m.id);
      return pending != null ? pending : Td.textOrCaption(m.content);
    }
    PENDING_METHODS
  }
  static class ContentPreview {CAPTION_MESSAGE_METHOD}
  static class Screen {
    static int dp(float n) {return Math.round(n);}
    static int getStatusBarHeight() {return 80;}
  }
  static class Lang {static boolean rtl() {return false;}}
  static class Config {static final boolean MOVE_BUBBLE_TIME_RTL_TO_LEFT = false;}
  static class Canvas {
    void drawRoundRect(Object... args) {} void drawText(Object... args) {} void drawArc(Object... args) {}
  }
  static class RectF {void set(int... args) {}}
  static class Paints {
    static RectF getRectF() {return new RectF();}
    static Object fillingPaint(int color) {return null;}
    static Object colorPaint(Object paint, int color) {return null;}
  }
  static class View {static final int VISIBLE = 0, GONE = 8;}
  static class MessageView extends View {Object getTextMediaReceiver() {return null;}}
  static class ComplexReceiver {}
  static class ViewSupport {
    static int clipPath(Canvas c, Object p) {return 0;} static void restoreClipPath(Canvas c, int n) {}
  }
  static class TextWrapper {
    int height = 30;
    int getHeight() {return height;} int getLastLineWidth() {return 100;}
    boolean getLastLineIsRtl() {return false;}
    int y, reserved;
    void draw(Canvas c, Object... args) {reserved = (int)args[2]; y = (int)args[3];}
  }
  static class MediaWrapper {
    int getCellLeft() {return 0;} int getCellRight() {return 200;} int getCellHeight() {return 180;}
    int getCenterX() {return 100;} int getCenterY() {return 90;}
  }
  static class Mosaic {
    int y; boolean top, bottom;
    int getHeight() {return 180;}
    void setNeedDefaultRounds(boolean top, boolean bottom) {this.top = top; this.bottom = bottom;}
    void draw(MessageView v, Canvas c, int x, int y, ComplexReceiver r, boolean separators) {this.y = y;}
    MediaWrapper getSingularItem() {return new MediaWrapper();}
  }
  static class Layout {
    Tdlib tdlib = new Tdlib(); TdApi.Message msg;
    ArrayList<TdApi.Message> combined;
    ArrayList<TdApi.Message> getCombinedMessagesUnsafely() {return combined;}
    boolean hasEditedText, showCaptionAboveMedia, bubbles = true, forward, footer;
    TdApi.FormattedText caption; long captionMessageId; TextWrapper wrapper;
    Mosaic mosaicWrapper = new Mosaic();
    static final float TEXT_MARGIN = 10f;
    static final int BOTTOM_LINE_EXPAND_HEIGHT = -1, xBubblePadding = 10, xBubblePaddingSmall = 5;
    boolean useBubbles() {return bubbles;} boolean useForward() {return forward;}
    boolean useFullWidth() {return false;} boolean isOutgoing() {return false;}
    boolean hasFooter() {return footer;}
    boolean needTopContentRounding() {return false;}
    boolean needBottomContentRounding() {return drawBubbleTimeOverContent();}
    int getBubbleContentPadding() {return 5;} int getBubbleTimePartWidth() {return 40;}
    int getTextX(View v, TextWrapper w, boolean rtl) {return 0;}
    float getTranslationLoadingAlphaValue() {return 1;}
    Object getBubbleClipPath() {return null;} Object mHotPaint() {return null;}
    boolean isHotTimerStarted() {return false;} float getHotExpiresFactor() {return 0;}
    String timerText; int timerWidth;
    boolean setCaption(TdApi.FormattedText caption, long id, boolean force) {
      boolean changed = !Objects.equals(this.caption, caption);
      this.caption = caption; captionMessageId = id;
      wrapper = Td.isEmpty(caption) ? null : new TextWrapper();
      updateRounds(); return changed || force;
    }
    LAYOUT_METHODS
  }
  static TdApi.Message photo(long id, String caption, boolean above) {
    TdApi.Message message = new TdApi.Message(); message.chatId = 1; message.id = id;
    TdApi.MessagePhoto photo = new TdApi.MessagePhoto(); photo.caption = text(caption);
    photo.showCaptionAboveMedia = above; message.content = photo; return message;
  }
  static void layoutTests() {
    Layout layout = new Layout(); layout.msg = photo(1, "Caption", true);
    check(layout.checkCommonCaption(false), "Read incoming caption and placement");
    for (boolean bubbles : new boolean[]{false, true}) for (boolean forward : new boolean[]{false, true}) {
      layout.bubbles = bubbles; layout.forward = forward;
      int height = layout.getContentHeight();
      layout.drawContent(new MessageView(), new Canvas(), 0, 50, 200, new ComplexReceiver());
      check(layout.wrapper.y + 30 + 4 == layout.mosaicWrapper.y, "Top caption uses mainline's compact 4dp media gap");
      check(layout.mosaicWrapper.y + 180 == 50 + height, "Media ends at content bottom");
      check(layout.wrapper.reserved == 0 && layout.drawBubbleTimeOverContent(), "Timestamp stays over media");
      check(layout.getBottomLineContentWidth() == -1, "Top caption never reserves the timestamp on its last line");
      layout.wrapper.height = 90;
      layout.drawContent(new MessageView(), new Canvas(), 0, 50, 200, new ComplexReceiver());
      check(layout.wrapper.y + 90 + 4 == layout.mosaicWrapper.y &&
        layout.mosaicWrapper.y + 180 == 50 + layout.getContentHeight(),
        "Multiline captions preserve the compact gap and exact content bounds");
      layout.wrapper.height = 30;
      ((TdApi.MessagePhoto)layout.msg.content).showCaptionAboveMedia = false;
      check(layout.checkCommonCaption(false), "Placement-only server updates rebuild layout");
      layout.drawContent(new MessageView(), new Canvas(), 0, 50, 200, new ComplexReceiver());
      check(layout.mosaicWrapper.y == 50 && layout.wrapper.y == 240, "Default caption follows media");
      check(layout.getContentHeight() == height + 6 && !layout.drawBubbleTimeOverContent(),
        "Only top captions lose 6dp of spacing; bottom captions keep their original layout");
      ((TdApi.MessagePhoto)layout.msg.content).showCaptionAboveMedia = true;
      layout.checkCommonCaption(false);
    }
    layout.footer = true;
    check(!layout.drawBubbleTimeOverContent() && layout.getFooterPaddingTop() == 8, "Footer follows media without caption spacing");
    layout.footer = false;
    TdApi.Message captionPhoto = photo(2, "Album caption", true);
    layout.combined = new ArrayList<>(Arrays.asList(photo(3, "", false), captionPhoto));
    layout.checkCommonCaption(false);
    check(layout.captionMessageId == 2 && layout.isCaptionAboveMedia(), "Album uses caption-bearing item's placement");
    layout.combined.add(photo(4, "Second caption", true)); layout.checkCommonCaption(false);
    check(layout.wrapper == null && layout.getContentHeight() == 180, "Multiple captions do not produce a common album caption");
    layout.combined.remove(2); layout.checkCommonCaption(false);
    layout.tdlib.editMessageCaption(1, 2, text("Album caption"), false);
    layout.checkCommonCaption(false);
    check(layout.hasEditedText && !layout.isCaptionAboveMedia(), "Apply pending caption position immediately");
    layout.tdlib.client.handlers.get(0).onResult(new TdApi.Error(400, "Rejected"));
    layout.checkCommonCaption(false);
    check(!layout.hasEditedText && layout.isCaptionAboveMedia(), "Failed caption edits restore placement");
    layout.tdlib.editMessageCaption(1, 2, text(""), false); layout.checkCommonCaption(false);
    check(layout.wrapper == null, "Removing album caption also removes its space");
    layout.tdlib.client.handlers.get(1).onResult(new TdApi.Error(400, "Rejected"));
    layout.tdlib.editMessageCaption(1, 2, text("Older"), false);
    layout.tdlib.editMessageCaption(1, 2, text("Newer"), true);
    layout.tdlib.client.handlers.get(2).onResult(new TdApi.Ok());
    check(layout.tdlib.getMessageShowCaptionAboveMedia(captionPhoto) &&
      layout.tdlib.getPendingMessageCaption(1, 2).text.equals("Newer"), "Older edit callback cannot discard newer edit");
    MessageEditMediaPending media = new MessageEditMediaPending(); media.above = false; media.caption = text("Replacement");
    layout.tdlib.media = media; layout.checkCommonCaption(false);
    check(!layout.isCaptionAboveMedia() && layout.caption.text.equals("Replacement"), "Replacement media supplies caption and placement together");
  }
  static void albumTests() {
    TdApi.MessageSendOptions options = new TdApi.MessageSendOptions();
    TdApi.InputMessageReplyTo reply = new TdApi.InputMessageReplyToMessage();
    TdApi.MessageTopic topic = new TdApi.MessageTopicForum(42);
    for (int count : new int[]{1, 2, 9, 10, 11, 20, 21, 31}) for (boolean above : new boolean[]{false, true}) {
      TdApi.InputMessageContent[] contents = new TdApi.InputMessageContent[count];
      for (int i = 0; i < count; i++) {
        TdApi.FormattedText caption = i == 0 ? text("Common caption") : text("");
        contents[i] = i % 2 == 0 ? new TdApi.InputMessagePhoto(null, caption, above, null, false) :
          new TdApi.InputMessageVideo(null, caption, above, null, false);
      }
      for (boolean group : new boolean[]{false, true}) {
        List<TdApi.Function<?>> requests = TD.toFunctions(1, topic, reply, options, contents, group);
        check(requests.size() == (group ? (count + 9) / 10 : count), "Split at ten, keeping a single remainder");
        int index = 0, captions = 0;
        for (int n = 0; n < requests.size(); n++) {
          TdApi.Function<?> request = requests.get(n); TdApi.InputMessageContent[] batch;
          if (request instanceof TdApi.SendMessageAlbum) {
            TdApi.SendMessageAlbum album = (TdApi.SendMessageAlbum)request; batch = album.inputMessageContents;
            check(batch.length >= 2 && batch.length <= 10, "Album contains two to ten items");
            check(album.topicId == topic && album.options == options && album.replyTo == (n == 0 ? reply : null), "Keep send metadata");
          } else {
            TdApi.SendMessage single = (TdApi.SendMessage)request; batch = new TdApi.InputMessageContent[]{single.inputMessageContent};
            check(single.topicId == topic && single.options == options && single.replyTo == (n == 0 ? reply : null), "Keep singleton metadata");
          }
          for (TdApi.InputMessageContent c : batch) {
            check(c == contents[index++], "Keep selection order and caption ownership across album boundaries");
            boolean actual; TdApi.FormattedText caption;
            if (c instanceof TdApi.InputMessagePhoto) {actual = ((TdApi.InputMessagePhoto)c).showCaptionAboveMedia; caption = ((TdApi.InputMessagePhoto)c).caption;}
            else {actual = ((TdApi.InputMessageVideo)c).showCaptionAboveMedia; caption = ((TdApi.InputMessageVideo)c).caption;}
            check(actual == above, "Every item shares placement, including captionless and later albums");
            if (!Td.isEmpty(caption)) captions++;
          }
        }
        check(index == count && captions == 1, "Common caption is sent exactly once on the first album");
      }
    }
    TdApi.InputMessageContent[] mixed = {new TdApi.InputMessagePhoto(null, text(""), true, null, false),
      new TdApi.InputMessageVideo(null, text(""), true, new TdApi.MessageSelfDestructTypeImmediately(), false),
      new TdApi.InputMessageAnimation(null, text("GIF"), true, false)};
    check(TD.toFunctions(1, null, null, options, mixed, true).size() == 3, "Self-destruct media remains separate");
  }
  static class Gravity {static final int TOP = 48, BOTTOM = 80, RIGHT = 5, VERTICAL_GRAVITY_MASK = 112;}
  static class FrameLayout {
    static class LayoutParams {
      int gravity = Gravity.BOTTOM | Gravity.RIGHT, topMargin, bottomMargin, leftMargin, height = -2;
    }
  }
  static class FrameLayoutFix extends FrameLayout {
    static LayoutParams newParams(int width, int height) {
      LayoutParams params = new LayoutParams(); params.height = height; return params;
    }
  }
  static class ViewGroup {static class LayoutParams {static final int WRAP_CONTENT = -2;}}
  static class Control {
    static final int VISIBLE = 0;
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(); float y;
    int height = 250, visibility = VISIBLE;
    Object parent;
    Object getLayoutParams() {return params;} void setLayoutParams(FrameLayout.LayoutParams p) {params = p;}
    void setTranslationY(float y) {this.y = y;} float getTranslationY() {return y;}
    int getMeasuredHeight() {return height;} int getVisibility() {return visibility;}
    Object getParent() {return parent;}
  }
  static class Size {static int getHeaderPortraitSize() {return 56;}}
  static class Views {
    static void setBottomMargin(Control c, int margin) {c.params.bottomMargin = margin;}
    static void setTopMargin(Control c, int margin) {if (c != null) c.params.topMargin = margin;}
    static void setPaddingTop(Control c, int padding) {}
  }
  static class ChatId {static boolean isSecret(long id) {return id == -1;}}
  static class Preview {
    static final int MODE_GALLERY = 1;
    int mode = MODE_GALLERY; boolean profile, allowed = true, above, local = true;
    long receiverChatId = 1;
    Preview sendDelegate = this, stack = this;
    Preview getArgumentsStrict() {return this;} Preview getCurrent() {return this;}
    Object getSourceGalleryFile() {return local ? this : null;}
    boolean inProfilePhotoEditMode() {return profile;}
    boolean allowShowCaptionAboveMedia() {return allowed;}
    boolean showCaptionAboveMedia() {return above;}
    void onShowCaptionAboveMediaStateChanged(boolean value) {above = value;}
    void updateCaptionPositionButton() {}
    static final int THUMBS_PADDING = 10, THUMBS_HEIGHT = 50;
    boolean needVideoMargin, inCaption;
    float videoFactor = 1, thumbsFactor, dismissFactor;
    boolean inForceEditMode() {return false;}
    int measureBottomWrapHeight() {return 56;}
    float getKeyboardOffset() {return 80;}
    Control captionWrapView = new Control(), captionEmojiButton = new Control(), captionDoneButton = new Control();
    Control bottomWrap = new Control(), keyboardFrameLayout = new Control(), headerView;
    Control thumbsRecyclerView, bottomSpace, receiverView, checkView, counterView, othersView;
    Object contentView = new Object();
    int topOffset;
    Preview() {
      captionWrapView.parent = bottomWrap;
      keyboardFrameLayout.parent = bottomWrap;
    }
    Preview inlineResultsView; void updatePosition(boolean animate) {}
    FrameLayout.LayoutParams createReceiverLayout() {
      RECEIVER_LAYOUT
      return fp;
    }
    PREVIEW_METHODS
  }
  static void previewTests() {
    Preview p = new Preview(); p.bottomWrap.y = -80;
    p.toggleCaptionPosition();
    check(p.above && p.captionWrapView.params.topMargin == 8 &&
      (p.captionWrapView.params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP,
      "Caption editor moves above media");
    check(p.captionWrapView.y == 80 && p.captionDoneButton.y == 80 && p.captionEmojiButton.y == 80,
      "Top caption and its controls compensate keyboard animation together");
    check(p.captionWrapView.params.bottomMargin == 0, "Top caption is independent of video controls");
    p.toggleCaptionPosition();
    check(!p.above && p.captionWrapView.params.topMargin == 0 && p.captionWrapView.y == -250 &&
      (p.captionWrapView.params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM,
      "Move down restores bottom editor above keyboard");
    check(p.needVideoMargin && p.captionWrapView.params.bottomMargin == 56, "Restore video control spacing when moving down");
    p.receiverChatId = -1; p.toggleCaptionPosition(); check(!p.above, "Secret chats cannot enable placement");
    p.receiverChatId = 1; p.profile = true; p.toggleCaptionPosition(); check(!p.above, "Profile photo editor has no caption placement");
    p.profile = false; p.local = false; p.toggleCaptionPosition(); check(!p.above, "Inline search results do not support a local caption override");
    p.local = true; p.allowed = false; p.toggleCaptionPosition(); check(!p.above, "Respect delegate capability");
    p.allowed = true; p.above = true; p.captionDoneButton = null; p.captionWrapView = null;
    p.checkBottomWrapY();
    check(p.bottomWrap.y == -80, "Reopening a top-caption selection can lay out before caption controls exist");
  }
  static Control headerControl(Preview p, int top, int height) {
    Control c = new Control(); c.parent = p.contentView;
    c.params.topMargin = top; c.params.height = height;
    c.height = 0; // Layout must already be safe before the first measurement.
    return c;
  }
  static void pickerHeaderTests() {
    Preview p = new Preview();
    p.checkView = headerControl(p, 11, 60);
    p.counterView = headerControl(p, 26, 30);
    p.receiverView = headerControl(p, 4, 24);
    p.bottomWrap.y = -80;
    p.toggleCaptionPosition();
    check(p.captionWrapView.params.topMargin == 79, "Caption starts below the full selection-button touch target");
    for (int inset : new int[]{24, 80, 0}) {
      p.setTopOffset(inset);
      for (Control c : new Control[]{p.checkView, p.counterView, p.receiverView}) {
        check(p.captionWrapView.params.topMargin >= c.params.topMargin + c.params.height + 8,
          "Caption clears recipient, counter, and selection controls after system inset changes");
      }
      check(p.captionDoneButton.params.topMargin == p.captionWrapView.params.topMargin &&
        p.captionEmojiButton.params.topMargin == p.captionWrapView.params.topMargin,
        "Caption text, Done, and emoji button share the safe top row");
      check(p.captionWrapView.y + p.bottomWrap.y == 0, "Header clearance is independent of keyboard translation");
    }
    p.receiverView.height = 120; p.checkCaptionButtonsY();
    check(p.captionWrapView.params.topMargin == 132, "Measured large-font recipient rows also reserve their height");
    p.headerView = headerControl(p, 0, 160); p.checkCaptionButtonsY();
    check(p.captionWrapView.params.topMargin == 168, "Account for an attached header in media edit mode");
    p.headerView.parent = null;
    p.setTopOffset(80);
    p.receiverView.params = p.createReceiverLayout(); p.receiverView.height = 17;
    p.checkCaptionButtonsY();
    check(p.captionWrapView.params.topMargin == p.checkView.params.topMargin + p.checkView.params.height + 8,
      "Large status-bar insets must not leave an extra status-bar-sized gap below the recipient text");
    p.headerView.parent = null; p.checkView.parent = null; p.counterView.parent = null;
    p.receiverView.visibility = View.GONE; p.setTopOffset(0); p.checkCaptionButtonsY();
    check(p.captionWrapView.params.topMargin == 8, "Detached and gone header controls do not reserve space");
    p.toggleCaptionPosition();
    check(p.captionWrapView.params.topMargin == 0 &&
      (p.captionWrapView.params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM,
      "Moving caption back down removes header clearance");
  }
  public static void main(String[] args) {
    switch (args[0]) {
      case "albums": albumTests(); break;
      case "layout": layoutTests(); break;
      case "preview": previewTests(); break;
      case "picker_header": pickerHeaderTests(); break;
    }
  }
}
'''


if __name__ == "__main__":
    unittest.main()
