package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MediaPreview;
import org.thunderdog.challegram.component.chat.MediaPreviewSimple;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.MapController;
import org.thunderdog.challegram.util.text.Text;

import java.util.Arrays;
import java.util.Locale;

import me.vkryl.core.ColorUtils;
import tgx.td.Td;

/** An independently owned receiver prevents poll attachments from sharing file or avatar keys. */
final class PollMediaAttachment {
  final TdApi.PollMedia media;
  private final TGMessagePoll owner;
  private final ComplexReceiver receiver = new ComplexReceiver();
  private MediaWrapper visual;
  private FileComponent file;
  private MediaPreview preview;
  private Text label;
  private int previewHeight;
  private int height, left, top, width;
  private boolean pressed;
  private boolean thumbnail;

  PollMediaAttachment (TGMessagePoll owner, TdApi.PollMedia media) {
    this.owner = owner;
    this.media = media;
    receiver.setUpdateListener((r, key) -> owner.invalidate());
  }

  private void buildComponents () {
    if (visual != null || file != null) return;
    if (media instanceof TdApi.PollMediaPhoto) {
      TdApi.PollMediaPhoto p = (TdApi.PollMediaPhoto) media;
      if (p.video != null) {
        visual = new MediaWrapper(owner.context(), owner.tdlib(), p.video, p.photo,
          owner.msg.chatId, owner.msg.id, owner, false);
      } else {
        visual = new MediaWrapper(owner.context(), owner.tdlib(), p.photo,
          owner.msg.chatId, owner.msg.id, owner, false);
      }
    } else if (media instanceof TdApi.PollMediaVideo) {
      TdApi.PollMediaVideo v = (TdApi.PollMediaVideo) media;
      visual = new MediaWrapper(owner.context(), owner.tdlib(), v.video, v.cover,
        owner.msg.chatId, owner.msg.id, owner, false);
    } else if (media instanceof TdApi.PollMediaAnimation) {
      TdApi.Animation a = ((TdApi.PollMediaAnimation) media).animation;
      visual = new MediaWrapper(owner.context(), owner.tdlib(), a,
        owner.msg.chatId, owner.msg.id, owner, false);
    } else if (media instanceof TdApi.PollMediaAudio) {
      TdApi.Audio audio = ((TdApi.PollMediaAudio) media).audio;
      TdApi.Message playbackMessage = Td.copyOf(owner.msg);
      playbackMessage.content = new TdApi.MessageAudio(audio, new TdApi.FormattedText("", null));
      file = new FileComponent(owner, owner.msg, audio, playbackMessage, null);
    } else if (media instanceof TdApi.PollMediaDocument) {
      file = new FileComponent(owner, owner.msg, ((TdApi.PollMediaDocument) media).document);
    }
    if (visual != null) {
      visual.setViewProvider(owner.currentViews);
      visual.setOnClickListener((view, wrapper) -> {
        open();
        return true;
      });
    }
    if (file != null) {
      file.setViewProvider(owner.currentViews);
    }
  }

  void build (int maxWidth) {
    thumbnail = false;
    buildComponents();
    width = maxWidth;
    if (visual != null) {
      float ratio = Math.min(
        (float) maxWidth / Math.max(1, visual.getContentWidth()),
        (float) Screen.dp(220f) / Math.max(1, visual.getContentHeight())
      );
      visual.buildContent(
        Math.max(1, (int) (visual.getContentWidth() * ratio)),
        Math.max(1, (int) (visual.getContentHeight() * ratio))
      );
      height = visual.getCellHeight();
      visual.requestPreview(receiver.getPreviewReceiver(0));
      visual.requestImage(receiver.getImageReceiver(0));
      visual.requestGif(receiver.getGifReceiver(0));
    } else if (file != null) {
      file.buildLayout(maxWidth);
      height = file.getHeight();
      file.requestPreview(receiver.getPreviewReceiver(0));
      file.requestContent(receiver.getImageReceiver(0));
    } else {
      int size = Math.min(maxWidth, Screen.dp(180f));
      String labelText = null;
      if (media instanceof TdApi.PollMediaLocation) {
        TdApi.Location location = ((TdApi.PollMediaLocation) media).location;
        preview = MediaPreview.valueOf(owner.tdlib(), location, null, size, Screen.dp(6f));
        labelText = Lang.getString(R.string.PollMediaLocation) + " · " + String.format(
          Locale.US, "%.5f, %.5f", location.latitude, location.longitude
        );
      } else if (media instanceof TdApi.PollMediaVenue) {
        TdApi.Venue venue = ((TdApi.PollMediaVenue) media).venue;
        preview = MediaPreview.valueOf(owner.tdlib(), venue, null, size, Screen.dp(6f));
        labelText = venue.title + " · " + venue.address;
      } else if (media instanceof TdApi.PollMediaSticker) {
        preview = new MediaPreviewSimple(
          owner.tdlib(), size, 0, ((TdApi.PollMediaSticker) media).sticker
        );
      } else if (media instanceof TdApi.PollMediaLink) {
        TdApi.PollMediaLink link = (TdApi.PollMediaLink) media;
        TdApi.Message message = new TdApi.Message();
        message.content = new TdApi.MessageText(
          new TdApi.FormattedText(link.url, null), link.linkPreview, null
        );
        preview = MediaPreview.valueOf(owner.tdlib(), message, null, size, Screen.dp(6f));
        labelText = link.url;
      }
      previewHeight = preview != null ? preview.getHeight() : 0;
      label = labelText != null ? new Text.Builder(
        labelText, maxWidth, Paints.robotoStyleProvider(13f), owner.getTextColorSet()
      ).singleLine().build() : null;
      height = previewHeight + (label != null ? label.getHeight() + Screen.dp(8f) : 0);
      if (preview != null) {
        preview.requestFiles(receiver, false);
      }
    }
    attach(owner.currentViews.hasAnyTargetToInvalidate());
  }

  void buildThumbnail (int size) {
    if (thumbnail && width == size) return;
    thumbnail = true;
    width = height = size;
    int radius = Screen.dp(5f);
    if (media instanceof TdApi.PollMediaPhoto) {
      preview = MediaPreview.valueOf(owner.tdlib(), ((TdApi.PollMediaPhoto) media).photo,
        size, radius, false);
    } else if (media instanceof TdApi.PollMediaVideo) {
      TdApi.PollMediaVideo video = (TdApi.PollMediaVideo) media;
      preview = video.cover != null ?
        MediaPreview.valueOf(owner.tdlib(), video.cover, size, radius, false) :
        MediaPreview.valueOf(owner.tdlib(), video.video, size, radius, false);
    } else if (media instanceof TdApi.PollMediaAnimation) {
      preview = MediaPreview.valueOf(owner.tdlib(), ((TdApi.PollMediaAnimation) media).animation,
        size, radius, false);
    } else if (media instanceof TdApi.PollMediaAudio) {
      preview = MediaPreview.valueOf(owner.tdlib(), ((TdApi.PollMediaAudio) media).audio,
        size, radius);
    } else if (media instanceof TdApi.PollMediaDocument) {
      preview = MediaPreview.valueOf(owner.tdlib(), ((TdApi.PollMediaDocument) media).document,
        size, radius);
    } else if (media instanceof TdApi.PollMediaSticker) {
      preview = new MediaPreviewSimple(owner.tdlib(), size, radius,
        ((TdApi.PollMediaSticker) media).sticker);
    } else if (media instanceof TdApi.PollMediaLocation) {
      preview = MediaPreview.valueOf(owner.tdlib(), ((TdApi.PollMediaLocation) media).location,
        null, size, radius);
    } else if (media instanceof TdApi.PollMediaVenue) {
      preview = MediaPreview.valueOf(owner.tdlib(), ((TdApi.PollMediaVenue) media).venue,
        null, size, radius);
    } else if (media instanceof TdApi.PollMediaLink) {
      TdApi.PollMediaLink link = (TdApi.PollMediaLink) media;
      TdApi.Message message = new TdApi.Message();
      message.content = new TdApi.MessageText(
        new TdApi.FormattedText(link.url, null), link.linkPreview, null
      );
      preview = MediaPreview.valueOf(owner.tdlib(), message, null, size, radius);
    }
    if (preview != null) {
      preview.requestFiles(receiver, false);
    } else {
      receiver.clear();
    }
    attach(owner.currentViews.hasAnyTargetToInvalidate());
  }

  static boolean sameMedia (TdApi.PollMedia first, TdApi.PollMedia second) {
    if (first == second) {
      return true;
    }
    if (first == null || second == null || first.getConstructor() != second.getConstructor()) {
      return false;
    }
    switch (first.getConstructor()) {
      case TdApi.PollMediaPhoto.CONSTRUCTOR: {
        TdApi.PollMediaPhoto a = (TdApi.PollMediaPhoto) first, b = (TdApi.PollMediaPhoto) second;
        return Td.equalsTo(a.photo, b.photo) && Td.equalsTo(a.video, b.video);
      }
      case TdApi.PollMediaVideo.CONSTRUCTOR: {
        TdApi.PollMediaVideo a = (TdApi.PollMediaVideo) first, b = (TdApi.PollMediaVideo) second;
        return Td.equalsTo(a.video, b.video) && Td.equalsTo(a.cover, b.cover) &&
          a.startTimestamp == b.startTimestamp &&
          Arrays.equals(a.alternativeVideos, b.alternativeVideos) &&
          Arrays.equals(a.storyboards, b.storyboards);
      }
      case TdApi.PollMediaAnimation.CONSTRUCTOR:
        return Td.equalsTo(
          ((TdApi.PollMediaAnimation) first).animation, ((TdApi.PollMediaAnimation) second).animation
        );
      case TdApi.PollMediaAudio.CONSTRUCTOR:
        return Td.equalsTo(
          ((TdApi.PollMediaAudio) first).audio, ((TdApi.PollMediaAudio) second).audio
        );
      case TdApi.PollMediaDocument.CONSTRUCTOR:
        return Td.equalsTo(
          ((TdApi.PollMediaDocument) first).document, ((TdApi.PollMediaDocument) second).document
        );
      case TdApi.PollMediaSticker.CONSTRUCTOR:
        return Td.equalsTo(
          ((TdApi.PollMediaSticker) first).sticker, ((TdApi.PollMediaSticker) second).sticker
        );
      case TdApi.PollMediaLocation.CONSTRUCTOR:
        return Td.equalsTo(
          ((TdApi.PollMediaLocation) first).location, ((TdApi.PollMediaLocation) second).location
        );
      case TdApi.PollMediaVenue.CONSTRUCTOR:
        return Td.equalsTo(
          ((TdApi.PollMediaVenue) first).venue, ((TdApi.PollMediaVenue) second).venue
        );
      case TdApi.PollMediaLink.CONSTRUCTOR: {
        TdApi.PollMediaLink a = (TdApi.PollMediaLink) first, b = (TdApi.PollMediaLink) second;
        return a.url.equals(b.url) && a.linkPreview == b.linkPreview;
      }
      default:
        return false;
    }
  }

  int height () {
    return height + Screen.dp(8f);
  }

  void attach (boolean attached) {
    if (attached) {
      receiver.attach();
    } else {
      receiver.detach();
    }
  }

  void destroy () {
    receiver.performDestroy();
    if (visual != null) {
      visual.destroy();
    }
    if (file != null) {
      file.performDestroy();
    }
  }

  void draw (MessageView view, Canvas canvas, int x, int y) {
    left = x;
    top = y;
    if (thumbnail) {
      if (preview != null) {
        preview.draw(view, canvas, receiver, x, y, 1f);
      } else {
        canvas.drawRoundRect(x, y, x + width, y + height, Screen.dp(5f), Screen.dp(5f),
          Paints.fillingPaint(ColorUtils.alphaColor(.08f, owner.getTextColor())));
      }
      int icon = media instanceof TdApi.PollMediaLink ? R.drawable.baseline_link_24 :
        media instanceof TdApi.PollMediaVideo ||
        media instanceof TdApi.PollMediaPhoto && ((TdApi.PollMediaPhoto) media).video != null ?
          R.drawable.baseline_play_arrow_36_white :
        preview == null && media instanceof TdApi.PollMediaAudio ? R.drawable.baseline_music_note_24 :
        preview == null && media instanceof TdApi.PollMediaDocument ?
          R.drawable.baseline_insert_drive_file_24 : 0;
      if (icon != 0) {
        Drawable drawable = Drawables.get(icon);
        if (preview != null) {
          canvas.drawRoundRect(x, y, x + width, y + height, Screen.dp(5f), Screen.dp(5f),
            Paints.fillingPaint(0x40000000));
        }
        Drawables.draw(canvas, drawable, x + (width - drawable.getMinimumWidth()) / 2f,
          y + (height - drawable.getMinimumHeight()) / 2f,
          preview != null ? Paints.whitePorterDuffPaint() : owner.getDecentIconPaint());
      }
    } else if (visual != null) {
      visual.draw(view, canvas, x, y, receiver.getPreviewReceiver(0),
        visual.getTargetGifFile() != null ? receiver.getGifReceiver(0) : receiver.getImageReceiver(0),
        1f);
    } else if (file != null) {
      file.draw(view, canvas, x, y, receiver.getPreviewReceiver(0), receiver.getImageReceiver(0),
        owner.getContentBackgroundColor(), owner.getContentReplaceColor(), 1f, 0f);
    } else {
      if (preview != null) {
        preview.draw(view, canvas, receiver, x, y, 1f);
      }
      if (label != null) {
        label.draw(canvas, x, y + previewHeight + Screen.dp(4f));
      }
    }
  }

  boolean touch (MessageView view, MotionEvent event) {
    if (!thumbnail && visual != null && visual.onTouchEvent(view, event)) {
      return true;
    }
    if (!thumbnail && file != null && file.onTouchEvent(view, event)) {
      return true;
    }
    boolean inside = event.getX() >= left && event.getX() < left + width &&
      event.getY() >= top && event.getY() < top + height;
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      pressed = inside;
    } else if (event.getAction() == MotionEvent.ACTION_UP && pressed) {
      pressed = false;
      if (inside) {
        open();
      }
      return true;
    } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
      pressed = false;
    }
    return pressed;
  }

  private void open () {
    if (owner.openMedia(this)) {
      return;
    }
    if (media instanceof TdApi.PollMediaDocument) {
      buildComponents();
      file.open();
    } else if (media instanceof TdApi.PollMediaAudio) {
      TdApi.Message audio = Td.copyOf(owner.msg);
      audio.content = new TdApi.MessageAudio(((TdApi.PollMediaAudio) media).audio,
        new TdApi.FormattedText("", null));
      owner.tdlib().context().player().playPauseMessage(owner.tdlib(), audio, null);
    } else if (media instanceof TdApi.PollMediaLink) {
      owner.tdlib().ui().openUrl(owner.controller(), ((TdApi.PollMediaLink) media).url,
        owner.openParameters());
    } else if (media instanceof TdApi.PollMediaSticker) {
      owner.tdlib().ui().showStickerSet(owner.controller(),
        ((TdApi.PollMediaSticker) media).sticker.setId, owner.openParameters());
    } else {
      TdApi.Location location = media instanceof TdApi.PollMediaLocation ?
        ((TdApi.PollMediaLocation) media).location : media instanceof TdApi.PollMediaVenue ?
        ((TdApi.PollMediaVenue) media).venue.location : null;
      if (location != null) {
        owner.tdlib().ui().openMap(owner.controller(),
          new MapController.Args(location.latitude, location.longitude, owner.msg));
      }
    }
  }
}
