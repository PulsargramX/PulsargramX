package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class RichMessageFlattenerTest {
  @Test
  public void structuralPathsAndCollapsedDetailsAreStable () {
    TdApi.RichMessage message = new TdApi.RichMessage(new TdApi.PageBlock[] {
      paragraph("root"),
      new TdApi.PageBlockDetails(new TdApi.RichTextPlain("details"),
        new TdApi.PageBlock[] {
          paragraph("hidden"),
          new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
            new TdApi.PageBlockListItem("A.", new TdApi.PageBlock[] {
              paragraph("nested")
            }, true, true, 1, "A")
          })
        }, false)
    }, true, true);

    RichMessageFlattener.Result result =
      RichMessageFlattener.flatten(message, null);
    assertEquals("0", result.nodes.get(0).path);
    assertEquals("1", result.nodes.get(1).path);
    assertEquals("1/0", result.nodes.get(2).path);
    assertEquals("1/1/0/0", result.nodes.get(3).path);
    assertFalse(result.nodes.get(2).visible);
    assertEquals("A.", result.nodes.get(3).listLabel);
    assertTrue(result.nodes.get(3).hasCheckbox);
    assertTrue(result.nodes.get(3).checked);
    assertTrue(message.isRtl);
  }

  @Test
  public void compatibleStateSurvivesAndIncompatibleStateResets () {
    TdApi.RichMessage original = rich(new TdApi.PageBlockDetails(
      new TdApi.RichTextPlain("details"),
      new TdApi.PageBlock[] { paragraph("child") }, false));
    RichMessageFlattener.Result first =
      RichMessageFlattener.flatten(original, null);
    first.states.get("0").detailsOpen = true;
    first.states.get("0").horizontalOffset = 42f;

    RichMessageFlattener.Result edited = RichMessageFlattener.flatten(
      rich(new TdApi.PageBlockDetails(new TdApi.RichTextPlain("edited"),
        new TdApi.PageBlock[] { paragraph("child 2") }, false)), first.states);
    assertTrue(edited.states.get("0").detailsOpen);
    assertEquals(42f, edited.states.get("0").horizontalOffset, 0f);

    RichMessageFlattener.Result incompatible = RichMessageFlattener.flatten(
      rich(paragraph("replacement")), edited.states);
    assertFalse(incompatible.states.get("0").detailsOpen);
    assertEquals(0f, incompatible.states.get("0").horizontalOffset, 0f);
  }

  @Test
  public void receiverKeysAreDeterministicAndSlotSeparated () {
    assertEquals(RichMessageFlattener.receiverKey("0/2/1", 0),
      RichMessageFlattener.receiverKey("0/2/1", 0));
    assertNotEquals(RichMessageFlattener.receiverKey("0/2/1", 0),
      RichMessageFlattener.receiverKey("0/2/1", 1));
    assertTrue(RichMessageFlattener.receiverKey("0/2/1", 0) >= 0);
  }

  @Test
  public void listContinuationAndNestedTablesRetainTheirListAncestors () {
    TdApi.PageBlockTable table = new TdApi.PageBlockTable();
    TdApi.PageBlockList inner = new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
      new TdApi.PageBlockListItem("1.", new TdApi.PageBlock[] { table }, false, false, 1, "1")
    });
    TdApi.PageBlockList outer = new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
      new TdApi.PageBlockListItem("100.", new TdApi.PageBlock[] {
        paragraph("first"), paragraph("continuation"), inner
      }, true, false, 100, "1"),
      new TdApi.PageBlockListItem("101.", new TdApi.PageBlock[] {
        paragraph("next item")
      }, false, false, 101, "1")
    });
    RichMessageFlattener.Result result = RichMessageFlattener.flatten(rich(outer), null);
    assertEquals(4, result.nodes.size());
    assertEquals("100.", result.nodes.get(0).listLabel);
    assertNull(result.nodes.get(1).listLabel);
    assertArrayEquals(new TdApi.PageBlockList[] { outer }, result.nodes.get(1).lists);
    assertArrayEquals(new TdApi.PageBlockList[] { outer, inner }, result.nodes.get(2).lists);
    assertEquals(RichMessageFlattener.KIND_TABLE, result.nodes.get(2).kind);
    assertEquals("1.", result.nodes.get(2).listLabel);
    assertArrayEquals(new TdApi.PageBlockList[] { outer }, result.nodes.get(3).lists);
    assertEquals("101.", result.nodes.get(3).listLabel);
  }

  @Test
  public void pullQuoteCreditKeepsVisibilityAndDoesNotRepeatListMarker () {
    TdApi.PageBlockPullQuote quote = new TdApi.PageBlockPullQuote(
      new TdApi.RichTextPlain("quotation"), new TdApi.RichTextPlain("attribution"));
    TdApi.PageBlockList list = new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
      new TdApi.PageBlockListItem("1.", new TdApi.PageBlock[] { quote }, false, false, 1, "1")
    });
    RichMessageFlattener.Result result = RichMessageFlattener.flatten(rich(
      new TdApi.PageBlockDetails(new TdApi.RichTextPlain("details"),
        new TdApi.PageBlock[] { list }, false)), null);
    assertEquals(3, result.nodes.size());
    assertEquals("quotation", RichMessageUtils.richTextToPlain(result.nodes.get(1).text));
    assertEquals("attribution", RichMessageUtils.richTextToPlain(result.nodes.get(2).text));
    assertEquals(result.nodes.get(1).path + "/credit", result.nodes.get(2).path);
    assertFalse(result.nodes.get(2).visible);
    assertNull(result.nodes.get(2).listLabel);
    assertArrayEquals(result.nodes.get(1).lists, result.nodes.get(2).lists);

    result.states.get("0").detailsOpen = true;
    RichMessageFlattener.Result expanded = RichMessageFlattener.flatten(rich(
      new TdApi.PageBlockDetails(new TdApi.RichTextPlain("details"),
        new TdApi.PageBlock[] { list }, false)), result.states);
    assertTrue(expanded.nodes.get(2).visible);
  }

  @Test
  public void emptyPullQuoteCreditDoesNotCreateAnExtraBlock () {
    RichMessageFlattener.Result result = RichMessageFlattener.flatten(rich(
      new TdApi.PageBlockPullQuote(new TdApi.RichTextPlain("quote"),
        new TdApi.RichTextPlain(""))), null);
    assertEquals(1, result.nodes.size());
  }

  @Test
  public void invisibleAnchorDoesNotConsumeListMarker () {
    TdApi.PageBlockList list = new TdApi.PageBlockList(new TdApi.PageBlockListItem[] {
      new TdApi.PageBlockListItem("1.", new TdApi.PageBlock[] {
        new TdApi.PageBlockAnchor("target"), paragraph("item")
      }, false, false, 1, "1")
    });
    RichMessageFlattener.Result result = RichMessageFlattener.flatten(rich(list), null);
    assertNull(result.nodes.get(0).listLabel);
    assertEquals("1.", result.nodes.get(1).listLabel);
  }

  private static TdApi.PageBlockParagraph paragraph (String value) {
    return new TdApi.PageBlockParagraph(new TdApi.RichTextPlain(value));
  }

  private static TdApi.RichMessage rich (TdApi.PageBlock block) {
    return new TdApi.RichMessage(new TdApi.PageBlock[] { block }, false, true);
  }
}
