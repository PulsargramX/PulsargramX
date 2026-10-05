package org.thunderdog.challegram.data;

import org.junit.Test;

import static org.junit.Assert.*;

public class RichMessageRequestGuardTest {
  @Test
  public void acceptsOnlyCurrentMessageAndRevision () {
    RichMessageRequestGuard guard = new RichMessageRequestGuard();
    RichMessageRequestGuard.Token token = guard.begin(10, 20, "rev-1");
    assertNotNull(token);
    assertNull(guard.begin(10, 20, "rev-1"));
    assertFalse(guard.complete(token, 10, 21, "rev-1"));
    assertTrue(guard.isInFlight());
    assertTrue(guard.complete(token, 10, 20, "rev-1"));
    assertFalse(guard.isInFlight());
  }

  @Test
  public void invalidationRejectsStaleResultAndFailureAllowsRetry () {
    RichMessageRequestGuard guard = new RichMessageRequestGuard();
    RichMessageRequestGuard.Token stale = guard.begin(1, 2, "old");
    guard.invalidate();
    assertFalse(guard.complete(stale, 1, 2, "old"));

    RichMessageRequestGuard.Token retry = guard.begin(1, 2, "new");
    assertNotNull(retry);
    assertTrue(guard.complete(retry, 1, 2, "new"));
    assertNotNull(guard.begin(1, 2, "new"));
  }
}
