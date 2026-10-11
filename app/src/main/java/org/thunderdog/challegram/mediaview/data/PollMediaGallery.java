package org.thunderdog.challegram.mediaview.data;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.SharedPollVotePolicy;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.navigation.ViewController;

import java.util.ArrayList;
import java.util.List;

/** A poll owns its gallery; attachments share a message id, but have distinct media indices. */
public final class PollMediaGallery {
  public static final int DESCRIPTION = -2;
  public static final int EXPLANATION = -3;

  private PollMediaGallery () { }

  public static int indexOf (TdApi.MessagePoll content, TdApi.PollMedia media) {
    if (media == null) return -1;
    if (content.media == media) return DESCRIPTION;
    if (content.poll.type instanceof TdApi.PollTypeQuiz &&
        ((TdApi.PollTypeQuiz) content.poll.type).explanationMedia == media) return EXPLANATION;
    for (int i = 0; i < content.poll.options.length; i++) {
      if (content.poll.options[i].media == media) return i;
    }
    return -1;
  }

  public static @Nullable MediaStack createStack (ViewController<?> context, TdApi.Message message,
                                                @Nullable TGMessage source, int selectedIndex) {
    if (!(message.content instanceof TdApi.MessagePoll)) return null;
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    TdApi.PollTypeQuiz quiz = content.poll.type instanceof TdApi.PollTypeQuiz ?
      (TdApi.PollTypeQuiz) content.poll.type : null;
    MediaItem selected;
    if (selectedIndex == DESCRIPTION) {
      selected = valueOf(context, content.media, content.description);
    } else if (selectedIndex == EXPLANATION && quiz != null) {
      selected = valueOf(context, quiz.explanationMedia, quiz.explanation);
    } else if (selectedIndex >= 0 && selectedIndex < content.poll.options.length) {
      TdApi.PollOption option = content.poll.options[selectedIndex];
      selected = valueOf(context, option.media, option.text);
    } else {
      return null;
    }
    if (selected == null) return null;
    List<MediaItem> items = new ArrayList<>();
    int currentIndex = -1;
    MediaItem item = selectedIndex == DESCRIPTION ? selected :
      valueOf(context, content.media, content.description);
    if (item != null) {
      if (selectedIndex == DESCRIPTION) currentIndex = items.size();
      items.add(item);
    }
    for (int index : SharedPollVotePolicy.displayOrder(content.poll)) {
      TdApi.PollOption option = content.poll.options[index];
      item = selectedIndex == index ? selected : valueOf(context, option.media, option.text);
      if (item != null) {
        if (selectedIndex == index) currentIndex = items.size();
        items.add(item);
      }
    }
    if (quiz != null) {
      item = selectedIndex == EXPLANATION ? selected :
        valueOf(context, quiz.explanationMedia, quiz.explanation);
      if (item != null) {
        if (selectedIndex == EXPLANATION) currentIndex = items.size();
        items.add(item);
      }
    }
    if (currentIndex == -1) return null;
    for (MediaItem mediaItem : items) {
      mediaItem.setSourceMessage(message).setSourceSender(message.senderId).setSourceDate(message.date);
      if (source != null) mediaItem.setSourceMessage(source);
    }
    MediaStack stack = new MediaStack(context.context(), context.tdlib());
    stack.set(currentIndex, items);
    return stack;
  }

  private static @Nullable MediaItem valueOf (ViewController<?> context, TdApi.PollMedia media,
                                            TdApi.FormattedText caption) {
    if (media instanceof TdApi.PollMediaPhoto) {
      TdApi.PollMediaPhoto photo = (TdApi.PollMediaPhoto) media;
      return photo.video != null ?
        MediaItem.valueOf(context.context(), context.tdlib(), photo.video, null, photo.photo, caption) :
        MediaItem.valueOf(context.context(), context.tdlib(), photo.photo, caption);
    } else if (media instanceof TdApi.PollMediaVideo) {
      TdApi.PollMediaVideo video = (TdApi.PollMediaVideo) media;
      return MediaItem.valueOf(context.context(), context.tdlib(), video.video,
        video.alternativeVideos, video.cover, caption);
    } else if (media instanceof TdApi.PollMediaAnimation) {
      return MediaItem.valueOf(context.context(), context.tdlib(),
        ((TdApi.PollMediaAnimation) media).animation, caption);
    } else if (media instanceof TdApi.PollMediaDocument) {
      return MediaItem.valueOf(context.context(), context.tdlib(),
        ((TdApi.PollMediaDocument) media).document, caption);
    }
    return null;
  }
}
