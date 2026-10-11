package org.thunderdog.challegram.ui;

import java.util.ArrayList;
import java.util.List;

/** Poll draft constraints, independent of editor views and Android lifecycle. */
public final class PollDraftRules {
  private PollDraftRules () { }

  public static boolean validAnswers (int answerCount, int correctCount, boolean quiz, boolean multiple) {
    return answerCount >= 2 && (!quiz || correctCount == 1 || multiple && correctCount > 1);
  }

  public static boolean validDuration (int period, int closeDate, long now, long scheduledDate, boolean whenOnline, int maximum) {
    return period >= 0 && period <= maximum && (period == 0 || closeDate == 0) &&
      (closeDate == 0 || !whenOnline && closeDate > Math.max(now, scheduledDate) && (long) closeDate - now <= maximum);
  }

  /** Ranges preserve the original styled text, including custom emoji spans. */
  public static List<int[]> splitLines (CharSequence text, int start, int end) {
    List<int[]> ranges = new ArrayList<>();
    int lineStart = start;
    for (int i = start; i <= end; i++) {
      if (i == end || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
        int first = lineStart;
        int last = i;
        while (first < last && Character.isWhitespace(text.charAt(first))) first++;
        while (last > first && Character.isWhitespace(text.charAt(last - 1))) last--;
        if (first < last) ranges.add(new int[] {first, last});
        lineStart = i + 1;
      }
    }
    return ranges;
  }
}
