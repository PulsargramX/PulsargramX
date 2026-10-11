package org.thunderdog.challegram.data;

import java.util.Arrays;

/** Resolves actions against the latest server snapshot, including added or removed options. */
public final class PollOptionSelection {
  private PollOptionSelection () { }

  public static int find (String[] ids, String id) {
    if (id == null || id.isEmpty()) {
      return -1;
    }
    for (int i = 0; i < ids.length; i++) {
      if (id.equals(ids[i])) {
        return i;
      }
    }
    return -1;
  }

  public static int[] toggle (String[] ids, boolean[] chosen, String id, boolean multiple) {
    if (ids.length != chosen.length) {
      throw new IllegalArgumentException();
    }
    int target = find(ids, id);
    if (target < 0) {
      return null;
    }
    int[] result = new int[ids.length];
    int count = 0;
    for (int i = 0; i < ids.length; i++) {
      if (i == target ? !chosen[i] : multiple && chosen[i]) {
        result[count++] = i;
      }
    }
    return Arrays.copyOf(result, count);
  }
}
