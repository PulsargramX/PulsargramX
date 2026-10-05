package org.thunderdog.challegram.telegram;

import java.util.HashSet;

/** Tracks changes made while a paginated topic snapshot is being fetched. */
final class ForumTopicChanges {
  final HashSet<Integer> changedTopicIds = new HashSet<>();
  final HashSet<Integer> deletedTopicIds = new HashSet<>();
  final HashSet<Integer> activityTopicIds = new HashSet<>();

  void changed (int topicId) {
    if (!deletedTopicIds.contains(topicId)) changedTopicIds.add(topicId);
  }

  void activity (int topicId) {
    changed(topicId);
    if (!deletedTopicIds.contains(topicId)) activityTopicIds.add(topicId);
  }

  void deleted (int topicId) {
    changedTopicIds.remove(topicId);
    activityTopicIds.remove(topicId);
    deletedTopicIds.add(topicId);
  }

  void acknowledge (int topicId, boolean matchesUpdate) {
    // UpdateForumTopic does not describe message activity. A page that matches
    // its read/draft fields can still predate a new message or a deletion.
    if (matchesUpdate && !activityTopicIds.contains(topicId)) changedTopicIds.remove(topicId);
  }

  Integer takeNext () {
    if (changedTopicIds.isEmpty()) return null;
    int topicId = changedTopicIds.iterator().next();
    changedTopicIds.remove(topicId);
    activityTopicIds.remove(topicId);
    return topicId;
  }
}
