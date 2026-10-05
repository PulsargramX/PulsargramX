# Forum topic state and verification

`Tdlib` owns the account's cached topic snapshots and unread-topic total.
`ForumTopicState` copies the mutable topic and message wrappers when snapshots
cross into the UI. The list controller owns its display/search lists on the UI
thread and reconciles rows by topic ID.

Topic order comes from TDLib's `ForumTopic.order`, descending. Pagination uses
all three `ForumTopics.nextOffset*` fields, independent of the visible sort order.
The approximate `totalCount` is not used to decide that a scan is complete.

Only a complete topic scan establishes an unread-topic total. Subsequent updates
change that total using the complete cache. The last total is saved per account
and chat, restored after a process restart, and refreshed in the background.
Before the first complete scan, chat rows use an unnumbered unread indicator
when needed; they never substitute an unread-message count for a topic count.
Failed or incomplete scans keep the last saved topic total.
Logging out removes the saved counts and startup rows for that account.

Dialog loading starts the topic scan before a forum is opened. The topic list
joins the scan's first-page request and can display that page before unread
reconciliation finishes. The first 100 rows' names, icons, order, mute state and
badges are also saved per account/chat, so process restarts can display the last
known rows immediately. Message previews and drafts refresh from TDLib; the small
startup cache is display-only and never establishes an unread-topic total.

Full scans coalesce concurrent requests and reconcile live changes before
publishing their result. Request identities reject responses after an account
restart; topic revisions reject responses superseded by newer updates.
TDLib emits `UpdateForumTopic` even when answering `GetForumTopic`. Identical
updates must not invalidate the request that produced them. Updates already
represented in a returned page are removed from the reconciliation queue.

## Automated checks

From the repository root:

```sh
./scripts/gradle.sh testLatestArm64ReleaseUnitTest --stacktrace
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p '*_test.py'
git diff --check
```

`ForumTopicStateTest` covers snapshot isolation, overlapping pages, unread
aggregation including hidden topics, more than 100 unread topics, and unknown
versus restored badge totals. Source checks also guard draft visibility and
preservation of icon playback across ordinary row bindings.
`ForumTopicListCacheTest` covers round trips, ordering, the row limit, empty
snapshots, and corrupt or incompatible data. Update-echo tests cover completion
without suppressing actual read-marker or draft changes.

## Device checks

These require a logged-in client and are not covered by JVM tests:

1. In each chat-list layout (two lines, three lines, large three lines), compare a
   topic draft with a regular-chat draft in light, dark, and custom themes. The
   draft prefix should use the same negative color; the body should use the
   normal preview color. The draft remains visible when the topic has unread
   messages, with its unread badge still shown.
2. Load a forum's unread count, force-stop, and reopen online and offline. The
   last complete numeric topic count should be available immediately; a later
   server result may legitimately change it. Repeat with a second account and
   a topic-enabled private bot.
   Leave forums unopened while the dialog list loads: counters must settle to
   numbers without navigation. Reopen a previously loaded forum offline after
   force-stop: cached rows must appear without a blocking loading screen.
3. Open a busy forum repeatedly. Check pinned order, draft-driven moves, scroll
   position, typing indicators, and rapid scrolling/recycling while updates arrive.
   Animated topic icons should continue playing through draft, unread-count and
   message updates; change an icon and verify that the new one loads correctly.
4. In a forum with more than 100 topics, scroll through multiple pages, enter
   search during a page load, and leave search. No duplicate or missing rows
   should result. Delete a topic while a refresh is pending and verify that it
   does not reappear after refresh, search, or reopening.
5. Read a topic on another client while this client shows the topic list or a
   filtered search. Check both the topic row and parent chat badge. Change a
   draft repeatedly while reads and new messages arrive.
6. Search rapidly for different queries and paginate message results. Each row
   must preview its matched message. With message filters enabled, scroll across
   hidden-message previews and switch accounts; previews must stay in their own
   topic and account.

Reference behavior: [official TopicsController](https://github.com/DrKLO/Telegram/blob/9552e5541e1274b9557c9832b204dbfcaf44b3dc/TMessagesProj/src/main/java/org/telegram/messenger/TopicsController.java)
and [official TopicsFragment](https://github.com/DrKLO/Telegram/blob/9552e5541e1274b9557c9832b204dbfcaf44b3dc/TMessagesProj/src/main/java/org/telegram/ui/TopicsFragment.java).
