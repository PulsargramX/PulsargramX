package org.thunderdog.challegram.util;

import android.text.InputFilter;
import android.text.Spanned;

/** Limits note edits by Unicode codepoints, accounting for the replaced selection. */
public final class NoteLengthFilter implements InputFilter {
  private final int limit;

  public NoteLengthFilter (int limit) {
    this.limit = limit;
  }

  @Override
  public CharSequence filter (CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
    int remaining = limit - Character.codePointCount(dest, 0, dstart) -
      Character.codePointCount(dest, dend, dest.length());
    if (Character.codePointCount(source, start, end) <= remaining) {
      return null;
    }
    int cut = remaining <= 0 ? start : Character.offsetByCodePoints(source, start, remaining);
    // Spanned subsequences keep spans and clip them to the accepted source range.
    return source.subSequence(start, cut);
  }
}
