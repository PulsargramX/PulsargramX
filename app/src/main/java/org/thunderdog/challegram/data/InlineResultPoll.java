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
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MediaPreview;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Screen;

/** A shared poll row that retains its source message for navigation and message actions. */
public final class InlineResultPoll extends InlineResultMultiline {
  public InlineResultPoll (BaseActivity context, Tdlib tdlib, TdApi.Message message) {
    super(context, tdlib, article(message));
    setMessage(message);
    setMediaPreview(preview(tdlib, ((TdApi.MessagePoll) message.content).media));
  }

  private static MediaPreview preview (Tdlib tdlib, TdApi.PollMedia media) {
    if (media == null) {
      return null;
    }
    int size = Screen.dp(50f);
    int radius = Screen.dp(3f);
    switch (media.getConstructor()) {
      case TdApi.PollMediaPhoto.CONSTRUCTOR:
        return MediaPreview.valueOf(tdlib, ((TdApi.PollMediaPhoto) media).photo, size, radius, false);
      case TdApi.PollMediaVideo.CONSTRUCTOR:
        return MediaPreview.valueOf(tdlib, ((TdApi.PollMediaVideo) media).video, size, radius, false);
      case TdApi.PollMediaAnimation.CONSTRUCTOR:
        return MediaPreview.valueOf(tdlib, ((TdApi.PollMediaAnimation) media).animation, size, radius, false);
      case TdApi.PollMediaAudio.CONSTRUCTOR:
        return MediaPreview.valueOf(tdlib, ((TdApi.PollMediaAudio) media).audio, size, radius);
      case TdApi.PollMediaDocument.CONSTRUCTOR:
        return MediaPreview.valueOf(tdlib, ((TdApi.PollMediaDocument) media).document, size, radius);
      default:
        return null;
    }
  }

  private static String mediaLabel (TdApi.PollMedia media) {
    int res;
    switch (media.getConstructor()) {
      case TdApi.PollMediaPhoto.CONSTRUCTOR: res = R.string.Photo; break;
      case TdApi.PollMediaVideo.CONSTRUCTOR: res = R.string.Video; break;
      case TdApi.PollMediaAnimation.CONSTRUCTOR: res = R.string.Gif; break;
      case TdApi.PollMediaAudio.CONSTRUCTOR: res = R.string.Audio; break;
      case TdApi.PollMediaDocument.CONSTRUCTOR: res = R.string.File; break;
      case TdApi.PollMediaSticker.CONSTRUCTOR: res = R.string.Sticker; break;
      case TdApi.PollMediaLocation.CONSTRUCTOR:
      case TdApi.PollMediaVenue.CONSTRUCTOR: res = R.string.Location; break;
      default: res = R.string.Link; break;
    }
    return Lang.getString(res);
  }

  private static TdApi.InlineQueryResultArticle article (TdApi.Message message) {
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    TdApi.Poll poll = content.poll;
    StringBuilder description = new StringBuilder(Lang.getString(
      poll.type.getConstructor() == TdApi.PollTypeQuiz.CONSTRUCTOR ? R.string.Quiz : R.string.Poll));
    if (poll.canSeeResults) {
      description.append(" · ").append(Lang.plural(R.string.xVotes, poll.totalVoterCount));
    }
    if (content.media != null) {
      description.append(" · ").append(mediaLabel(content.media));
    }
    if (content.description != null && !content.description.text.isEmpty()) {
      description.append('\n').append(content.description.text);
    }
    for (int index : SharedPollVotePolicy.displayOrder(poll)) {
      TdApi.PollOption option = poll.options[index];
      description.append('\n').append(option.text.text);
      if (option.media != null) {
        description.append(" · ").append(mediaLabel(option.media));
      }
    }
    return new TdApi.InlineQueryResultArticle(Long.toString(message.id), "", poll.question.text,
      description.toString(), null);
  }
}
