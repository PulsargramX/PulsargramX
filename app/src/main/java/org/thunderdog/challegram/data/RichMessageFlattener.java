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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a TDLib page-block tree to message-bubble render records.
 *
 * <p>Paths are based only on structure, never on content or object identity.
 * This makes them suitable for receiver keys, accessibility IDs, and local
 * state restoration.</p>
 */
public final class RichMessageFlattener {
  private RichMessageFlattener () { }

  public static final int KIND_TEXT = 1;
  public static final int KIND_DIVIDER = 2;
  public static final int KIND_FORMULA = 3;
  public static final int KIND_DETAILS = 4;
  public static final int KIND_TABLE = 5;
  public static final int KIND_MEDIA = 6;
  public static final int KIND_ANCHOR = 7;
  public static final int KIND_UNKNOWN = 8;

  public static final class State {
    int kind;
    int constructor;
    public boolean detailsOpen;
    public float horizontalOffset;
    public int slideshowIndex;
    public long playbackPosition;

    public State copy () {
      State result = new State();
      result.kind = kind;
      result.constructor = constructor;
      result.detailsOpen = detailsOpen;
      result.horizontalOffset = horizontalOffset;
      result.slideshowIndex = slideshowIndex;
      result.playbackPosition = playbackPosition;
      return result;
    }
  }

  public static final class Node {
    public final @NonNull String path;
    public final int kind;
    public final @NonNull TdApi.PageBlock block;
    public final @Nullable TdApi.RichText text;
    public final int nestingDepth;
    public final int quoteDepth;
    public final TdApi.PageBlockList[] lists;
    public final @Nullable String detailsPath;
    public final boolean visible;
    public final @Nullable String listLabel;
    public final boolean hasCheckbox;
    public final boolean checked;
    public final @NonNull State state;

    private Node (String path, int kind, TdApi.PageBlock block, @Nullable TdApi.RichText text,
                  Context context, @Nullable ListMarker listMarker, State state) {
      this.path = path;
      this.kind = kind;
      this.block = block;
      this.text = text;
      this.nestingDepth = context.nestingDepth;
      this.quoteDepth = context.quoteDepth;
      this.lists = context.lists.toArray(new TdApi.PageBlockList[0]);
      this.detailsPath = context.detailsPath;
      this.visible = context.visible;
      this.listLabel = listMarker != null ? listMarker.label : null;
      this.hasCheckbox = listMarker != null && listMarker.hasCheckbox;
      this.checked = listMarker != null && listMarker.checked;
      this.state = state;
    }

    public boolean isCompatibleWith (@Nullable Node other) {
      return other != null && kind == other.kind &&
        block.getConstructor() == other.block.getConstructor();
    }
  }

  public static final class Result {
    public final @NonNull List<Node> nodes;
    public final @NonNull Map<String, State> states;

    private Result (List<Node> nodes, Map<String, State> states) {
      this.nodes = Collections.unmodifiableList(nodes);
      this.states = Collections.unmodifiableMap(states);
    }
  }

  private static final class Context {
    int nestingDepth;
    int quoteDepth;
    final ArrayList<TdApi.PageBlockList> lists = new ArrayList<>();
    @Nullable String detailsPath;
    boolean visible = true;

    Context copy () {
      Context result = new Context();
      result.nestingDepth = nestingDepth;
      result.quoteDepth = quoteDepth;
      result.lists.addAll(lists);
      result.detailsPath = detailsPath;
      result.visible = visible;
      return result;
    }
  }

  private static final class ListMarker {
    final String label;
    final boolean hasCheckbox;
    final boolean checked;
    boolean emitted;

    ListMarker (String label, boolean hasCheckbox, boolean checked) {
      this.label = label;
      this.hasCheckbox = hasCheckbox;
      this.checked = checked;
    }
  }

  public static Result flatten (@Nullable TdApi.RichMessage message,
                                @Nullable Map<String, State> previousState) {
    ArrayList<Node> nodes = new ArrayList<>();
    HashMap<String, State> states = new HashMap<>();
    if (message != null) {
      flattenBlocks(message.blocks, "", new Context(), null, previousState, states, nodes);
    }
    return new Result(nodes, states);
  }

  public static long receiverKey (@NonNull String path, int slot) {
    // FNV-1a provides stable, well-distributed positive keys without allocations.
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < path.length(); index++) {
      hash ^= path.charAt(index);
      hash *= 0x100000001b3L;
    }
    hash ^= slot;
    hash *= 0x100000001b3L;
    return hash & Long.MAX_VALUE;
  }

  private static void flattenBlocks (@Nullable TdApi.PageBlock[] blocks, String parentPath,
                                     Context context, @Nullable ListMarker marker,
                                     @Nullable Map<String, State> previousState,
                                     Map<String, State> states, List<Node> out) {
    if (blocks == null) {
      return;
    }
    for (int index = 0; index < blocks.length; index++) {
      TdApi.PageBlock block = blocks[index];
      if (block == null) {
        continue;
      }
      String path = childPath(parentPath, index);
      flattenBlock(block, path, context, marker, previousState, states, out);
    }
  }

  private static void flattenBlock (TdApi.PageBlock block, String path, Context context,
                                    @Nullable ListMarker marker,
                                    @Nullable Map<String, State> previousState,
                                    Map<String, State> states, List<Node> out) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockCover.CONSTRUCTOR: {
        TdApi.PageBlock cover = ((TdApi.PageBlockCover) block).cover;
        if (cover != null) {
          flattenBlock(cover, childPath(path, 0), context, marker, previousState, states, out);
        }
        return;
      }
      case TdApi.PageBlockList.CONSTRUCTOR: {
        TdApi.PageBlockListItem[] items = ((TdApi.PageBlockList) block).items;
        if (items == null) {
          return;
        }
        Context childContext = context.copy();
        childContext.nestingDepth++;
        childContext.lists.add((TdApi.PageBlockList) block);
        for (int itemIndex = 0; itemIndex < items.length; itemIndex++) {
          TdApi.PageBlockListItem item = items[itemIndex];
          if (item == null) {
            continue;
          }
          ListMarker childMarker = new ListMarker(listLabel(item), item.hasCheckbox, item.isChecked);
          flattenBlocks(item.blocks, childPath(path, itemIndex), childContext, childMarker,
            previousState, states, out);
        }
        return;
      }
      case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockBlockQuote quote = (TdApi.PageBlockBlockQuote) block;
        Context quoteContext = context.copy();
        quoteContext.nestingDepth++;
        quoteContext.quoteDepth++;
        flattenBlocks(quote.blocks, path, quoteContext, marker, previousState, states, out);
        if (quote.credit != null && !RichMessageUtils.richTextToPlain(quote.credit).isEmpty()) {
          addNode(path + "/credit", KIND_TEXT, block, quote.credit, quoteContext,
            marker, previousState, states, out);
        }
        return;
      }
      case TdApi.PageBlockPullQuote.CONSTRUCTOR: {
        TdApi.PageBlockPullQuote quote = (TdApi.PageBlockPullQuote) block;
        addNode(path, KIND_TEXT, block, quote.text, context, marker, previousState, states, out);
        if (quote.credit != null && !RichMessageUtils.richTextToPlain(quote.credit).isEmpty()) {
          addNode(path + "/credit", KIND_TEXT, block, quote.credit, context, null,
            previousState, states, out);
        }
        return;
      }
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
        State state = restoredState(path, KIND_DETAILS, block, details.isOpen, previousState);
        states.put(path, state);
        addNodeWithState(path, KIND_DETAILS, block, details.header, context, marker, state, out);

        Context childContext = context.copy();
        childContext.nestingDepth++;
        childContext.detailsPath = path;
        childContext.visible = context.visible && state.detailsOpen;
        flattenBlocks(details.blocks, path, childContext, null, previousState, states, out);
        return;
      }
      case TdApi.PageBlockCollage.CONSTRUCTOR:
      case TdApi.PageBlockSlideshow.CONSTRUCTOR:
        addNode(path, KIND_MEDIA, block, null, context, marker, previousState, states, out);
        addMediaCaption(block, path, context, previousState, states, out);
        return;
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
      case TdApi.PageBlockVideo.CONSTRUCTOR:
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
      case TdApi.PageBlockAudio.CONSTRUCTOR:
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR:
      case TdApi.PageBlockMap.CONSTRUCTOR:
        addNode(path, KIND_MEDIA, block, null, context, marker, previousState, states, out);
        addMediaCaption(block, path, context, previousState, states, out);
        return;
      case TdApi.PageBlockTable.CONSTRUCTOR:
        addNode(path, KIND_TABLE, block, ((TdApi.PageBlockTable) block).caption,
          context, marker, previousState, states, out);
        return;
      case TdApi.PageBlockDivider.CONSTRUCTOR:
        addNode(path, KIND_DIVIDER, block, null, context, marker, previousState, states, out);
        return;
      case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR:
        addNode(path, KIND_FORMULA, block, null, context, marker, previousState, states, out);
        return;
      case TdApi.PageBlockAnchor.CONSTRUCTOR:
        addNode(path, KIND_ANCHOR, block, null, context, marker, previousState, states, out);
        return;
      default: {
        TdApi.RichText text = textOf(block);
        addNode(path, text != null ? KIND_TEXT : KIND_UNKNOWN, block, text, context,
          marker, previousState, states, out);
      }
    }
  }

  private static void addMediaCaption (TdApi.PageBlock block, String path, Context context,
                                       @Nullable Map<String, State> previousState,
                                       Map<String, State> states, List<Node> out) {
    TdApi.PageBlockCaption caption = captionOf(block);
    if (caption == null) {
      return;
    }
    if (caption.text != null && !RichMessageUtils.richTextToPlain(caption.text).isEmpty()) {
      addNode(path + "/caption", KIND_TEXT, block, caption.text, context, null,
        previousState, states, out);
    }
    if (caption.credit != null && !RichMessageUtils.richTextToPlain(caption.credit).isEmpty()) {
      addNode(path + "/credit", KIND_TEXT, block, caption.credit, context, null,
        previousState, states, out);
    }
  }

  private static void addNode (String path, int kind, TdApi.PageBlock block,
                               @Nullable TdApi.RichText text, Context context,
                               @Nullable ListMarker marker,
                               @Nullable Map<String, State> previousState,
                               Map<String, State> states, List<Node> out) {
    State state = restoredState(path, kind, block, false, previousState);
    states.put(path, state);
    addNodeWithState(path, kind, block, text, context, marker, state, out);
  }

  private static void addNodeWithState (String path, int kind, TdApi.PageBlock block,
                                        @Nullable TdApi.RichText text, Context context,
                                        @Nullable ListMarker marker, State state,
                                        List<Node> out) {
    ListMarker effectiveMarker = kind != KIND_ANCHOR && marker != null && !marker.emitted ? marker : null;
    out.add(new Node(path, kind, block, text, context, effectiveMarker, state));
    if (effectiveMarker != null) {
      effectiveMarker.emitted = true;
    }
  }

  private static State restoredState (String path, int kind, TdApi.PageBlock block,
                                      boolean defaultOpen,
                                      @Nullable Map<String, State> previousState) {
    State previous = previousState != null ? previousState.get(path) : null;
    boolean compatible = previous != null && previous.kind == kind &&
      previous.constructor == block.getConstructor();
    State result = compatible ? previous.copy() : new State();
    result.kind = kind;
    result.constructor = block.getConstructor();
    if (!compatible && kind == KIND_DETAILS) {
      result.detailsOpen = defaultOpen;
    }
    return result;
  }

  static String listLabel (TdApi.PageBlockListItem item) {
    if (item.label != null && !item.label.isEmpty()) {
      return item.label;
    }
    if (item.value != 0 || item.type != null && !item.type.isEmpty()) {
      return formatValue(item.value > 0 ? item.value : 1, item.type) + ".";
    }
    return item.hasCheckbox ? "" : "•";
  }

  private static String formatValue (int value, @Nullable String type) {
    if ("a".equals(type) || "A".equals(type)) {
      StringBuilder out = new StringBuilder();
      do {
        value--;
        out.append((char) ('a' + value % 26));
        value /= 26;
      } while (value > 0);
      String result = out.reverse().toString();
      return "A".equals(type) ? result.toUpperCase(java.util.Locale.US) : result;
    }
    if ("i".equals(type) || "I".equals(type)) {
      String result = roman(value);
      return "i".equals(type) ? result.toLowerCase(java.util.Locale.US) : result;
    }
    return Integer.toString(value);
  }

  private static String roman (int value) {
    if (value <= 0 || value > 3999) {
      return Integer.toString(value);
    }
    int[] values = { 1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1 };
    String[] digits = { "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I" };
    StringBuilder out = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      while (value >= values[index]) {
        out.append(digits[index]);
        value -= values[index];
      }
    }
    return out.toString();
  }

  private static @Nullable TdApi.RichText textOf (TdApi.PageBlock block) {
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
    }
    return null;
  }

  static @Nullable TdApi.PageBlockCaption captionOf (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR: return ((TdApi.PageBlockPhoto) block).caption;
      case TdApi.PageBlockVideo.CONSTRUCTOR: return ((TdApi.PageBlockVideo) block).caption;
      case TdApi.PageBlockAnimation.CONSTRUCTOR: return ((TdApi.PageBlockAnimation) block).caption;
      case TdApi.PageBlockAudio.CONSTRUCTOR: return ((TdApi.PageBlockAudio) block).caption;
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR: return ((TdApi.PageBlockVoiceNote) block).caption;
      case TdApi.PageBlockMap.CONSTRUCTOR: return ((TdApi.PageBlockMap) block).caption;
      case TdApi.PageBlockCollage.CONSTRUCTOR: return ((TdApi.PageBlockCollage) block).caption;
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: return ((TdApi.PageBlockSlideshow) block).caption;
    }
    return null;
  }

  private static String childPath (String parent, int child) {
    return parent.isEmpty() ? Integer.toString(child) : parent + "/" + child;
  }
}
