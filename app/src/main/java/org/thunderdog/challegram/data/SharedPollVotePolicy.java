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
 *
 */
package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;

import java.util.Arrays;
import java.util.List;

/** Resolves an archive selection against the current server option IDs and voting permissions. */
public final class SharedPollVotePolicy {
  private SharedPollVotePolicy () { }

  public static boolean hasAnswer (TdApi.Poll poll) {
    for (TdApi.PollOption option : poll.options) {
      if (option.isChosen) return true;
    }
    return false;
  }

  public static boolean canVote (TdApi.Poll poll) {
    if (poll.isClosed || poll.voteRestrictionReason != null ||
        (hasAnswer(poll) && !poll.allowsRevoting)) return false;
    for (TdApi.PollOption option : poll.options) {
      if (option.isBeingChosen) return false;
    }
    return true;
  }

  public static int[] displayOrder (TdApi.Poll poll) {
    int[] order = new int[poll.options.length];
    boolean[] seen = new boolean[order.length];
    int count = 0;
    if (poll.optionOrder != null) for (int index : poll.optionOrder) {
      if (index >= 0 && index < order.length && !seen[index]) {
        seen[index] = true;
        order[count++] = index;
      }
    }
    for (int i = 0; i < order.length; i++) if (!seen[i]) order[count++] = i;
    return order;
  }

  public static int[] resolve (TdApi.Poll poll, List<String> selectedIds, boolean retract) {
    if (!canVote(poll) || (retract ? !hasAnswer(poll) || !selectedIds.isEmpty() : selectedIds.isEmpty())) return null;
    if (!poll.allowsMultipleAnswers && selectedIds.size() > 1) return null;
    int[] indexes = new int[selectedIds.size()];
    int count = 0;
    for (String id : selectedIds) {
      if (id == null || id.isEmpty()) return null;
      int index = -1;
      for (int i = 0; i < poll.options.length; i++) {
        if (id.equals(poll.options[i].id)) { index = i; break; }
      }
      if (index < 0) return null;
      for (int i = 0; i < count; i++) if (indexes[i] == index) return null;
      indexes[count++] = index;
    }
    return Arrays.copyOf(indexes, count);
  }
}
