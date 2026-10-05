package org.thunderdog.challegram.telegram;

import org.junit.Test;

import static org.junit.Assert.*;

public class ForumTopicChangesTest {
  @Test
  public void pageEchoCannotHideNewMessageOrLastMessageDeletion () {
    ForumTopicChanges changes = new ForumTopicChanges();
    changes.activity(10);
    changes.acknowledge(10, true);
    assertEquals(Integer.valueOf(10), changes.takeNext());
    assertNull(changes.takeNext());
  }

  @Test
  public void activityDuringTargetedFetchRequiresAnotherReconciliation () {
    ForumTopicChanges changes = new ForumTopicChanges();
    changes.activity(10);
    assertEquals(Integer.valueOf(10), changes.takeNext());
    changes.activity(10);
    changes.acknowledge(10, true);
    assertEquals(Integer.valueOf(10), changes.takeNext());
    // The next response's own metadata update must not cause an echo loop.
    changes.changed(10);
    changes.acknowledge(10, true);
    assertNull(changes.takeNext());
  }

  @Test
  public void mismatchedReadOrDraftStateMustStillBeReconciled () {
    ForumTopicChanges changes = new ForumTopicChanges();
    changes.changed(10);
    changes.acknowledge(10, false);
    assertEquals(Integer.valueOf(10), changes.takeNext());
  }

  @Test
  public void deletedTopicCannotBeResurrectedByInFlightUpdates () {
    ForumTopicChanges changes = new ForumTopicChanges();
    changes.activity(10);
    changes.deleted(10);
    changes.activity(10);
    changes.changed(10);
    changes.acknowledge(10, true);
    assertNull(changes.takeNext());
    assertTrue(changes.deletedTopicIds.contains(10));
    assertFalse(changes.activityTopicIds.contains(10));
  }

  @Test
  public void burstOfUpdatesIsCoalescedPerTopic () {
    ForumTopicChanges changes = new ForumTopicChanges();
    for (int i = 0; i < 1000; i++) changes.activity(10);
    assertEquals(Integer.valueOf(10), changes.takeNext());
    assertNull(changes.takeNext());
  }
}
