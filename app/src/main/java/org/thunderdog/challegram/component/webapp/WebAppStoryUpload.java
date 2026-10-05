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
package org.thunderdog.challegram.component.webapp;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.CleanupStartupDelegate;
import org.thunderdog.challegram.telegram.StoryListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;

import java.io.File;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Keeps TDLib upload inputs alive independently of a closed Mini App document. */
final class WebAppStoryUpload implements StoryListener, CleanupStartupDelegate {
  // TDLib listeners use weak references. Pending uploads deliberately survive their UI owner.
  private static final Set<WebAppStoryUpload> ACTIVE = new HashSet<>();
  private final Tdlib tdlib;
  private final long userId;
  private final long chatId;
  private final File file;
  private Consumer<Boolean> callback;
  private boolean finished;
  private int storyId;
  private boolean identified;
  private final Map<Integer, Boolean> earlyResults = new HashMap<>();

  WebAppStoryUpload (Tdlib tdlib, TdApi.PostStory request, File file, Consumer<Boolean> callback) {
    this.tdlib = tdlib;
    this.userId = tdlib.myUserId();
    this.chatId = request.chatId;
    this.file = file;
    this.callback = callback;
    ACTIVE.add(this);
    tdlib.listeners().subscribeForGlobalUpdates(this);
    tdlib.listeners().addCleanupListener(this);
    if (!tdlib.isAuthorized() || tdlib.myUserId() != userId) { finish(false); return; }
    tdlib.client().send(request, result -> UI.post(() -> {
      if (finished) return;
      if (result instanceof TdApi.Story) {
        TdApi.Story story = (TdApi.Story) result;
        storyId = story.id; identified = true;
        Boolean terminal = earlyResults.get(storyId);
        earlyResults.clear();
        if (terminal != null) finish(terminal);
        else if (!story.isBeingPosted) finish(true);
      } else {
        // No pending story owns the file when the request itself was rejected.
        finish(false);
      }
    }));
  }
  void detach () { callback = null; }
  private void report (boolean success) {
    Consumer<Boolean> listener = callback; callback = null;
    if (listener != null) listener.accept(success);
  }
  private void finish (boolean success) {
    if (finished) return;
    finished = true;
    tdlib.listeners().unsubscribeFromGlobalUpdates(this);
    tdlib.listeners().removeCleanupListener(this);
    ACTIVE.remove(this);
    earlyResults.clear();
    file.delete();
    report(success);
  }
  private void terminalResult (long chatId, int id, boolean success) {
    if (finished || this.chatId != chatId) return;
    if (!identified) {
      // TDLib starts sending before resolving PostStory; a terminal update may arrive first.
      earlyResults.put(id, success);
    } else if (storyId == id) finish(success);
  }
  @Override public void onStoryUpdated (TdApi.Story story) { }
  @Override public void onStoryDeleted (long chatId, int storyId) {
    UI.post(() -> terminalResult(chatId, storyId, false));
  }
  @Override public void onStorySendSucceeded (TdApi.Story story, int oldStoryId) {
    UI.post(() -> terminalResult(story.posterChatId, oldStoryId, true));
  }
  @Override public void onStorySendFailed (TdApi.Story story, TdApi.Error error, TdApi.CanPostStoryResult type) {
    // Unlike failed messages, TDLib removes a failed posted story from its pending queue.
    UI.post(() -> terminalResult(story.posterChatId, story.id, false));
  }
  @Override public void onPerformUserCleanup () { UI.post(() -> finish(false)); }
}
