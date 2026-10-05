# Rich message rendering comparison

## Baselines

The original implementation is commit `533c6eb5f10333842982da85eef14b6a192994e5`,
**Implement rich message rendering** (24 July 2026). It introduced `TGMessageRich`,
`RichMessageLayout`, the flattener, span solver, selection surface and export helpers.

The reference is official Telegram Android commit
[`dc780e81ed1261c369c27870e8e0999a1eb0b600`](https://github.com/DrKLO/Telegram/commit/dc780e81ed1261c369c27870e8e0999a1eb0b600),
**update to 12.10.5 (7105)**. Source was inspected in a temporary sparse checkout.
The main reference is the **chat bubble renderer**, not the rich message editor:

- [Official RichMessageLayout](https://github.com/DrKLO/Telegram/blob/dc780e81ed1261c369c27870e8e0999a1eb0b600/TMessagesProj/src/main/java/org/telegram/messenger/RichMessageLayout.java):
  `onTouchEvent`, `RichTableBlock`, `RichPreformattedBlock`, `RichMathBlock`,
  `RichSlideshowBlock`, `emitBlock`, `StyleSpan`, selection/export methods.
- [Official TableLayout](https://github.com/DrKLO/Telegram/blob/dc780e81ed1261c369c27870e8e0999a1eb0b600/TMessagesProj/src/main/java/org/telegram/ui/Components/TableLayout.java):
  cell measurement, intrinsic widths, grid drawing and row spans.
- [Official RichTableCell](https://github.com/DrKLO/Telegram/blob/dc780e81ed1261c369c27870e8e0999a1eb0b600/TMessagesProj/src/main/java/org/telegram/ui/iv/RichTableCell.java):
  editor uses a real HorizontalScrollView; it is not the implementation used by chat bubbles.

The fork uses TDLib page blocks; official Telegram uses MTProto `TL_iv` objects.
The rendering behavior can be aligned without replacing Telegram X's text/media pipeline.
The reference also contains newer protocol features that the checked-in TDLib API cannot represent.

## Bugs addressed

| Area | Original behavior | Change and reference behavior |
| --- | --- | --- |
| Table dragging | A plain cell returned false on DOWN, so MessageView never forwarded MOVE. Swipe-to-reply also ran before block dragging. | Claim scrollable DOWN events and give the captured rich block priority over message swiping. |
| Gesture ownership | Every event was hit-tested against its current Y position. Moving outside a block lost cancellation or dispatched to another block. | Retain the original block until UP/CANCEL, as the official renderer does. |
| Code and formula scrolling | Same lost-DOWN problem as tables; drag only, no momentum. | Shared horizontal gesture and fling handling for tables, code and formulas. Vertical movement remains available to the chat list. |
| Links while scrolling | A pressed link could remain pressed when a drag began. | Cancel the pressed text target before scrolling; preserve ordinary link taps. |
| RTL and resizing | Drawing and gesture offset directions disagreed. Old offsets could exceed the new width after remeasurement. | Match drag/fling direction to RTL drawing and clamp stored offsets on measurement. Code draws across its full content width. |
| Details headers | Text was shifted by 20dp without reserving width; RTL arrow direction was wrong; releasing after movement could toggle details. | Reserve the arrow gutter during measurement and require a tap that stays within touch slop and header bounds. |
| Slideshow and map gestures | Slideshow competed with reply swipes and retained the pressed media during dragging. Maps opened on UP without tracking a valid tap. | Capture slideshow gestures, cancel media clicks, respect RTL direction, and require a valid map tap. |
| List layout | Only the first text block got the marker gutter; tables/media ignored nesting. Wide numbering and combined checkboxes overlapped. | Carry list ancestry to every child; measure one shared gutter per list; apply nesting to all block types and separate checkbox/number positions. |
| Headings and body sizing | Instant View sizes were reused; heading levels were collapsed into three groups and were not automatically bold. Chat font changes were not part of the layout cache key. | Use the chat font size, bold headings, six relative heading sizes, and rebuild on chat font size changes. Official headings use base size +3 through -2. |
| Preformatted text | Selecting an Instant View style provider did not make the content monospace. | Explicitly wrap code content in fixed-width rich text. |
| Quote credits and anchors | Pull-quote credits were dropped; empty quote credits and invisible anchors added spacing; anchors could consume a list marker. | Emit nonempty credits and keep invisible anchors from affecting spacing or consuming markers. |
| Selection | Hidden parts of horizontally clipped content could be hit; nearest-cell selection considered only Y and used bounds recorded before drawing. | Restrict hits to the visible block, compare current caret geometry in both axes, and clip selection geometry to the viewport. Bitmap formulas no longer expose an undrawn text wrapper as selectable text. |
| Accessibility | Table descriptions omitted cells; only tables offered scrolling; partial-message Show more had no virtual button. | Read table contents, expose scrolling for code/formulas too, and expose Show more/Retry as an accessible button. |
| Lifecycle | Rebuilding details, translating or replacing partial content recreated audio nodes without attaching them to views already displaying the message. | Preserve view attachments across node/layout replacement; stop fling callbacks and release gesture interception on detach/destruction. |

## Remaining differences

This is not complete parity with the official client. The following were identified during the comparison
and remain outside the fixes above:

- **Code presentation:** official code blocks include syntax highlighting, a dedicated background and a
  scrollbar. The fork now uses monospace text and supports scrolling, but retains simpler decoration.
- **Table appearance and sizing:** the fork retains its shared span solver and existing minimum-width
  policy. Official Telegram measures natural widths, supports compact tables and has dedicated table
  theme colors. The checked-in TDLib `PageBlockTable` does not expose a compact flag.
- **Animated content:** thinking indicators, streamed text, details/quote expansion and slideshow
  transitions remain simpler than official Telegram's specialized renderers.
- **Copy fidelity:** selecting part of a rich text leaf still exports escaped plain text for that
  fragment; it does not slice and preserve all inline formatting. Full-message HTML export uses the
  existing structured serializer. Bitmap formulas are available in whole-message copy, not as
  individually selectable rendered glyphs.
- **Anchors/references:** the utility builds an index, but message-local navigation is not wired into
  the bubble click callback. Instant View's navigation machinery cannot simply be reused in chat.
- **New protocol features:** official rich documents, inline buttons and their interaction state need
  corresponding TDLib support before this renderer can implement them.
- **Authoring:** this implementation remains an incoming-message renderer; official Telegram also has
  a rich editor and conversion/composition flows.

## Validation

Run through the repository wrapper:

```sh
./scripts/gradle.sh :app:compileLatestArm64ReleaseJavaWithJavac --offline
./scripts/gradle.sh :app:testLatestArm64ReleaseUnitTest --offline \
  --tests 'org.thunderdog.challegram.data.RichMessage*Test' \
  --tests 'org.thunderdog.challegram.data.SpanTableSolverTest'
```

Result: Java compilation passed; all **15 focused tests passed**, with no failures or skips.

The focused suite covers flattening/state restoration, nested-list ancestry, non-text list children,
quote credit visibility, invisible anchors, span placement, serialization and asynchronous request
invalidation. The unit-test task also compiles the affected Java code. It does **not** simulate Android
touch dispatch or prove visual parity.

Device checks still needed (use the existing `RichMessageFixtures` as source data):

1. Drag and fling a wide table starting on plain text, an empty cell, a URL and a spoiler. Move outside
   the table before releasing. Verify that no reply swipe or link activation occurs while dragging.
2. Repeat with code, a wide formula and RTL messages. Rotate after scrolling to the far edge; content
   should remain reachable. A vertical drag over each block should still scroll the chat.
3. Tap a URL without moving, then long-press visible table text. Drag selection across cells and check
   that handles/highlights remain inside the table viewport.
4. Check all six headings at different chat font sizes. Check multiline list items, wide numbering,
   checkbox plus number, and tables/media nested inside lists and quotes.
5. Tap, drag off and cancel a details header and a map. Swipe a slideshow in both directions, including
   RTL. A drag should not open media or toggle details.
6. Expand details containing audio, translate a message, and load its full version while visible.
   Check media interaction, then recycle the message view while a fling is running.
7. With TalkBack, inspect table text, scroll an overflowing code block, and activate Show more/Retry.
