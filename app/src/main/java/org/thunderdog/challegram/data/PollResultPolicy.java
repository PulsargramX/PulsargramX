package org.thunderdog.challegram.data;

/** Server availability remains authoritative when creators preview an unanswered poll. */
public final class PollResultPolicy {
  private PollResultPolicy () { }

  public static boolean canPreview (boolean canSeeResults, boolean hasAnswer, boolean closed) {
    return canSeeResults && !hasAnswer && !closed;
  }

  public static boolean canGetVoters (boolean anonymous, boolean canSeeResults, boolean canGetVoters) {
    return !anonymous && canSeeResults && canGetVoters;
  }
}
