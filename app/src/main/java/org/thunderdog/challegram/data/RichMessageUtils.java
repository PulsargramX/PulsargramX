/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.data;

import android.os.Build;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.UI;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One defensive traversal implementation for all non-visual rich-message uses.
 *
 * <p>The TDLib tree can be partial and may contain null/malformed values. Public
 * methods in this class deliberately never reject the whole message because of
 * one bad block.</p>
 */
public final class RichMessageUtils {
  private RichMessageUtils () { }

  public static final class Index {
    public final Map<String, String> anchors;
    public final Map<String, String> references;

    private Index (Map<String, String> anchors, Map<String, String> references) {
      this.anchors = Collections.unmodifiableMap(anchors);
      this.references = Collections.unmodifiableMap(references);
    }
  }

  public static final class MediaItem {
    public static final int PHOTO = 1;
    public static final int VIDEO = 2;
    public static final int ANIMATION = 3;
    public static final int AUDIO = 4;
    public static final int VOICE_NOTE = 5;
    public static final int MAP = 6;
    public static final int INLINE_ICON = 7;

    public final String path;
    public final int type;
    public final TdApi.Object value;
    public final @Nullable TdApi.File file;

    private MediaItem (String path, int type, TdApi.Object value, @Nullable TdApi.File file) {
      this.path = path;
      this.type = type;
      this.value = value;
      this.file = file;
    }
  }

  public static String toPlainText (@Nullable TdApi.RichMessage message) {
    if (message == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    appendBlocksPlain(out, message.blocks, "", 0);
    trimTrailingWhitespace(out);
    return out.toString();
  }

  public static TdApi.FormattedText toSearchableText (@Nullable TdApi.RichMessage message) {
    return new TdApi.FormattedText(toPlainText(message), new TdApi.TextEntity[0]);
  }

  public static String deterministicSource (@Nullable TdApi.RichMessage message) {
    if (message == null) {
      return "rich:null";
    }
    // HTML includes structure which plain text intentionally omits.
    String source = (message.isRtl ? "rtl:" : "ltr:") + (message.isFull ? "full:" : "part:")
      + toHtml(message);
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < source.length(); index++) {
      hash ^= source.charAt(index);
      hash *= 0x100000001b3L;
    }
    return Long.toHexString(hash);
  }

  public static @Nullable String firstMeaningfulText (@Nullable TdApi.RichMessage message) {
    if (message == null) {
      return null;
    }
    String text = toPlainText(message).trim();
    return text.isEmpty() ? null : text;
  }

  public static String toHtml (@Nullable TdApi.RichMessage message) {
    return toHtml(message, null);
  }

  public static String toHtml (
    @Nullable TdApi.RichMessage message,
    @Nullable Map<String, RichMessageFlattener.State> localState
  ) {
    if (message == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    out.append("<div dir=\"").append(message.isRtl ? "rtl" : "ltr")
      .append("\" class=\"telegram-rich-message\">");
    appendBlocksHtml(out, message.blocks, "", 0, localState);
    out.append("</div>");
    return out.toString();
  }

  public static Index indexAnchorsAndReferences (@Nullable TdApi.RichMessage message) {
    LinkedHashMap<String, String> anchors = new LinkedHashMap<>();
    LinkedHashMap<String, String> references = new LinkedHashMap<>();
    if (message != null) {
      indexBlocks(message.blocks, "", anchors, references);
    }
    return new Index(anchors, references);
  }

  public static List<MediaItem> media (@Nullable TdApi.RichMessage message) {
    if (message == null) {
      return Collections.emptyList();
    }
    ArrayList<MediaItem> out = new ArrayList<>();
    collectMediaBlocks(message.blocks, "", out);
    return Collections.unmodifiableList(out);
  }

  public static List<TdApi.File> files (@Nullable TdApi.RichMessage message) {
    List<MediaItem> media = media(message);
    ArrayList<TdApi.File> out = new ArrayList<>(media.size());
    for (MediaItem item : media) {
      if (item.file != null) {
        out.add(item.file);
      }
    }
    return Collections.unmodifiableList(out);
  }

  public static @Nullable MediaItem firstVisualMedia (@Nullable TdApi.RichMessage message) {
    for (MediaItem item : media(message)) {
      if (item.type == MediaItem.PHOTO || item.type == MediaItem.VIDEO ||
          item.type == MediaItem.ANIMATION || item.type == MediaItem.MAP) {
        return item;
      }
    }
    return null;
  }

  public static String richTextToPlain (@Nullable TdApi.RichText text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    appendRichTextPlain(out, text);
    return out.toString();
  }

  public static String richTextToHtml (@Nullable TdApi.RichText text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    appendRichTextHtml(out, text);
    return out.toString();
  }

  private static void appendBlocksPlain (StringBuilder out, @Nullable TdApi.PageBlock[] blocks,
                                         String parentPath, int quoteDepth) {
    if (blocks == null) {
      return;
    }
    for (int index = 0; index < blocks.length; index++) {
      TdApi.PageBlock block = blocks[index];
      if (block == null) {
        continue;
      }
      String path = childPath(parentPath, index);
      int before = out.length();
      try {
        switch (block.getConstructor()) {
          case TdApi.PageBlockTitle.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockTitle) block).title);
            break;
          case TdApi.PageBlockSubtitle.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockSubtitle) block).subtitle);
            break;
          case TdApi.PageBlockAuthorDate.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockAuthorDate) block).author);
            break;
          case TdApi.PageBlockHeader.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockHeader) block).header);
            break;
          case TdApi.PageBlockSubheader.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockSubheader) block).subheader);
            break;
          case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockSectionHeading) block).text);
            break;
          case TdApi.PageBlockKicker.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockKicker) block).kicker);
            break;
          case TdApi.PageBlockParagraph.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockParagraph) block).text);
            break;
          case TdApi.PageBlockPreformatted.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockPreformatted) block).text);
            break;
          case TdApi.PageBlockFooter.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockFooter) block).footer);
            break;
          case TdApi.PageBlockThinking.CONSTRUCTOR:
            appendRichTextPlain(out, ((TdApi.PageBlockThinking) block).text);
            break;
          case TdApi.PageBlockDivider.CONSTRUCTOR:
            out.append("—");
            break;
          case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR:
            out.append(nullToEmpty(((TdApi.PageBlockMathematicalExpression) block).expression));
            break;
          case TdApi.PageBlockAnchor.CONSTRUCTOR:
            break;
          case TdApi.PageBlockList.CONSTRUCTOR:
            appendListPlain(out, (TdApi.PageBlockList) block, path, quoteDepth);
            break;
          case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
            TdApi.PageBlockBlockQuote quote = (TdApi.PageBlockBlockQuote) block;
            StringBuilder quoted = new StringBuilder();
            appendBlocksPlain(quoted, quote.blocks, path, quoteDepth + 1);
            appendQuoted(out, quoted, quoteDepth + 1);
            if (quote.credit != null) {
              out.append("\n— ");
              appendRichTextPlain(out, quote.credit);
            }
            break;
          }
          case TdApi.PageBlockPullQuote.CONSTRUCTOR: {
            TdApi.PageBlockPullQuote quote = (TdApi.PageBlockPullQuote) block;
            appendRichTextPlain(out, quote.text);
            if (quote.credit != null) {
              out.append("\n— ");
              appendRichTextPlain(out, quote.credit);
            }
            break;
          }
          case TdApi.PageBlockAnimation.CONSTRUCTOR: {
            TdApi.PageBlockAnimation media = (TdApi.PageBlockAnimation) block;
            appendMediaPlain(out, "Animation", media.caption);
            break;
          }
          case TdApi.PageBlockAudio.CONSTRUCTOR: {
            TdApi.PageBlockAudio media = (TdApi.PageBlockAudio) block;
            appendMediaPlain(out, media.audio != null && media.audio.title != null &&
              !media.audio.title.isEmpty() ? media.audio.title : "Audio", media.caption);
            break;
          }
          case TdApi.PageBlockPhoto.CONSTRUCTOR:
            appendMediaPlain(out, "Photo", ((TdApi.PageBlockPhoto) block).caption);
            break;
          case TdApi.PageBlockVideo.CONSTRUCTOR:
            appendMediaPlain(out, "Video", ((TdApi.PageBlockVideo) block).caption);
            break;
          case TdApi.PageBlockVoiceNote.CONSTRUCTOR:
            appendMediaPlain(out, "Voice message", ((TdApi.PageBlockVoiceNote) block).caption);
            break;
          case TdApi.PageBlockMap.CONSTRUCTOR:
            appendMediaPlain(out, "Map", ((TdApi.PageBlockMap) block).caption);
            break;
          case TdApi.PageBlockCover.CONSTRUCTOR:
            appendBlocksPlain(out, new TdApi.PageBlock[] {
              ((TdApi.PageBlockCover) block).cover
            }, path, quoteDepth);
            break;
          case TdApi.PageBlockCollage.CONSTRUCTOR: {
            TdApi.PageBlockCollage collage = (TdApi.PageBlockCollage) block;
            appendBlocksPlain(out, collage.blocks, path, quoteDepth);
            appendCaptionPlain(out, collage.caption);
            break;
          }
          case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
            TdApi.PageBlockSlideshow slideshow = (TdApi.PageBlockSlideshow) block;
            appendBlocksPlain(out, slideshow.blocks, path, quoteDepth);
            appendCaptionPlain(out, slideshow.caption);
            break;
          }
          case TdApi.PageBlockTable.CONSTRUCTOR:
            appendTablePlain(out, (TdApi.PageBlockTable) block);
            break;
          case TdApi.PageBlockDetails.CONSTRUCTOR: {
            TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
            appendRichTextPlain(out, details.header);
            out.append(details.isOpen ? "\n" : "\n");
            appendBlocksPlain(out, details.blocks, path, quoteDepth);
            break;
          }
          default:
            appendUnknownPlain(out, block);
            break;
        }
      } catch (Throwable ignored) {
        appendUnknownPlain(out, block);
      }
      if (out.length() > before) {
        appendBlockSeparator(out);
      }
    }
  }

  private static void appendListPlain (StringBuilder out, TdApi.PageBlockList list,
                                       String path, int quoteDepth) {
    if (list.items == null) {
      return;
    }
    for (int index = 0; index < list.items.length; index++) {
      TdApi.PageBlockListItem item = list.items[index];
      if (item == null) {
        continue;
      }
      if (index > 0 && !endsWithNewline(out)) {
        out.append('\n');
      }
      if (item.hasCheckbox) {
        out.append(item.isChecked ? "☑ " : "☐ ");
      }
      if (item.label != null && !item.label.isEmpty()) {
        out.append(item.label).append(' ');
      } else if (item.value != 0 || item.type != null && !item.type.isEmpty()) {
        out.append(formatListValue(item.value, item.type)).append(". ");
      } else if (!item.hasCheckbox) {
        out.append("• ");
      }
      StringBuilder child = new StringBuilder();
      appendBlocksPlain(child, item.blocks, childPath(path, index), quoteDepth);
      trimTrailingWhitespace(child);
      String childText = child.toString().replace("\n", "\n  ");
      out.append(childText);
    }
  }

  private static void appendTablePlain (StringBuilder out, TdApi.PageBlockTable table) {
    if (table.caption != null) {
      appendRichTextPlain(out, table.caption);
      out.append('\n');
    }
    if (table.cells == null) {
      return;
    }
    for (int row = 0; row < table.cells.length; row++) {
      if (row > 0) {
        out.append('\n');
      }
      TdApi.PageBlockTableCell[] cells = table.cells[row];
      if (cells == null) {
        continue;
      }
      for (int column = 0; column < cells.length; column++) {
        if (column > 0) {
          out.append('\t');
        }
        if (cells[column] != null) {
          appendRichTextPlain(out, cells[column].text);
        }
      }
    }
  }

  private static void appendMediaPlain (StringBuilder out, String label,
                                        @Nullable TdApi.PageBlockCaption caption) {
    out.append('[').append(label).append(']');
    appendCaptionPlain(out, caption);
  }

  private static void appendCaptionPlain (StringBuilder out, @Nullable TdApi.PageBlockCaption caption) {
    if (caption == null) {
      return;
    }
    String text = richTextToPlain(caption.text);
    String credit = richTextToPlain(caption.credit);
    if (!text.isEmpty()) {
      out.append('\n').append(text);
    }
    if (!credit.isEmpty()) {
      out.append("\n— ").append(credit);
    }
  }

  private static void appendBlocksHtml (StringBuilder out, @Nullable TdApi.PageBlock[] blocks,
                                        String parentPath, int quoteDepth,
                                        @Nullable Map<String, RichMessageFlattener.State> localState) {
    if (blocks == null) {
      return;
    }
    for (int index = 0; index < blocks.length; index++) {
      TdApi.PageBlock block = blocks[index];
      if (block == null) {
        continue;
      }
      String path = childPath(parentPath, index);
      try {
        switch (block.getConstructor()) {
          case TdApi.PageBlockTitle.CONSTRUCTOR:
            tag(out, "h1", ((TdApi.PageBlockTitle) block).title);
            break;
          case TdApi.PageBlockSubtitle.CONSTRUCTOR:
            tag(out, "h2", ((TdApi.PageBlockSubtitle) block).subtitle);
            break;
          case TdApi.PageBlockHeader.CONSTRUCTOR:
            tag(out, "h3", ((TdApi.PageBlockHeader) block).header);
            break;
          case TdApi.PageBlockSubheader.CONSTRUCTOR:
            tag(out, "h4", ((TdApi.PageBlockSubheader) block).subheader);
            break;
          case TdApi.PageBlockSectionHeading.CONSTRUCTOR: {
            TdApi.PageBlockSectionHeading heading = (TdApi.PageBlockSectionHeading) block;
            int level = Math.max(1, Math.min(6, heading.size));
            tag(out, "h" + level, heading.text);
            break;
          }
          case TdApi.PageBlockKicker.CONSTRUCTOR:
            tag(out, "p class=\"kicker\"", ((TdApi.PageBlockKicker) block).kicker);
            break;
          case TdApi.PageBlockAuthorDate.CONSTRUCTOR:
            tag(out, "p class=\"author\"", ((TdApi.PageBlockAuthorDate) block).author);
            break;
          case TdApi.PageBlockParagraph.CONSTRUCTOR:
            tag(out, "p", ((TdApi.PageBlockParagraph) block).text);
            break;
          case TdApi.PageBlockPreformatted.CONSTRUCTOR: {
            TdApi.PageBlockPreformatted pre = (TdApi.PageBlockPreformatted) block;
            out.append("<pre");
            if (pre.language != null && !pre.language.isEmpty()) {
              out.append(" data-language=\"").append(escapeAttribute(pre.language)).append('"');
            }
            out.append('>');
            appendRichTextHtml(out, pre.text);
            out.append("</pre>");
            break;
          }
          case TdApi.PageBlockFooter.CONSTRUCTOR:
            tag(out, "footer", ((TdApi.PageBlockFooter) block).footer);
            break;
          case TdApi.PageBlockThinking.CONSTRUCTOR:
            tag(out, "p aria-live=\"polite\" class=\"thinking\"", ((TdApi.PageBlockThinking) block).text);
            break;
          case TdApi.PageBlockDivider.CONSTRUCTOR:
            out.append("<hr>");
            break;
          case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR: {
            String expression = nullToEmpty(((TdApi.PageBlockMathematicalExpression) block).expression);
            out.append("<div class=\"math\" data-latex=\"").append(escapeAttribute(expression))
              .append("\">").append(escapeHtml(expression)).append("</div>");
            break;
          }
          case TdApi.PageBlockAnchor.CONSTRUCTOR: {
            String name = nullToEmpty(((TdApi.PageBlockAnchor) block).name);
            out.append("<a id=\"").append(escapeAttribute(name)).append("\"></a>");
            break;
          }
          case TdApi.PageBlockList.CONSTRUCTOR:
            appendListHtml(out, (TdApi.PageBlockList) block, path, quoteDepth, localState);
            break;
          case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
            TdApi.PageBlockBlockQuote quote = (TdApi.PageBlockBlockQuote) block;
            out.append("<blockquote>");
            appendBlocksHtml(out, quote.blocks, path, quoteDepth + 1, localState);
            if (quote.credit != null) {
              tag(out, "cite", quote.credit);
            }
            out.append("</blockquote>");
            break;
          }
          case TdApi.PageBlockPullQuote.CONSTRUCTOR: {
            TdApi.PageBlockPullQuote quote = (TdApi.PageBlockPullQuote) block;
            out.append("<blockquote class=\"pull-quote\"><p>");
            appendRichTextHtml(out, quote.text);
            out.append("</p>");
            if (quote.credit != null) {
              tag(out, "cite", quote.credit);
            }
            out.append("</blockquote>");
            break;
          }
          case TdApi.PageBlockPhoto.CONSTRUCTOR:
            appendMediaHtml(out, "photo", ((TdApi.PageBlockPhoto) block).caption);
            break;
          case TdApi.PageBlockVideo.CONSTRUCTOR:
            appendMediaHtml(out, "video", ((TdApi.PageBlockVideo) block).caption);
            break;
          case TdApi.PageBlockAnimation.CONSTRUCTOR:
            appendMediaHtml(out, "animation", ((TdApi.PageBlockAnimation) block).caption);
            break;
          case TdApi.PageBlockAudio.CONSTRUCTOR:
            appendMediaHtml(out, "audio", ((TdApi.PageBlockAudio) block).caption);
            break;
          case TdApi.PageBlockVoiceNote.CONSTRUCTOR:
            appendMediaHtml(out, "voice-note", ((TdApi.PageBlockVoiceNote) block).caption);
            break;
          case TdApi.PageBlockMap.CONSTRUCTOR:
            appendMediaHtml(out, "map", ((TdApi.PageBlockMap) block).caption);
            break;
          case TdApi.PageBlockCover.CONSTRUCTOR:
            appendBlocksHtml(out, new TdApi.PageBlock[] {
              ((TdApi.PageBlockCover) block).cover
            }, path, quoteDepth, localState);
            break;
          case TdApi.PageBlockCollage.CONSTRUCTOR: {
            TdApi.PageBlockCollage collage = (TdApi.PageBlockCollage) block;
            out.append("<figure class=\"collage\">");
            appendBlocksHtml(out, collage.blocks, path, quoteDepth, localState);
            appendCaptionHtml(out, collage.caption);
            out.append("</figure>");
            break;
          }
          case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
            TdApi.PageBlockSlideshow slideshow = (TdApi.PageBlockSlideshow) block;
            out.append("<figure class=\"slideshow\">");
            appendBlocksHtml(out, slideshow.blocks, path, quoteDepth, localState);
            appendCaptionHtml(out, slideshow.caption);
            out.append("</figure>");
            break;
          }
          case TdApi.PageBlockTable.CONSTRUCTOR:
            appendTableHtml(out, (TdApi.PageBlockTable) block);
            break;
          case TdApi.PageBlockDetails.CONSTRUCTOR: {
            TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
            out.append("<details");
            RichMessageFlattener.State state =
              localState != null ? localState.get(path) : null;
            if (state != null ? state.detailsOpen : details.isOpen) {
              out.append(" open");
            }
            out.append("><summary>");
            appendRichTextHtml(out, details.header);
            out.append("</summary>");
            appendBlocksHtml(out, details.blocks, path, quoteDepth, localState);
            out.append("</details>");
            break;
          }
          default: {
            StringBuilder fallback = new StringBuilder();
            appendUnknownPlain(fallback, block);
            if (fallback.length() > 0) {
              out.append("<p class=\"unsupported\">").append(escapeHtml(fallback.toString())).append("</p>");
            }
            break;
          }
        }
      } catch (Throwable ignored) {
        StringBuilder fallback = new StringBuilder();
        appendUnknownPlain(fallback, block);
        if (fallback.length() > 0) {
          out.append("<p class=\"unsupported\">").append(escapeHtml(fallback.toString())).append("</p>");
        }
      }
    }
  }

  private static void appendListHtml (StringBuilder out, TdApi.PageBlockList list,
                                      String path, int quoteDepth,
                                      @Nullable Map<String, RichMessageFlattener.State> localState) {
    boolean ordered = false;
    if (list.items != null) {
      for (TdApi.PageBlockListItem item : list.items) {
        if (item != null && (item.value != 0 || item.type != null && !item.type.isEmpty())) {
          ordered = true;
          break;
        }
      }
    }
    out.append(ordered ? "<ol>" : "<ul>");
    if (list.items != null) {
      for (int index = 0; index < list.items.length; index++) {
        TdApi.PageBlockListItem item = list.items[index];
        if (item == null) {
          continue;
        }
        out.append("<li");
        if (item.value != 0) {
          out.append(" value=\"").append(item.value).append('"');
        }
        if (item.hasCheckbox) {
          out.append(" data-task=\"true\" aria-checked=\"").append(item.isChecked).append('"');
        }
        out.append('>');
        if (item.hasCheckbox) {
          out.append(item.isChecked ? "☑ " : "☐ ");
        }
        if (item.label != null && !item.label.isEmpty()) {
          out.append("<span class=\"list-label\">").append(escapeHtml(item.label)).append("</span> ");
        }
        appendBlocksHtml(out, item.blocks, childPath(path, index), quoteDepth, localState);
        out.append("</li>");
      }
    }
    out.append(ordered ? "</ol>" : "</ul>");
  }

  private static void appendTableHtml (StringBuilder out, TdApi.PageBlockTable table) {
    out.append("<table");
    if (table.isBordered) {
      out.append(" data-bordered=\"true\"");
    }
    if (table.isStriped) {
      out.append(" data-striped=\"true\"");
    }
    out.append('>');
    if (table.caption != null) {
      out.append("<caption>");
      appendRichTextHtml(out, table.caption);
      out.append("</caption>");
    }
    if (table.cells != null) {
      for (TdApi.PageBlockTableCell[] row : table.cells) {
        out.append("<tr>");
        if (row != null) {
          for (TdApi.PageBlockTableCell cell : row) {
            if (cell == null) {
              continue;
            }
            String tag = cell.isHeader ? "th" : "td";
            out.append('<').append(tag);
            if (cell.colspan > 1) {
              out.append(" colspan=\"").append(cell.colspan).append('"');
            }
            if (cell.rowspan > 1) {
              out.append(" rowspan=\"").append(cell.rowspan).append('"');
            }
            String align = horizontalAlignment(cell.align);
            String valign = verticalAlignment(cell.valign);
            if (align != null) {
              out.append(" align=\"").append(align).append('"');
            }
            if (valign != null) {
              out.append(" valign=\"").append(valign).append('"');
            }
            out.append('>');
            appendRichTextHtml(out, cell.text);
            out.append("</").append(tag).append('>');
          }
        }
        out.append("</tr>");
      }
    }
    out.append("</table>");
  }

  private static void appendMediaHtml (StringBuilder out, String type,
                                       @Nullable TdApi.PageBlockCaption caption) {
    out.append("<figure data-media=\"").append(type).append("\"><span class=\"media-label\">[")
      .append(escapeHtml(type)).append("]</span>");
    appendCaptionHtml(out, caption);
    out.append("</figure>");
  }

  private static void appendCaptionHtml (StringBuilder out, @Nullable TdApi.PageBlockCaption caption) {
    if (caption == null) {
      return;
    }
    out.append("<figcaption>");
    appendRichTextHtml(out, caption.text);
    if (caption.credit != null) {
      out.append("<cite>");
      appendRichTextHtml(out, caption.credit);
      out.append("</cite>");
    }
    out.append("</figcaption>");
  }

  private static void appendRichTextPlain (StringBuilder out, @Nullable TdApi.RichText text) {
    if (text == null) {
      return;
    }
    try {
      switch (text.getConstructor()) {
        case TdApi.RichTextPlain.CONSTRUCTOR:
          out.append(nullToEmpty(((TdApi.RichTextPlain) text).text));
          return;
        case TdApi.RichTextCustomEmoji.CONSTRUCTOR:
          out.append(nullToEmpty(((TdApi.RichTextCustomEmoji) text).alternativeText));
          return;
        case TdApi.RichTextIcon.CONSTRUCTOR:
          out.append('\uFFFC');
          return;
        case TdApi.RichTextMathematicalExpression.CONSTRUCTOR:
          out.append(nullToEmpty(((TdApi.RichTextMathematicalExpression) text).expression));
          return;
        case TdApi.RichTextAnchor.CONSTRUCTOR:
          return;
        case TdApi.RichTextReference.CONSTRUCTOR:
          appendRichTextPlain(out, ((TdApi.RichTextReference) text).text);
          return;
        case TdApi.RichTextDiff.CONSTRUCTOR:
          appendRichTextPlain(out, ((TdApi.RichTextDiff) text).text);
          return;
        case TdApi.RichTexts.CONSTRUCTOR: {
          TdApi.RichText[] texts = ((TdApi.RichTexts) text).texts;
          if (texts != null) {
            for (TdApi.RichText child : texts) {
              appendRichTextPlain(out, child);
            }
          }
          return;
        }
        case TdApi.RichTextDateTime.CONSTRUCTOR: {
          TdApi.RichTextDateTime dateTime = (TdApi.RichTextDateTime) text;
          String formatted = formatDateTime(dateTime);
          if (formatted != null) {
            out.append(formatted);
          } else {
            appendRichTextPlain(out, dateTime.text);
          }
          return;
        }
        default:
          TdApi.RichText child = wrappedRichText(text);
          if (child != null) {
            appendRichTextPlain(out, child);
          }
      }
    } catch (Throwable ignored) {
      appendUnknownPlain(out, text);
    }
  }

  private static void appendRichTextHtml (StringBuilder out, @Nullable TdApi.RichText text) {
    if (text == null) {
      return;
    }
    try {
      switch (text.getConstructor()) {
        case TdApi.RichTextPlain.CONSTRUCTOR:
          out.append(escapeHtml(nullToEmpty(((TdApi.RichTextPlain) text).text)));
          return;
        case TdApi.RichTextBold.CONSTRUCTOR:
          inlineTag(out, "strong", ((TdApi.RichTextBold) text).text);
          return;
        case TdApi.RichTextItalic.CONSTRUCTOR:
          inlineTag(out, "em", ((TdApi.RichTextItalic) text).text);
          return;
        case TdApi.RichTextUnderline.CONSTRUCTOR:
          inlineTag(out, "u", ((TdApi.RichTextUnderline) text).text);
          return;
        case TdApi.RichTextStrikethrough.CONSTRUCTOR:
          inlineTag(out, "s", ((TdApi.RichTextStrikethrough) text).text);
          return;
        case TdApi.RichTextSpoiler.CONSTRUCTOR:
          inlineTag(out, "span class=\"spoiler\"", ((TdApi.RichTextSpoiler) text).text);
          return;
        case TdApi.RichTextSubscript.CONSTRUCTOR:
          inlineTag(out, "sub", ((TdApi.RichTextSubscript) text).text);
          return;
        case TdApi.RichTextSuperscript.CONSTRUCTOR:
          inlineTag(out, "sup", ((TdApi.RichTextSuperscript) text).text);
          return;
        case TdApi.RichTextMarked.CONSTRUCTOR:
          inlineTag(out, "mark", ((TdApi.RichTextMarked) text).text);
          return;
        case TdApi.RichTextFixed.CONSTRUCTOR:
          inlineTag(out, "code", ((TdApi.RichTextFixed) text).text);
          return;
        case TdApi.RichTextDateTime.CONSTRUCTOR: {
          TdApi.RichTextDateTime dateTime = (TdApi.RichTextDateTime) text;
          out.append("<time datetime=\"").append(dateTime.unixTime).append("\">");
          String formatted = formatDateTime(dateTime);
          if (formatted != null) {
            out.append(escapeHtml(formatted));
          } else {
            appendRichTextHtml(out, dateTime.text);
          }
          out.append("</time>");
          return;
        }
        case TdApi.RichTextMention.CONSTRUCTOR: {
          TdApi.RichTextMention mention = (TdApi.RichTextMention) text;
          link(out, "https://t.me/" + nullToEmpty(mention.username), mention.text);
          return;
        }
        case TdApi.RichTextHashtag.CONSTRUCTOR: {
          TdApi.RichTextHashtag hashtag = (TdApi.RichTextHashtag) text;
          inlineTag(out, "span data-hashtag=\"" + escapeAttribute(hashtag.hashtag) + "\"", hashtag.text);
          return;
        }
        case TdApi.RichTextCashtag.CONSTRUCTOR: {
          TdApi.RichTextCashtag cashtag = (TdApi.RichTextCashtag) text;
          inlineTag(out, "span data-cashtag=\"" + escapeAttribute(cashtag.cashtag) + "\"", cashtag.text);
          return;
        }
        case TdApi.RichTextBankCardNumber.CONSTRUCTOR: {
          TdApi.RichTextBankCardNumber card = (TdApi.RichTextBankCardNumber) text;
          inlineTag(out, "span data-card=\"" + escapeAttribute(card.bankCardNumber) + "\"", card.text);
          return;
        }
        case TdApi.RichTextBotCommand.CONSTRUCTOR: {
          TdApi.RichTextBotCommand command = (TdApi.RichTextBotCommand) text;
          inlineTag(out, "span data-command=\"" + escapeAttribute(command.botCommand) + "\"", command.text);
          return;
        }
        case TdApi.RichTextMentionName.CONSTRUCTOR: {
          TdApi.RichTextMentionName mention = (TdApi.RichTextMentionName) text;
          link(out, "tg://user?id=" + mention.userId, mention.text);
          return;
        }
        case TdApi.RichTextUrl.CONSTRUCTOR: {
          TdApi.RichTextUrl url = (TdApi.RichTextUrl) text;
          link(out, url.url, url.text);
          return;
        }
        case TdApi.RichTextEmailAddress.CONSTRUCTOR: {
          TdApi.RichTextEmailAddress email = (TdApi.RichTextEmailAddress) text;
          link(out, "mailto:" + nullToEmpty(email.emailAddress), email.text);
          return;
        }
        case TdApi.RichTextPhoneNumber.CONSTRUCTOR: {
          TdApi.RichTextPhoneNumber phone = (TdApi.RichTextPhoneNumber) text;
          link(out, "tel:" + nullToEmpty(phone.phoneNumber), phone.text);
          return;
        }
        case TdApi.RichTextCustomEmoji.CONSTRUCTOR: {
          TdApi.RichTextCustomEmoji emoji = (TdApi.RichTextCustomEmoji) text;
          out.append("<span data-custom-emoji=\"").append(emoji.customEmojiId).append("\">")
            .append(escapeHtml(nullToEmpty(emoji.alternativeText))).append("</span>");
          return;
        }
        case TdApi.RichTextIcon.CONSTRUCTOR:
          out.append("<span class=\"inline-icon\">\uFFFC</span>");
          return;
        case TdApi.RichTextMathematicalExpression.CONSTRUCTOR: {
          String expression = nullToEmpty(((TdApi.RichTextMathematicalExpression) text).expression);
          out.append("<span class=\"math\" data-latex=\"").append(escapeAttribute(expression))
            .append("\">").append(escapeHtml(expression)).append("</span>");
          return;
        }
        case TdApi.RichTextDiff.CONSTRUCTOR: {
          TdApi.RichTextDiff diff = (TdApi.RichTextDiff) text;
          inlineTag(out, "del", diff.oldText);
          inlineTag(out, "ins", diff.text);
          return;
        }
        case TdApi.RichTextReference.CONSTRUCTOR: {
          TdApi.RichTextReference reference = (TdApi.RichTextReference) text;
          out.append("<span id=\"ref-").append(escapeAttribute(reference.name)).append("\">");
          appendRichTextHtml(out, reference.text);
          out.append("</span>");
          return;
        }
        case TdApi.RichTextReferenceLink.CONSTRUCTOR: {
          TdApi.RichTextReferenceLink reference = (TdApi.RichTextReferenceLink) text;
          String href = reference.url != null && !reference.url.isEmpty() ?
            reference.url : "#ref-" + nullToEmpty(reference.referenceName);
          link(out, href, reference.text);
          return;
        }
        case TdApi.RichTextAnchor.CONSTRUCTOR: {
          TdApi.RichTextAnchor anchor = (TdApi.RichTextAnchor) text;
          out.append("<a id=\"").append(escapeAttribute(anchor.name)).append("\"></a>");
          return;
        }
        case TdApi.RichTextAnchorLink.CONSTRUCTOR: {
          TdApi.RichTextAnchorLink anchor = (TdApi.RichTextAnchorLink) text;
          String href = anchor.url != null && !anchor.url.isEmpty() ?
            anchor.url : "#" + nullToEmpty(anchor.anchorName);
          link(out, href, anchor.text);
          return;
        }
        case TdApi.RichTexts.CONSTRUCTOR: {
          TdApi.RichText[] texts = ((TdApi.RichTexts) text).texts;
          if (texts != null) {
            for (TdApi.RichText child : texts) {
              appendRichTextHtml(out, child);
            }
          }
          return;
        }
        default:
          out.append(escapeHtml(richTextToPlain(text)));
      }
    } catch (Throwable ignored) {
      out.append(escapeHtml(richTextToPlain(text)));
    }
  }

  private static void collectMediaBlocks (@Nullable TdApi.PageBlock[] blocks, String parentPath,
                                          List<MediaItem> out) {
    if (blocks == null) {
      return;
    }
    for (int index = 0; index < blocks.length; index++) {
      TdApi.PageBlock block = blocks[index];
      if (block == null) {
        continue;
      }
      String path = childPath(parentPath, index);
      try {
        switch (block.getConstructor()) {
          case TdApi.PageBlockPhoto.CONSTRUCTOR: {
            TdApi.Photo photo = ((TdApi.PageBlockPhoto) block).photo;
            out.add(new MediaItem(path, MediaItem.PHOTO, block, largestPhotoFile(photo)));
            collectCaptionMedia(((TdApi.PageBlockPhoto) block).caption, path, out);
            break;
          }
          case TdApi.PageBlockVideo.CONSTRUCTOR: {
            TdApi.Video video = ((TdApi.PageBlockVideo) block).video;
            out.add(new MediaItem(path, MediaItem.VIDEO, block, video != null ? video.video : null));
            collectCaptionMedia(((TdApi.PageBlockVideo) block).caption, path, out);
            break;
          }
          case TdApi.PageBlockAnimation.CONSTRUCTOR: {
            TdApi.Animation animation = ((TdApi.PageBlockAnimation) block).animation;
            out.add(new MediaItem(path, MediaItem.ANIMATION, block,
              animation != null ? animation.animation : null));
            collectCaptionMedia(((TdApi.PageBlockAnimation) block).caption, path, out);
            break;
          }
          case TdApi.PageBlockAudio.CONSTRUCTOR: {
            TdApi.Audio audio = ((TdApi.PageBlockAudio) block).audio;
            out.add(new MediaItem(path, MediaItem.AUDIO, block, audio != null ? audio.audio : null));
            collectCaptionMedia(((TdApi.PageBlockAudio) block).caption, path, out);
            break;
          }
          case TdApi.PageBlockVoiceNote.CONSTRUCTOR: {
            TdApi.VoiceNote voice = ((TdApi.PageBlockVoiceNote) block).voiceNote;
            out.add(new MediaItem(path, MediaItem.VOICE_NOTE, block,
              voice != null ? voice.voice : null));
            collectCaptionMedia(((TdApi.PageBlockVoiceNote) block).caption, path, out);
            break;
          }
          case TdApi.PageBlockMap.CONSTRUCTOR:
            out.add(new MediaItem(path, MediaItem.MAP, block, null));
            collectCaptionMedia(((TdApi.PageBlockMap) block).caption, path, out);
            break;
          case TdApi.PageBlockCover.CONSTRUCTOR:
            collectMediaBlocks(new TdApi.PageBlock[] { ((TdApi.PageBlockCover) block).cover }, path, out);
            break;
          case TdApi.PageBlockList.CONSTRUCTOR: {
            TdApi.PageBlockListItem[] items = ((TdApi.PageBlockList) block).items;
            if (items != null) {
              for (int item = 0; item < items.length; item++) {
                if (items[item] != null) {
                  collectMediaBlocks(items[item].blocks, childPath(path, item), out);
                }
              }
            }
            break;
          }
          case TdApi.PageBlockBlockQuote.CONSTRUCTOR:
            collectMediaBlocks(((TdApi.PageBlockBlockQuote) block).blocks, path, out);
            collectRichTextMedia(((TdApi.PageBlockBlockQuote) block).credit, path + "/credit", out);
            break;
          case TdApi.PageBlockCollage.CONSTRUCTOR:
            collectMediaBlocks(((TdApi.PageBlockCollage) block).blocks, path, out);
            collectCaptionMedia(((TdApi.PageBlockCollage) block).caption, path, out);
            break;
          case TdApi.PageBlockSlideshow.CONSTRUCTOR:
            collectMediaBlocks(((TdApi.PageBlockSlideshow) block).blocks, path, out);
            collectCaptionMedia(((TdApi.PageBlockSlideshow) block).caption, path, out);
            break;
          case TdApi.PageBlockDetails.CONSTRUCTOR:
            collectRichTextMedia(((TdApi.PageBlockDetails) block).header, path + "/header", out);
            collectMediaBlocks(((TdApi.PageBlockDetails) block).blocks, path, out);
            break;
          case TdApi.PageBlockTable.CONSTRUCTOR: {
            TdApi.PageBlockTable table = (TdApi.PageBlockTable) block;
            collectRichTextMedia(table.caption, path + "/caption", out);
            if (table.cells != null) {
              for (int row = 0; row < table.cells.length; row++) {
                TdApi.PageBlockTableCell[] cells = table.cells[row];
                if (cells != null) {
                  for (int column = 0; column < cells.length; column++) {
                    if (cells[column] != null) {
                      collectRichTextMedia(cells[column].text,
                        path + "/" + row + "/" + column, out);
                    }
                  }
                }
              }
            }
            break;
          }
          default:
            collectRichTextMedia(blockRichText(block), path, out);
            break;
        }
      } catch (Throwable ignored) { }
    }
  }

  private static void collectCaptionMedia (@Nullable TdApi.PageBlockCaption caption, String path,
                                           List<MediaItem> out) {
    if (caption != null) {
      collectRichTextMedia(caption.text, path + "/caption", out);
      collectRichTextMedia(caption.credit, path + "/credit", out);
    }
  }

  private static void collectRichTextMedia (@Nullable TdApi.RichText text, String path,
                                            List<MediaItem> out) {
    if (text == null) {
      return;
    }
    if (text.getConstructor() == TdApi.RichTextIcon.CONSTRUCTOR) {
      TdApi.Document document = ((TdApi.RichTextIcon) text).document;
      out.add(new MediaItem(path, MediaItem.INLINE_ICON, text,
        document != null ? document.document : null));
    } else if (text.getConstructor() == TdApi.RichTexts.CONSTRUCTOR) {
      TdApi.RichText[] texts = ((TdApi.RichTexts) text).texts;
      if (texts != null) {
        for (int index = 0; index < texts.length; index++) {
          collectRichTextMedia(texts[index], childPath(path, index), out);
        }
      }
    } else {
      collectRichTextMedia(wrappedRichText(text), path, out);
      if (text.getConstructor() == TdApi.RichTextDiff.CONSTRUCTOR) {
        collectRichTextMedia(((TdApi.RichTextDiff) text).oldText, path + "/old", out);
      }
    }
  }

  private static void indexBlocks (@Nullable TdApi.PageBlock[] blocks, String parentPath,
                                  Map<String, String> anchors, Map<String, String> references) {
    if (blocks == null) {
      return;
    }
    for (int index = 0; index < blocks.length; index++) {
      TdApi.PageBlock block = blocks[index];
      if (block == null) {
        continue;
      }
      String path = childPath(parentPath, index);
      if (block.getConstructor() == TdApi.PageBlockAnchor.CONSTRUCTOR) {
        putNormalized(anchors, ((TdApi.PageBlockAnchor) block).name, path);
      }
      indexRichText(blockRichText(block), path, anchors, references);
      TdApi.PageBlockCaption caption = RichMessageFlattener.captionOf(block);
      if (caption != null) {
        indexRichText(caption.text, path + "/caption", anchors, references);
        indexRichText(caption.credit, path + "/credit", anchors, references);
      }
      try {
        switch (block.getConstructor()) {
          case TdApi.PageBlockTable.CONSTRUCTOR: {
            TdApi.PageBlockTableCell[][] rows = ((TdApi.PageBlockTable) block).cells;
            if (rows != null) {
              for (TdApi.PageBlockTableCell[] row : rows) {
                if (row == null) continue;
                for (TdApi.PageBlockTableCell cell : row) {
                  if (cell != null) indexRichText(cell.text, path, anchors, references);
                }
              }
            }
            break;
          }
          case TdApi.PageBlockPullQuote.CONSTRUCTOR:
            indexRichText(((TdApi.PageBlockPullQuote) block).credit,
              path + "/credit", anchors, references);
            break;
          case TdApi.PageBlockList.CONSTRUCTOR: {
            TdApi.PageBlockListItem[] items = ((TdApi.PageBlockList) block).items;
            if (items != null) {
              for (int item = 0; item < items.length; item++) {
                if (items[item] != null) {
                  indexBlocks(items[item].blocks, childPath(path, item), anchors, references);
                }
              }
            }
            break;
          }
          case TdApi.PageBlockBlockQuote.CONSTRUCTOR:
            indexBlocks(((TdApi.PageBlockBlockQuote) block).blocks, path, anchors, references);
            indexRichText(((TdApi.PageBlockBlockQuote) block).credit, path + "/credit", anchors, references);
            break;
          case TdApi.PageBlockCover.CONSTRUCTOR:
            indexBlocks(new TdApi.PageBlock[] { ((TdApi.PageBlockCover) block).cover },
              path, anchors, references);
            break;
          case TdApi.PageBlockCollage.CONSTRUCTOR:
            indexBlocks(((TdApi.PageBlockCollage) block).blocks, path, anchors, references);
            break;
          case TdApi.PageBlockSlideshow.CONSTRUCTOR:
            indexBlocks(((TdApi.PageBlockSlideshow) block).blocks, path, anchors, references);
            break;
          case TdApi.PageBlockDetails.CONSTRUCTOR:
            indexBlocks(((TdApi.PageBlockDetails) block).blocks, path, anchors, references);
            break;
        }
      } catch (Throwable ignored) { }
    }
  }

  private static void indexRichText (@Nullable TdApi.RichText text, String path,
                                    Map<String, String> anchors, Map<String, String> references) {
    if (text == null) {
      return;
    }
    switch (text.getConstructor()) {
      case TdApi.RichTextAnchor.CONSTRUCTOR:
        putNormalized(anchors, ((TdApi.RichTextAnchor) text).name, path);
        break;
      case TdApi.RichTextReference.CONSTRUCTOR: {
        TdApi.RichTextReference reference = (TdApi.RichTextReference) text;
        putNormalized(references, reference.name, path);
        indexRichText(reference.text, path, anchors, references);
        break;
      }
      case TdApi.RichTexts.CONSTRUCTOR: {
        TdApi.RichText[] texts = ((TdApi.RichTexts) text).texts;
        if (texts != null) {
          for (int index = 0; index < texts.length; index++) {
            // Inline text belongs to its containing block; child text indices
            // are not block paths and can collide with nested block indices.
            indexRichText(texts[index], path, anchors, references);
          }
        }
        break;
      }
      default:
        indexRichText(wrappedRichText(text), path, anchors, references);
        break;
    }
  }

  private static @Nullable TdApi.RichText blockRichText (TdApi.PageBlock block) {
    try {
      switch (block.getConstructor()) {
        case TdApi.PageBlockTitle.CONSTRUCTOR: return ((TdApi.PageBlockTitle) block).title;
        case TdApi.PageBlockSubtitle.CONSTRUCTOR: return ((TdApi.PageBlockSubtitle) block).subtitle;
        case TdApi.PageBlockAuthorDate.CONSTRUCTOR: return ((TdApi.PageBlockAuthorDate) block).author;
        case TdApi.PageBlockHeader.CONSTRUCTOR: return ((TdApi.PageBlockHeader) block).header;
        case TdApi.PageBlockSubheader.CONSTRUCTOR: return ((TdApi.PageBlockSubheader) block).subheader;
        case TdApi.PageBlockSectionHeading.CONSTRUCTOR: return ((TdApi.PageBlockSectionHeading) block).text;
        case TdApi.PageBlockKicker.CONSTRUCTOR: return ((TdApi.PageBlockKicker) block).kicker;
        case TdApi.PageBlockParagraph.CONSTRUCTOR: return ((TdApi.PageBlockParagraph) block).text;
        case TdApi.PageBlockPreformatted.CONSTRUCTOR: return ((TdApi.PageBlockPreformatted) block).text;
        case TdApi.PageBlockFooter.CONSTRUCTOR: return ((TdApi.PageBlockFooter) block).footer;
        case TdApi.PageBlockThinking.CONSTRUCTOR: return ((TdApi.PageBlockThinking) block).text;
        case TdApi.PageBlockPullQuote.CONSTRUCTOR: return ((TdApi.PageBlockPullQuote) block).text;
        case TdApi.PageBlockDetails.CONSTRUCTOR: return ((TdApi.PageBlockDetails) block).header;
        case TdApi.PageBlockTable.CONSTRUCTOR: return ((TdApi.PageBlockTable) block).caption;
      }
    } catch (Throwable ignored) { }
    return null;
  }

  private static @Nullable TdApi.RichText wrappedRichText (TdApi.RichText text) {
    try {
      for (Field field : text.getClass().getFields()) {
        if (TdApi.RichText.class.isAssignableFrom(field.getType())) {
          return (TdApi.RichText) field.get(text);
        }
      }
    } catch (Throwable ignored) { }
    return null;
  }

  private static void appendUnknownPlain (StringBuilder out, TdApi.Object object) {
    int before = out.length();
    try {
      for (Field field : object.getClass().getFields()) {
        Object value = field.get(object);
        if (value instanceof TdApi.RichText) {
          appendRichTextPlain(out, (TdApi.RichText) value);
        } else if (value instanceof TdApi.PageBlock) {
          appendBlocksPlain(out, new TdApi.PageBlock[] { (TdApi.PageBlock) value }, "", 0);
        } else if (value != null && value.getClass().isArray()) {
          int length = Array.getLength(value);
          for (int index = 0; index < length; index++) {
            Object item = Array.get(value, index);
            if (item instanceof TdApi.RichText) {
              appendRichTextPlain(out, (TdApi.RichText) item);
            } else if (item instanceof TdApi.PageBlock) {
              appendBlocksPlain(out, new TdApi.PageBlock[] { (TdApi.PageBlock) item }, "", 0);
            }
          }
        }
      }
    } catch (Throwable ignored) { }
    if (out.length() == before && object instanceof TdApi.PageBlock) {
      out.append("[Unsupported content]");
    }
  }

  public static @Nullable String formatDateTime (TdApi.RichTextDateTime dateTime) {
    if (dateTime.formattingType == null) {
      return null;
    }
    if (dateTime.formattingType.getConstructor() == TdApi.DateTimeFormattingTypeRelative.CONSTRUCTOR) {
      long when = dateTime.unixTime * 1000L;
      long now = System.currentTimeMillis();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return RelativeDateFormatter.format(when - now, Lang.dateFormatLocale());
      }
      return DateUtils.getRelativeTimeSpanString(when, now, 1000L,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }
    TdApi.DateTimeFormattingTypeAbsolute absolute =
      (TdApi.DateTimeFormattingTypeAbsolute) dateTime.formattingType;
    return formatAbsoluteDateTime(dateTime.unixTime, absolute,
      Lang.dateFormatLocale(), UI.needAmPm());
  }

  private static String formatAbsoluteDateTime (long unixTime,
                                               TdApi.DateTimeFormattingTypeAbsolute absolute,
                                               Locale locale, boolean useAmPm) {
    int dateStyle = dateStyle(absolute.datePrecision);
    int timeStyle = timeStyle(absolute.timePrecision);
    Date value = new Date(unixTime * 1000L);
    if (dateStyle < 0 && timeStyle < 0 && !absolute.showDayOfWeek) {
      return "";
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      String skeleton = dateStyle < 0 ? "" : dateStyle == DateFormat.LONG ? "yMMMMd" : "yMd";
      if (absolute.showDayOfWeek) skeleton += "EEEE";
      if (timeStyle >= 0) skeleton += (useAmPm ? "hm" : "Hm") +
        (timeStyle == DateFormat.MEDIUM ? "s" : "");
      String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton);
      return new SimpleDateFormat(pattern, locale).format(value);
    }
    String date = "";
    if (dateStyle >= 0) {
      DateFormat formatter = DateFormat.getDateInstance(
        absolute.showDayOfWeek && dateStyle == DateFormat.LONG ? DateFormat.FULL : dateStyle,
        locale);
      if (absolute.showDayOfWeek && dateStyle == DateFormat.SHORT) {
        formatter = new SimpleDateFormat("EEEE, " +
          ((SimpleDateFormat) formatter).toPattern(), locale);
      }
      date = formatter.format(value);
    } else if (absolute.showDayOfWeek) {
      date = new SimpleDateFormat("EEEE", locale).format(value);
    }
    if (timeStyle < 0) return date;
    String timePattern = useAmPm ? "h:mm" : "HH:mm";
    if (timeStyle == DateFormat.MEDIUM) timePattern += ":ss";
    if (useAmPm) timePattern += " a";
    String time = new SimpleDateFormat(timePattern, locale).format(value);
    return date.isEmpty() ? time : date + " " + time;
  }

  private static int dateStyle (@Nullable TdApi.DateTimePartPrecision precision) {
    if (precision == null || precision.getConstructor() == TdApi.DateTimePartPrecisionNone.CONSTRUCTOR) {
      return -1;
    }
    return precision.getConstructor() == TdApi.DateTimePartPrecisionLong.CONSTRUCTOR ?
      DateFormat.LONG : DateFormat.SHORT;
  }

  private static int timeStyle (@Nullable TdApi.DateTimePartPrecision precision) {
    if (precision == null || precision.getConstructor() == TdApi.DateTimePartPrecisionNone.CONSTRUCTOR) {
      return -1;
    }
    return precision.getConstructor() == TdApi.DateTimePartPrecisionLong.CONSTRUCTOR ?
      DateFormat.MEDIUM : DateFormat.SHORT;
  }

  @RequiresApi(Build.VERSION_CODES.N)
  private static final class RelativeDateFormatter {
    static String format (long differenceMs, Locale locale) {
      android.icu.text.RelativeDateTimeFormatter formatter =
        android.icu.text.RelativeDateTimeFormatter.getInstance(locale);
      long seconds = Math.max(1, Math.round(Math.abs(differenceMs) / 1000.0));
      long divisor;
      android.icu.text.RelativeDateTimeFormatter.RelativeUnit unit;
      if (seconds < 60) {
        divisor = 1;
        unit = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS;
      } else if (seconds < 3600) {
        divisor = 60;
        unit = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES;
      } else if (seconds < 86400) {
        divisor = 3600;
        unit = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS;
      } else if (seconds < 2592000) {
        divisor = 86400;
        unit = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS;
      } else if (seconds < 31536000) {
        divisor = 2592000;
        unit = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS;
      } else {
        divisor = 31536000;
        unit = android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS;
      }
      return formatter.format(Math.round((double) seconds / divisor),
        differenceMs > 0 ? android.icu.text.RelativeDateTimeFormatter.Direction.NEXT :
          android.icu.text.RelativeDateTimeFormatter.Direction.LAST, unit);
    }
  }

  private static void inlineTag (StringBuilder out, String tag, @Nullable TdApi.RichText text) {
    String name = tag;
    int space = tag.indexOf(' ');
    if (space != -1) {
      name = tag.substring(0, space);
    }
    out.append('<').append(tag).append('>');
    appendRichTextHtml(out, text);
    out.append("</").append(name).append('>');
  }

  private static void tag (StringBuilder out, String tag, @Nullable TdApi.RichText text) {
    inlineTag(out, tag, text);
  }

  private static void link (StringBuilder out, @Nullable String href, @Nullable TdApi.RichText text) {
    out.append("<a href=\"").append(escapeAttribute(href)).append("\">");
    appendRichTextHtml(out, text);
    out.append("</a>");
  }

  private static String horizontalAlignment (@Nullable TdApi.PageBlockHorizontalAlignment alignment) {
    if (alignment == null) return null;
    switch (alignment.getConstructor()) {
      case TdApi.PageBlockHorizontalAlignmentLeft.CONSTRUCTOR: return "left";
      case TdApi.PageBlockHorizontalAlignmentCenter.CONSTRUCTOR: return "center";
      case TdApi.PageBlockHorizontalAlignmentRight.CONSTRUCTOR: return "right";
    }
    return null;
  }

  private static String verticalAlignment (@Nullable TdApi.PageBlockVerticalAlignment alignment) {
    if (alignment == null) return null;
    switch (alignment.getConstructor()) {
      case TdApi.PageBlockVerticalAlignmentTop.CONSTRUCTOR: return "top";
      case TdApi.PageBlockVerticalAlignmentMiddle.CONSTRUCTOR: return "middle";
      case TdApi.PageBlockVerticalAlignmentBottom.CONSTRUCTOR: return "bottom";
    }
    return null;
  }

  private static void appendQuoted (StringBuilder out, StringBuilder quoted, int depth) {
    trimTrailingWhitespace(quoted);
    String prefix = repeat("> ", Math.max(1, depth));
    String[] lines = quoted.toString().split("\\n", -1);
    for (int index = 0; index < lines.length; index++) {
      if (index > 0) {
        out.append('\n');
      }
      out.append(prefix).append(lines[index]);
    }
  }

  private static String formatListValue (int value, @Nullable String type) {
    if (value <= 0) {
      value = 1;
    }
    if ("a".equals(type) || "A".equals(type)) {
      StringBuilder out = new StringBuilder();
      int number = value;
      while (number > 0) {
        number--;
        out.append((char) ('a' + number % 26));
        number /= 26;
      }
      String result = out.reverse().toString();
      return "A".equals(type) ? result.toUpperCase(Locale.US) : result;
    }
    if ("i".equals(type) || "I".equals(type)) {
      String roman = roman(value);
      return "i".equals(type) ? roman.toLowerCase(Locale.US) : roman;
    }
    return Integer.toString(value);
  }

  private static String roman (int value) {
    if (value <= 0 || value > 3999) {
      return Integer.toString(value);
    }
    int[] values = { 1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1 };
    String[] numerals = { "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I" };
    StringBuilder out = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      while (value >= values[index]) {
        out.append(numerals[index]);
        value -= values[index];
      }
    }
    return out.toString();
  }

  private static @Nullable TdApi.File largestPhotoFile (@Nullable TdApi.Photo photo) {
    if (photo == null || photo.sizes == null) {
      return null;
    }
    TdApi.PhotoSize largest = null;
    for (TdApi.PhotoSize size : photo.sizes) {
      if (size != null && (largest == null || (long) size.width * size.height >
          (long) largest.width * largest.height)) {
        largest = size;
      }
    }
    return largest != null ? largest.photo : null;
  }

  private static void putNormalized (Map<String, String> out, @Nullable String name, String path) {
    if (name != null) {
      out.put(name.toLowerCase(Locale.US), path);
    }
  }

  private static String childPath (String parent, int index) {
    return parent.isEmpty() ? Integer.toString(index) : parent + "/" + index;
  }

  private static void appendBlockSeparator (StringBuilder out) {
    trimTrailingNewlines(out);
    out.append("\n\n");
  }

  private static boolean endsWithNewline (StringBuilder out) {
    return out.length() > 0 && out.charAt(out.length() - 1) == '\n';
  }

  private static void trimTrailingWhitespace (StringBuilder out) {
    while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
      out.setLength(out.length() - 1);
    }
  }

  private static void trimTrailingNewlines (StringBuilder out) {
    while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
      out.setLength(out.length() - 1);
    }
  }

  private static String repeat (String text, int count) {
    StringBuilder out = new StringBuilder(text.length() * count);
    for (int index = 0; index < count; index++) {
      out.append(text);
    }
    return out.toString();
  }

  private static String nullToEmpty (@Nullable String value) {
    return value != null ? value : "";
  }

  private static String escapeHtml (@Nullable String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int index = 0; index < value.length(); index++) {
      switch (value.charAt(index)) {
        case '&': out.append("&amp;"); break;
        case '<': out.append("&lt;"); break;
        case '>': out.append("&gt;"); break;
        case '"': out.append("&quot;"); break;
        case '\'': out.append("&#39;"); break;
        default: out.append(value.charAt(index)); break;
      }
    }
    return out.toString();
  }

  private static String escapeAttribute (@Nullable String value) {
    return escapeHtml(nullToEmpty(value));
  }
}
