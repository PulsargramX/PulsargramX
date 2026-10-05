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

import org.drinkless.tdlib.TdApi;

import java.util.Arrays;

/**
 * Debug-only rich-message fixtures for visual and performance validation.
 */
public final class RichMessageFixtures {
  private RichMessageFixtures () { }

  public static TdApi.RichMessage allConstructors () {
    TdApi.RichText inline = new TdApi.RichTexts(new TdApi.RichText[] {
      plain("plain "),
      new TdApi.RichTextBold(plain("bold ")),
      new TdApi.RichTextItalic(plain("italic ")),
      new TdApi.RichTextUnderline(plain("underline ")),
      new TdApi.RichTextStrikethrough(plain("strike ")),
      new TdApi.RichTextSpoiler(plain("spoiler ")),
      new TdApi.RichTextSubscript(plain("subscript ")),
      new TdApi.RichTextSuperscript(plain("superscript ")),
      new TdApi.RichTextMarked(plain("marked ")),
      new TdApi.RichTextFixed(plain("fixed ")),
      new TdApi.RichTextUrl(plain("URL "), "https://example.com", false),
      new TdApi.RichTextEmailAddress(plain("email "), "rich@example.com"),
      new TdApi.RichTextPhoneNumber(plain("phone "), "+380000000000"),
      new TdApi.RichTextMention(plain("mention "), "telegram"),
      new TdApi.RichTextMentionName(plain("user "), 1),
      new TdApi.RichTextBotCommand(plain("command "), "/start"),
      new TdApi.RichTextHashtag(plain("hashtag "), "#rich"),
      new TdApi.RichTextCashtag(plain("cashtag "), "$TON"),
      new TdApi.RichTextBankCardNumber(plain("card "), "0000000000000000"),
      new TdApi.RichTextDateTime(plain("date "), 1_700_000_000,
        new TdApi.DateTimeFormattingTypeRelative()),
      new TdApi.RichTextCustomEmoji(1, "🙂"),
      new TdApi.RichTextMathematicalExpression("\\frac{a}{b}"),
      new TdApi.RichTextDiff(plain("new "), plain("old ")),
      new TdApi.RichTextReference("note", plain("reference ")),
      new TdApi.RichTextReferenceLink(plain("reference link "), "note", ""),
      new TdApi.RichTextAnchor("named-anchor"),
      new TdApi.RichTextAnchorLink(plain("anchor link"), "named-anchor", "")
    });
    TdApi.PageBlockCaption caption = new TdApi.PageBlockCaption(
      plain("Caption"), plain("Credit"));

    TdApi.PageBlock[] blocks = new TdApi.PageBlock[] {
      new TdApi.PageBlockTitle(plain("Title")),
      new TdApi.PageBlockSubtitle(plain("Subtitle")),
      new TdApi.PageBlockAuthorDate(plain("Author"), 1_700_000_000),
      new TdApi.PageBlockHeader(plain("Header")),
      new TdApi.PageBlockSubheader(plain("Subheader")),
      new TdApi.PageBlockSectionHeading(plain("Heading 1"), 1),
      new TdApi.PageBlockSectionHeading(plain("Heading 2"), 2),
      new TdApi.PageBlockSectionHeading(plain("Heading 3"), 3),
      new TdApi.PageBlockSectionHeading(plain("Heading 4"), 4),
      new TdApi.PageBlockSectionHeading(plain("Heading 5"), 5),
      new TdApi.PageBlockSectionHeading(plain("Heading 6"), 6),
      new TdApi.PageBlockKicker(plain("Kicker")),
      new TdApi.PageBlockParagraph(inline),
      new TdApi.PageBlockPreformatted(plain("fun main() = Unit"), "kotlin"),
      new TdApi.PageBlockFooter(plain("Footer")),
      new TdApi.PageBlockThinking(plain("Thinking…")),
      new TdApi.PageBlockDivider(),
      new TdApi.PageBlockMathematicalExpression("\\int_0^1 x^2 dx"),
      new TdApi.PageBlockAnchor("block-anchor"),
      taskList(),
      new TdApi.PageBlockBlockQuote(new TdApi.PageBlock[] {
        new TdApi.PageBlockParagraph(plain("Nested quote"))
      }, plain("Quote credit")),
      new TdApi.PageBlockPullQuote(plain("Pull quote"), plain("Credit")),
      new TdApi.PageBlockPhoto(null, caption, "", true),
      new TdApi.PageBlockVideo(null, caption, true, true, true),
      new TdApi.PageBlockAnimation(null, caption, true, true),
      new TdApi.PageBlockAudio(null, caption),
      new TdApi.PageBlockVoiceNote(null, caption),
      new TdApi.PageBlockMap(new TdApi.Location(50.4501, 30.5234, 0), 14,
        640, 360, caption),
      new TdApi.PageBlockCollage(new TdApi.PageBlock[] {
        new TdApi.PageBlockPhoto(null, caption, "", false),
        new TdApi.PageBlockVideo(null, caption, false, false, false)
      }, caption),
      new TdApi.PageBlockSlideshow(new TdApi.PageBlock[] {
        new TdApi.PageBlockPhoto(null, caption, "", false),
        new TdApi.PageBlockAnimation(null, caption, false, false)
      }, caption),
      new TdApi.PageBlockCover(new TdApi.PageBlockPhoto(null, caption, "", false)),
      twentyColumnTable(),
      nestedDetails(16)
    };
    return new TdApi.RichMessage(blocks, false, true);
  }

  public static TdApi.RichMessage stress () {
    TdApi.PageBlock[] blocks = new TdApi.PageBlock[500];
    char[] characters = new char[32_768];
    Arrays.fill(characters, 'x');
    blocks[0] = new TdApi.PageBlockParagraph(plain(new String(characters)));
    for (int index = 1; index < 450; index++) {
      blocks[index] = new TdApi.PageBlockParagraph(plain("Block " + index));
    }
    for (int index = 450; index < blocks.length; index++) {
      blocks[index] = new TdApi.PageBlockPhoto(null,
        new TdApi.PageBlockCaption(plain("Media " + (index - 449)), null), "", false);
    }
    return new TdApi.RichMessage(blocks, true, false);
  }

  public static TdApi.PageBlock nestedDetails (int depth) {
    TdApi.PageBlock child = new TdApi.PageBlockParagraph(plain("Deep content"));
    for (int index = Math.max(1, depth); index > 0; index--) {
      child = new TdApi.PageBlockDetails(plain("Level " + index),
        new TdApi.PageBlock[] { child }, true);
    }
    return child;
  }

  public static TdApi.PageBlockTable twentyColumnTable () {
    TdApi.PageBlockTableCell[][] cells = new TdApi.PageBlockTableCell[4][20];
    for (int row = 0; row < cells.length; row++) {
      for (int column = 0; column < cells[row].length; column++) {
        cells[row][column] = new TdApi.PageBlockTableCell(
          plain(row + ":" + column), row == 0, 1, 1,
          column % 3 == 0 ? new TdApi.PageBlockHorizontalAlignmentLeft() :
            column % 3 == 1 ? new TdApi.PageBlockHorizontalAlignmentCenter() :
              new TdApi.PageBlockHorizontalAlignmentRight(),
          row % 3 == 0 ? new TdApi.PageBlockVerticalAlignmentTop() :
            row % 3 == 1 ? new TdApi.PageBlockVerticalAlignmentMiddle() :
              new TdApi.PageBlockVerticalAlignmentBottom());
      }
    }
    cells[1][0].colspan = 2;
    cells[1][0].rowspan = 2;
    return new TdApi.PageBlockTable(plain("20-column span table"), cells, true, true, true);
  }

  private static TdApi.PageBlockList taskList () {
    return new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
      new TdApi.PageBlockListItem("1.", new TdApi.PageBlock[] {
        new TdApi.PageBlockParagraph(plain("Ordered"))
      }, false, false, 1, "1"),
      new TdApi.PageBlockListItem("•", new TdApi.PageBlock[] {
        new TdApi.PageBlockParagraph(plain("Unordered")),
        new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
          new TdApi.PageBlockListItem("a.", new TdApi.PageBlock[] {
            new TdApi.PageBlockParagraph(plain("Nested"))
          }, false, false, 1, "a")
        })
      }, false, false, 0, ""),
      new TdApi.PageBlockListItem("", new TdApi.PageBlock[] {
        new TdApi.PageBlockParagraph(plain("Read-only task"))
      }, true, true, 0, "")
    });
  }

  private static TdApi.RichText plain (String text) {
    return new TdApi.RichTextPlain(text);
  }
}
