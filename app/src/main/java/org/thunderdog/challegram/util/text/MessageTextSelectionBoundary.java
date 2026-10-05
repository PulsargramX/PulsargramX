/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.util.text;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Locale;

/**
 * Cached UTF-16 boundaries used by message text selection.
 *
 * {@link BreakIterator#getCharacterInstance(Locale)} is the baseline. Extra coalescing keeps
 * modern emoji sequences intact on Android versions whose character iterator predates them.
 */
public final class MessageTextSelectionBoundary {
  public static final class Range {
    public int start;
    public int end;

    public Range () { }

    public Range (int start, int end) {
      set(start, end);
    }

    public Range set (int start, int end) {
      this.start = start;
      this.end = end;
      return this;
    }

    public boolean isEmpty () {
      return start >= end;
    }
  }

  private final String text;
  private final int[] boundaries;
  private final @Nullable TdApi.TextEntity[] entities;
  private final BreakIterator words;

  public MessageTextSelectionBoundary (
    @NonNull String text,
    @Nullable TdApi.TextEntity[] entities,
    @NonNull Locale locale
  ) {
    this.text = text;
    this.entities = entities;
    this.words = BreakIterator.getWordInstance(locale);
    this.words.setText(text);

    boolean[] legal = new boolean[text.length() + 1];
    BreakIterator characters = BreakIterator.getCharacterInstance(locale);
    characters.setText(text);
    for (int boundary = characters.first();
         boundary != BreakIterator.DONE;
         boundary = characters.next()) {
      legal[boundary] = true;
    }
    legal[0] = true;
    legal[text.length()] = true;

    coalesceEmojiBoundaries(text, legal);
    coalesceCustomEmojiBoundaries(entities, legal);

    int count = 0;
    for (boolean boundary : legal) {
      if (boundary) {
        count++;
      }
    }
    boundaries = new int[count];
    int index = 0;
    for (int offset = 0; offset < legal.length; offset++) {
      if (legal[offset]) {
        boundaries[index++] = offset;
      }
    }
  }

  public int length () {
    return text.length();
  }

  public int previous (int offset) {
    offset = clampOffset(offset);
    int index = Arrays.binarySearch(boundaries, offset);
    if (index >= 0) {
      return index > 0 ? boundaries[index - 1] : boundaries[0];
    }
    index = -index - 1;
    return index > 0 ? boundaries[index - 1] : boundaries[0];
  }

  public int next (int offset) {
    offset = clampOffset(offset);
    int index = Arrays.binarySearch(boundaries, offset);
    if (index >= 0) {
      return index + 1 < boundaries.length ? boundaries[index + 1] :
        boundaries[boundaries.length - 1];
    }
    index = -index - 1;
    return index < boundaries.length ? boundaries[index] : boundaries[boundaries.length - 1];
  }

  public int nearest (int offset) {
    offset = clampOffset(offset);
    int index = Arrays.binarySearch(boundaries, offset);
    if (index >= 0) {
      return boundaries[index];
    }
    index = -index - 1;
    if (index == 0) {
      return boundaries[0];
    }
    if (index == boundaries.length) {
      return boundaries[boundaries.length - 1];
    }
    int before = boundaries[index - 1];
    int after = boundaries[index];
    return offset - before <= after - offset ? before : after;
  }

  public int atOrBefore (int offset) {
    return boundaryAtOrBefore(clampOffset(offset));
  }

  public Range normalize (int start, int end, @NonNull Range out) {
    start = nearest(start);
    end = nearest(end);
    if (start > end) {
      int swap = start;
      start = end;
      end = swap;
    }
    return out.set(start, end);
  }

  public Range rangeAt (int offset, @NonNull Range out) {
    if (text.isEmpty()) {
      return out.set(0, 0);
    }
    int characterOffset = characterOffset(offset);
    if (characterOffset < 0 || Character.isWhitespace(text.codePointAt(characterOffset))) {
      return out.set(0, 0);
    }

    TdApi.TextEntity customEmoji = findCustomEmoji(characterOffset);
    if (customEmoji != null) {
      return out.set(
        nearest(customEmoji.offset),
        nearest(customEmoji.offset + customEmoji.length)
      );
    }

    int wordStart = words.preceding(characterOffset + 1);
    int wordEnd = words.following(characterOffset);
    if (wordStart != BreakIterator.DONE && wordEnd != BreakIterator.DONE &&
        containsLetterOrDigit(wordStart, wordEnd)) {
      return out.set(nearest(wordStart), nearest(wordEnd));
    }

    int start = boundaryAtOrBefore(characterOffset);
    int end = next(start);
    return out.set(start, end);
  }

  private int characterOffset (int offset) {
    offset = clampOffset(offset);
    if (offset == text.length()) {
      return previous(offset);
    }
    if (offset > 0 && Character.isLowSurrogate(text.charAt(offset))) {
      offset--;
    }
    return offset;
  }

  private int boundaryAtOrBefore (int offset) {
    int index = Arrays.binarySearch(boundaries, clampOffset(offset));
    if (index >= 0) {
      return boundaries[index];
    }
    index = -index - 1;
    return index > 0 ? boundaries[index - 1] : boundaries[0];
  }

  private int clampOffset (int offset) {
    return Math.max(0, Math.min(text.length(), offset));
  }

  private boolean containsLetterOrDigit (int start, int end) {
    for (int offset = start; offset < end; ) {
      int codePoint = text.codePointAt(offset);
      if (Character.isLetterOrDigit(codePoint)) {
        return true;
      }
      offset += Character.charCount(codePoint);
    }
    return false;
  }

  private @Nullable TdApi.TextEntity findCustomEmoji (int offset) {
    if (entities != null) {
      for (TdApi.TextEntity entity : entities) {
        if (entity.type.getConstructor() == TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR &&
            offset >= entity.offset && offset < entity.offset + entity.length) {
          return entity;
        }
      }
    }
    return null;
  }

  private static void coalesceCustomEmojiBoundaries (
    @Nullable TdApi.TextEntity[] entities,
    @NonNull boolean[] legal
  ) {
    if (entities == null) {
      return;
    }
    for (TdApi.TextEntity entity : entities) {
      if (entity.type.getConstructor() != TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR) {
        continue;
      }
      int start = Math.max(0, entity.offset);
      int end = Math.min(legal.length - 1, entity.offset + entity.length);
      for (int offset = start + 1; offset < end; offset++) {
        legal[offset] = false;
      }
    }
  }

  private static void coalesceEmojiBoundaries (
    @NonNull String text,
    @NonNull boolean[] legal
  ) {
    int regionalIndicatorCount = 0;
    int previousCodePoint = -1;
    for (int offset = 0; offset < text.length(); ) {
      int codePoint = text.codePointAt(offset);
      int nextOffset = offset + Character.charCount(codePoint);

      if (offset > 0 && (
        isCombiningMark(codePoint) ||
        isVariationSelector(codePoint) ||
        isEmojiModifier(codePoint) ||
        codePoint == 0x20e3 ||
        codePoint == 0x200d ||
        previousCodePoint == 0x200d
      )) {
        legal[offset] = false;
      }
      if (codePoint == 0x200d && nextOffset < legal.length) {
        legal[nextOffset] = false;
      }

      if (isRegionalIndicator(codePoint)) {
        if ((regionalIndicatorCount & 1) == 1) {
          legal[offset] = false;
        }
        regionalIndicatorCount++;
      } else {
        regionalIndicatorCount = 0;
      }

      previousCodePoint = codePoint;
      offset = nextOffset;
    }
  }

  private static boolean isCombiningMark (int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK ||
      type == Character.COMBINING_SPACING_MARK ||
      type == Character.ENCLOSING_MARK;
  }

  private static boolean isVariationSelector (int codePoint) {
    return codePoint >= 0xfe00 && codePoint <= 0xfe0f ||
      codePoint >= 0xe0100 && codePoint <= 0xe01ef;
  }

  private static boolean isEmojiModifier (int codePoint) {
    return codePoint >= 0x1f3fb && codePoint <= 0x1f3ff;
  }

  private static boolean isRegionalIndicator (int codePoint) {
    return codePoint >= 0x1f1e6 && codePoint <= 0x1f1ff;
  }
}
