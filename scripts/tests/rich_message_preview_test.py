"""Exercise rich-message preview traversal and media summaries without an APK."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
DATA = "app/src/main/java/org/thunderdog/challegram/data/"
PACKAGE = "org/thunderdog/challegram/"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class RichMessagePreviewTest(unittest.TestCase):
  def test_captions_nested_media_counts_and_copy_text(self):
    source = read(DATA + "ContentPreview.java")
    builder = java_method(source, "private static ContentPreview getRichMessagePreview (")
    preview = java_method(source, "private static ContentPreview getContentPreview (")
    self.assertIn("RichMessageUtils.toPreviewText(", preview)
    self.assertIn("return getRichMessagePreview(", preview)
    self.assertIn("RichMessageUtils.toSearchableText(", java_method(
      source, "private static TdApi.FormattedText getMessageTextForFilter("))
    names = sorted(set(re.findall(r"R\.string\.(\w+)", builder)))
    strings = {item.attrib.get("name") for item in ET.parse(
      ROOT / "app/src/main/res/values/strings.xml").getroot()}
    for name in names:
      self.assertTrue(name in strings or name + "_other" in strings, name)
    resources = "static class R {static class string {" + "".join(
      f"static final int {name} = {index};" for index, name in enumerate(names, 1)
    ) + "}}"
    emojis = "".join(f'static final Emoji {name} = new Emoji();' for name in sorted(
      set(re.findall(r"\bEMOJI_\w+", builder))))
    harness = r'''
package org.thunderdog.challegram.data;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.core.Lang;
public class ContentPreview {
  @interface Nullable {}
  static class Emoji {}
  RESOURCES
  EMOJIS
  final Emoji emoji;
  final int placeholderText;
  final TdApi.FormattedText formattedText;
  final boolean isTranslatable;
  ContentPreview(Emoji emoji, int placeholder, TdApi.FormattedText text, boolean translatable) {
    this.emoji = emoji; placeholderText = placeholder;
    formattedText = text; isTranslatable = translatable;
  }
  ContentPreview(Emoji emoji, int placeholder, String text, boolean translatable) {
    this(emoji, placeholder, new TdApi.FormattedText(text, null), translatable);
  }
  static class Td {
    static boolean isEmpty(TdApi.FormattedText text) {
      return text == null || text.text == null || text.text.isEmpty();
    }
  }
  BUILDER
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  static TdApi.RichMessage rich(TdApi.PageBlock... blocks) {
    return new TdApi.RichMessage(blocks, false, true);
  }
  static TdApi.PageBlockPhoto photo(String caption) {
    return new TdApi.PageBlockPhoto(null, caption == null ? null :
      new TdApi.PageBlockCaption(new TdApi.RichTextPlain(caption), null), "", false);
  }
  static ContentPreview preview(TdApi.RichMessage message) {
    return getRichMessagePreview(message, RichMessageUtils.toPreviewText(message));
  }
  static void count(ContentPreview preview, Emoji emoji, int resource, int count) {
    check(preview.emoji == emoji, "Wrong media icon");
    check(preview.formattedText.text.equals(count + ":" + resource), "Wrong localized count");
    check(preview.isTranslatable, "Media count must be translatable");
  }
  public static void main(String[] args) {
    TdApi.PageBlock[] photos = {photo(null), photo(null), photo(null)};
    TdApi.RichMessage collage = rich(new TdApi.PageBlockCollage(photos,
      new TdApi.PageBlockCaption(new TdApi.RichTextBold(
        new TdApi.RichTextPlain("💊 Verdict: caption")), null)));
    ContentPreview result = preview(collage);
    check(result.formattedText.text.equals("💊 Verdict: caption"), "Show the collage caption");
    check(result.emoji == EMOJI_ALBUM_PHOTOS && !result.isTranslatable,
      "Captioned collage keeps album icon and user text");
    check(RichMessageUtils.toPlainText(collage).split("\\[Photo\\]", -1).length == 4,
      "Copy text must retain every media label");
    check(RichMessageUtils.toHtml(collage).contains("data-media=\"photo\""),
      "HTML export must retain media");

    TdApi.RichMessage bare = rich(new TdApi.PageBlockCollage(photos, null));
    count(preview(bare), EMOJI_ALBUM_PHOTOS, R.string.xPhotos, 3);
    TdApi.RichMessage body = rich(new TdApi.PageBlockCollage(photos, null),
      new TdApi.PageBlockParagraph(new TdApi.RichTexts(new TdApi.RichText[] {
        new TdApi.RichTextPlain("Body "),
        new TdApi.RichTextUrl(new TdApi.RichTextPlain("link"), "https://example.org", false)
      })));
    check(preview(body).formattedText.text.equals("Body link"), "Show text following media");
    check(preview(rich(photo("[Photo] is literal text"))).formattedText.text.equals(
      "[Photo] is literal text"), "Keep literal placeholder-like text in captions");

    TdApi.PageBlockVideo video = new TdApi.PageBlockVideo();
    count(preview(rich(new TdApi.PageBlockSlideshow(new TdApi.PageBlock[] {video, video}, null))),
      EMOJI_ALBUM_VIDEOS, R.string.xVideos, 2);
    count(preview(rich(new TdApi.PageBlockSlideshow(new TdApi.PageBlock[] {
      photo(null), video, photo(null)}, null))), EMOJI_ALBUM_MEDIA, R.string.xMedia, 3);

    TdApi.PageBlockList list = new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
      null, new TdApi.PageBlockListItem("1.", new TdApi.PageBlock[] {
        new TdApi.PageBlockCover(new TdApi.PageBlockDetails(new TdApi.RichTextPlain(""),
          new TdApi.PageBlock[] {new TdApi.PageBlockBlockQuote(new TdApi.PageBlock[] {
            new TdApi.PageBlockDivider(), new TdApi.PageBlockCollage(photos, null)
          }, new TdApi.RichTextPlain(""))}, false))
      }, false, false, 1, "1")
    });
    count(preview(rich(list)), EMOJI_ALBUM_PHOTOS, R.string.xPhotos, 3);
    TdApi.RichMessage childCaption = rich(new TdApi.PageBlockCollage(new TdApi.PageBlock[] {
      photo(null), photo("Child caption"), null}, null));
    check(preview(childCaption).formattedText.text.equals("Child caption"),
      "Keep nested individual captions and tolerate missing blocks");

    TdApi.PageBlock[] singles = {photo(null), video, new TdApi.PageBlockAnimation(),
      new TdApi.PageBlockAudio(), new TdApi.PageBlockVoiceNote(), new TdApi.PageBlockMap()};
    Emoji[] icons = {EMOJI_PHOTO, EMOJI_VIDEO, EMOJI_GIF, EMOJI_AUDIO, EMOJI_VOICE, EMOJI_LOCATION};
    int[] labels = {R.string.ChatContentPhoto, R.string.ChatContentVideo,
      R.string.ChatContentAnimation, R.string.Audio, R.string.ChatContentVoice, R.string.Location};
    for (int index = 0; index < singles.length; index++) {
      result = preview(rich(singles[index]));
      check(result.emoji == icons[index] && result.placeholderText == labels[index] &&
        Td.isEmpty(result.formattedText), "Single media uses its localized fallback");
    }
    count(preview(rich(singles[3], singles[3])), EMOJI_ALBUM_AUDIO, R.string.xAudios, 2);
    TdApi.PageBlockAudio track = new TdApi.PageBlockAudio();
    track.audio = new TdApi.Audio();
    track.audio.title = "Track title";
    check(preview(rich(track)).formattedText.text.equals("Track title"),
      "Keep media titles supplied by the sender");
    TdApi.FormattedText pending = new TdApi.FormattedText("Edited caption", null);
    check(getRichMessagePreview(collage, pending).formattedText == pending,
      "Keep pending caption edits");
    count(getRichMessagePreview(collage, null), EMOJI_ALBUM_PHOTOS, R.string.xPhotos, 3);
    check(preview(null).placeholderText == R.string.YouHaveNewMessage, "Handle absent rich content");
    check(preview(rich(null, new TdApi.PageBlockAnchor("anchor"))).emoji == null,
      "Empty content has no media icon");
    result = preview(rich(new TdApi.PageBlockParagraph(new TdApi.RichTextCustomEmoji(42, "🙂"))));
    check(result.emoji == null && result.formattedText.text.equals("🙂"),
      "Inline emoji stays in text and is not an attachment");
    TdApi.RichMessage inline = rich(photo(null), new TdApi.PageBlockParagraph(
      new TdApi.RichTextIcon(null, 16, 16)));
    check(preview(inline).emoji == EMOJI_PHOTO, "Inline icons must not inflate the album count");
  }
}
'''
    harness = harness.replace("RESOURCES", resources).replace("EMOJIS", emojis)
    harness = harness.replace("BUILDER", builder)
    stubs = {
      "androidx/annotation/Nullable.java": "public @interface Nullable {}",
      "androidx/annotation/NonNull.java": "public @interface NonNull {}",
      "androidx/annotation/IntDef.java": "public @interface IntDef {long[] value() default {};}",
      "androidx/annotation/RequiresApi.java": "public @interface RequiresApi {int value();}",
      "android/os/Build.java": """public class Build {
        public static class VERSION {public static final int SDK_INT = 0;}
        public static class VERSION_CODES {public static final int N = 24, JELLY_BEAN_MR2 = 18;}
      }""",
      "android/text/format/DateUtils.java": """public class DateUtils {
        public static final int FORMAT_ABBREV_RELATIVE = 0;
        public static CharSequence getRelativeTimeSpanString(long a, long b, long c, int d) {
          return "";
        }
      }""",
      "android/text/format/DateFormat.java": """public class DateFormat {
        public static String getBestDateTimePattern(java.util.Locale locale, String skeleton) {
          return skeleton;
        }
      }""",
      "android/icu/text/RelativeDateTimeFormatter.java": """public class RelativeDateTimeFormatter {
        public enum RelativeUnit {SECONDS, MINUTES, HOURS, DAYS, MONTHS, YEARS}
        public enum Direction {NEXT, LAST}
        public static RelativeDateTimeFormatter getInstance(java.util.Locale locale) {return null;}
        public String format(long value, Direction direction, RelativeUnit unit) {return "";}
      }""",
      PACKAGE + "core/Lang.java": """public class Lang {
        public static java.util.Locale dateFormatLocale() {return java.util.Locale.US;}
        public static String plural(int resource, int count) {return count + ":" + resource;}
      }""",
      PACKAGE + "tool/UI.java": "public class UI {public static boolean needAmPm() {return false;}}",
    }
    with tempfile.TemporaryDirectory(prefix="rich-message-preview-") as directory:
      work = Path(directory)
      sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java",
                 ROOT / (DATA + "RichMessageUtils.java"),
                 ROOT / (DATA + "RichMessageFlattener.java")]
      for name, code in stubs.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        package = name.rsplit("/", 1)[0].replace("/", ".")
        path.write_text(f"package {package};\n{code}", encoding="utf-8")
        sources.append(path)
      path = work / "ContentPreview.java"
      path.write_text(harness, encoding="utf-8")
      sources.append(path)
      compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory,
                                 *map(str, sources)], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
      result = subprocess.run(["java", "-cp", directory,
                               "org.thunderdog.challegram.data.ContentPreview"],
                              capture_output=True, text=True)
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
  unittest.main()
