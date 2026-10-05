package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import static org.junit.Assert.*;

public class RichMessageUtilsTest {
  @Test
  public void serializesStructureToPlainTextAndHtml () {
    TdApi.RichText heading = new TdApi.RichTexts(new TdApi.RichText[] {
      new TdApi.RichTextBold(new TdApi.RichTextPlain("A & B")),
      new TdApi.RichTextPlain(" "),
      new TdApi.RichTextUrl(new TdApi.RichTextPlain("site"),
        "https://example.org/?a=1&b=2", false)
    });
    TdApi.PageBlockTableCell cell = new TdApi.PageBlockTableCell(
      new TdApi.RichTextPlain("cell"), true, 2, 1,
      new TdApi.PageBlockHorizontalAlignmentCenter(),
      new TdApi.PageBlockVerticalAlignmentMiddle());
    TdApi.RichMessage message = new TdApi.RichMessage(new TdApi.PageBlock[] {
      new TdApi.PageBlockSectionHeading(heading, 2),
      new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
        new TdApi.PageBlockListItem("1.", new TdApi.PageBlock[] {
          new TdApi.PageBlockParagraph(new TdApi.RichTextPlain("item"))
        }, false, false, 1, "1")
      }),
      new TdApi.PageBlockBlockQuote(new TdApi.PageBlock[] {
        new TdApi.PageBlockParagraph(new TdApi.RichTextPlain("quote"))
      }, new TdApi.RichTextPlain("credit")),
      new TdApi.PageBlockTable(new TdApi.RichTextPlain("caption"),
        new TdApi.PageBlockTableCell[][] { { cell } }, true, true, false),
      new TdApi.PageBlockMathematicalExpression("\\frac{1}{2}")
    }, false, true);

    String plain = RichMessageUtils.toPlainText(message);
    assertTrue(plain.contains("A & B site"));
    assertTrue(plain.contains("1. item"));
    assertTrue(plain.contains("> quote"));
    assertTrue(plain.contains("cell"));
    assertTrue(plain.contains("\\frac{1}{2}"));

    String html = RichMessageUtils.toHtml(message);
    assertTrue(html.contains("<h2>"));
    assertTrue(html.contains("<strong>A &amp; B</strong>"));
    assertTrue(html.contains("href=\"https://example.org/?a=1&amp;b=2\""));
    assertTrue(html.contains("<ol>"));
    assertTrue(html.contains("<blockquote>"));
    assertTrue(html.contains("<table"));
    assertTrue(html.contains("colspan=\"2\""));
    assertTrue(html.contains("<div class=\"math\" data-latex=\""));
  }

  @Test
  public void indexesEmptyAnchorsAndReferences () {
    TdApi.RichText inline = new TdApi.RichTexts(new TdApi.RichText[] {
      new TdApi.RichTextAnchor("inline"),
      new TdApi.RichTextReference("note", new TdApi.RichTextPlain("definition")),
      new TdApi.RichTextReferenceLink(new TdApi.RichTextPlain("jump"),
        "note", "https://example.org/note")
    });
    TdApi.RichMessage message = new TdApi.RichMessage(new TdApi.PageBlock[] {
      new TdApi.PageBlockAnchor("block"),
      new TdApi.PageBlockParagraph(inline)
    }, false, true);
    RichMessageUtils.Index index =
      RichMessageUtils.indexAnchorsAndReferences(message);
    assertEquals("0", index.anchors.get("block"));
    assertEquals("1", index.anchors.get("inline"));
    assertEquals("1", index.references.get("note"));
  }

  @Test
  public void unknownAndMalformedBlocksDoNotDiscardReadableContent () {
    TdApi.RichMessage readable = new TdApi.RichMessage(new TdApi.PageBlock[] {
      new FutureBlock(new TdApi.RichTextPlain("future text"))
    }, false, true);
    assertEquals("future text", RichMessageUtils.toPlainText(readable));

    TdApi.RichMessage empty = new TdApi.RichMessage(new TdApi.PageBlock[] {
      new FutureBlock(null), null
    }, false, true);
    assertTrue(RichMessageUtils.toPlainText(empty).contains("Unsupported"));
    assertNotNull(RichMessageUtils.toHtml(empty));
  }

  private static final class FutureBlock extends TdApi.PageBlock {
    public TdApi.RichText text;

    FutureBlock (TdApi.RichText text) {
      this.text = text;
    }

    @Override
    public int getConstructor () {
      return 0x13572468;
    }
  }
}
