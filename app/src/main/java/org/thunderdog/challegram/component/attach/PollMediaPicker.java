package org.thunderdog.challegram.component.attach;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.sticker.TGStickerObj;
import org.thunderdog.challegram.component.chat.MediaPreview;
import org.thunderdog.challegram.component.chat.MediaPreviewSimple;
import org.thunderdog.challegram.telegram.TdlibUi;
import me.vkryl.core.lambda.Destroyable;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.RightId;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.widget.EmojiLayout;
import org.thunderdog.challegram.widget.PopupLayout;

/** The same attachment picker is used for draft polls and newly added answers. */
public final class PollMediaPicker {
  private PollMediaPicker () { }

  public interface Callback {
    void onMediaSelected (TdApi.InputPollMedia media, CharSequence label);
    default void onMediaSelected (TdApi.InputPollMedia media, CharSequence label, @Nullable MediaPreview preview) {
      onMediaSelected(media, label);
    }
  }

  public static void show (ViewController<?> controller, boolean optionMedia, Callback callback) {
    show(controller, 0, optionMedia, callback);
  }

  public static void show (ViewController<?> controller, long chatId, boolean optionMedia, Callback callback) {
    if (controller.isDestroyed()) return;
    int[] ids = optionMedia ? new int[] {R.id.menu_btn_gallery, R.id.btn_file, R.id.controller_media_location, R.id.btn_stickerSet, R.id.btn_link} :
      new int[] {R.id.menu_btn_gallery, R.id.btn_file, R.id.controller_media_location};
    String[] labels = optionMedia ? new String[] {Lang.getString(R.string.Gallery), Lang.getString(R.string.File), Lang.getString(R.string.Location), Lang.getString(R.string.PollMediaSticker), Lang.getString(R.string.PollMediaLink)} :
      new String[] {Lang.getString(R.string.Gallery), Lang.getString(R.string.File), Lang.getString(R.string.Location)};
    controller.showOptions(Lang.getString(R.string.PollMediaSelect), ids, labels, null, null, (view, id) -> {
      if (id == R.id.btn_link) {
        showLink(controller, callback);
      } else if (id == R.id.controller_media_location) {
        showLocation(controller, chatId, callback);
      } else if (id == R.id.btn_stickerSet) {
        showStickers(controller, chatId, callback);
      } else {
        int rights = (1 << RightId.SEND_PHOTOS) | (1 << RightId.SEND_VIDEOS) |
          (1 << RightId.SEND_OTHER_MESSAGES) | (optionMedia ? 0 : (1 << RightId.SEND_DOCS) | (1 << RightId.SEND_AUDIO));
        MediaToReplacePickerManager manager = new MediaToReplacePickerManager(controller);
        Destroyable dismiss = () -> {
          if (manager.currentMediaLayout != null) {
            if (manager.currentMediaLayout.isVisible()) manager.currentMediaLayout.hide(false);
            else manager.currentMediaLayout.performDestroy();
          }
        };
        controller.addDestroyListener(dismiss);
        manager.openMediaView(file -> {
          if (controller.isDestroyed()) return;
          TdApi.InputMessageContent content = file.imageGalleryFile != null ?
            TD.toContent(controller.tdlib(), file.imageGalleryFile, false, true, false, false, false) :
            TD.toInputMessageContent(null, file.inlineResult, false, false, !optionMedia, !optionMedia, true, true);
          TdApi.InputPollMedia media = fromContent(content, optionMedia);
          if (media != null) {
            callback.onMediaSelected(media, file.getFileName(Lang.getString(id == R.id.menu_btn_gallery ? R.string.Gallery : R.string.File)), file.buildMediaPreview(controller.tdlib(), Screen.dp(48), Screen.dp(6)));
          } else {
            controller.showErrorTooltip(view, Lang.getString(R.string.PollMediaInvalid));
          }
        }, chatId, () -> { if (!controller.isDestroyed()) controller.removeDestroyListener(dismiss); }, false, null, rights, id == R.id.btn_file);
      }
      return true;
    });
  }

  private static void showLink (ViewController<?> controller, Callback callback) {
    EditText input = new EditText(controller.context());
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    AlertDialog dialog = new AlertDialog.Builder(controller.context())
      .setTitle(Lang.getString(R.string.PollMediaLink)).setView(input)
      .setPositiveButton(Lang.getString(R.string.Done), null)
      .setNegativeButton(Lang.getString(R.string.Cancel), null).create();
    Destroyable dismiss = dialog::dismiss;
    controller.addDestroyListener(dismiss);
    dialog.setOnDismissListener(ignored -> { if (!controller.isDestroyed()) controller.removeDestroyListener(dismiss); });
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      String url = input.getText().toString().trim();
      if (!(url.startsWith("https://") || url.startsWith("http://")) || android.net.Uri.parse(url).getHost() == null) {
        input.setError(Lang.getString(R.string.PollMediaInvalid));
        return;
      }
      if (!controller.isDestroyed()) callback.onMediaSelected(new TdApi.InputPollMediaLink(url), url);
      dialog.dismiss();
    }));
    dialog.show();
  }

  private static void showLocation (ViewController<?> controller, long chatId, Callback callback) {
    Destroyable[] destroy = new Destroyable[1];
    MediaLayout layout = new MediaLayout(controller) {
      @Override
      public long getTargetChatId () { return chatId; }

      @Override
      public void onPopupDismiss (PopupLayout popup) {
        if (!controller.isDestroyed()) controller.removeDestroyListener(destroy[0]);
        super.onPopupDismiss(popup);
      }

      @Override
      public void sendLocation (double latitude, double longitude, double accuracy, int heading, int livePeriod) {
        if (controller.isDestroyed()) return;
        TdApi.Location location = new TdApi.Location(latitude, longitude, accuracy);
        callback.onMediaSelected(new TdApi.InputPollMediaLocation(location), Lang.getString(R.string.Location), MediaPreview.valueOf(controller.tdlib(), location, null, Screen.dp(48), Screen.dp(6)));
        hide(false);
      }

      @Override
      public void sendVenue (MediaLocationData place) {
        if (controller.isDestroyed()) return;
        TdApi.Venue venue = place.convertToInputMessage().venue;
        callback.onMediaSelected(new TdApi.InputPollMediaVenue(venue), place.getTitle(), MediaPreview.valueOf(controller.tdlib(), venue, null, Screen.dp(48), Screen.dp(6)));
        hide(false);
      }
    };
    destroy[0] = () -> {
      if (layout.isVisible()) layout.hide(false);
      else layout.performDestroy();
    };
    controller.addDestroyListener(destroy[0]);
    layout.init(MediaLayout.MODE_LOCATION, null);
    layout.preload(() -> { if (!controller.isDestroyed()) layout.show(); }, 300);
  }

  private static void showStickers (ViewController<?> controller, long chatId, Callback callback) {
    controller.showPopup(Lang.getString(R.string.PollMediaSticker), true, Text.LINE_COUNT_UNLIMITED, (popup, wrap) -> {
      EmojiLayout emoji = new EmojiLayout(controller.context());
      emoji.initWithMediasEnabled(controller, true, new EmojiLayout.Listener() {
        @Override
        public long getOutputChatId () { return chatId; }

        @Override
        public boolean onSendSticker (@Nullable View view, TGStickerObj object, TdApi.MessageSendOptions options) {
          TdApi.Sticker sticker = object.getSticker();
          if (sticker == null || controller.isDestroyed()) return false;
          if (object.isCustomEmoji() && !controller.tdlib().hasPremium()) {
            controller.tdlib().ui().showPremiumAlert(controller, view != null ? view : controller.getValue(), TdlibUi.PremiumFeature.CUSTOM_EMOJI);
            return false;
          }
          callback.onMediaSelected(new TdApi.InputPollMediaSticker(new TdApi.InputSticker(new TdApi.InputFileId(sticker.sticker.id), null, sticker.width, sticker.height)), sticker.emoji,
            new MediaPreviewSimple(controller.tdlib(), Screen.dp(48), Screen.dp(6), sticker));
          popup.hideWindow(true);
          return true;
        }

        @Override
        public void onEnterEmoji (String value) {
          controller.tdlib().send(new TdApi.GetAnimatedEmoji(value), (animated, error) -> controller.runOnUiThreadOptional(() -> {
            if (popup.isDestroyed() || popup.isWindowHidden()) return;
            if (error == null && animated != null && animated.sticker != null) {
              onSendSticker(null, new TGStickerObj(controller.tdlib(), animated.sticker, (String) null, animated.sticker.fullType), null);
            } else {
              controller.showErrorTooltip(controller.getValue(), Lang.getString(R.string.PollMediaInvalid));
            }
          }));
        }

        @Override
        public void onEnterCustomEmoji (TGStickerObj object) {
          onSendSticker(null, object, null);
        }

        @Override
        public boolean onSendGIF (@Nullable View view, TdApi.Animation animation) {
          if (controller.isDestroyed()) return false;
          callback.onMediaSelected(new TdApi.InputPollMediaAnimation(TD.toInputMessageContent(animation).animation), Lang.getString(R.string.Gif), MediaPreview.valueOf(controller.tdlib(), animation, Screen.dp(48), Screen.dp(6), false));
          popup.hideWindow(true);
          return true;
        }
      }, controller, false);
      emoji.setForceHeight(Screen.dp(320));
      wrap.addView(emoji);
      Destroyable dismiss = () -> popup.hideWindow(false);
      controller.addDestroyListener(dismiss);
      popup.setDismissListener(new PopupLayout.DismissListener() {
        @Override
        public void onPopupDismissPrepare (PopupLayout layout) { }
        @Override
        public void onPopupDismiss (PopupLayout layout) {
          if (!controller.isDestroyed()) controller.removeDestroyListener(dismiss);
          emoji.destroy();
        }
      });
      return Screen.dp(320);
    }, null);
  }

  @Nullable
  public static TdApi.InputPollMedia fromContent (@Nullable TdApi.InputMessageContent content, boolean optionMedia) {
    if (content == null) return null;
    switch (content.getConstructor()) {
      case TdApi.InputMessagePhoto.CONSTRUCTOR:
        return new TdApi.InputPollMediaPhoto(((TdApi.InputMessagePhoto) content).photo);
      case TdApi.InputMessageVideo.CONSTRUCTOR:
        return new TdApi.InputPollMediaVideo(((TdApi.InputMessageVideo) content).video);
      case TdApi.InputMessageAnimation.CONSTRUCTOR:
        return new TdApi.InputPollMediaAnimation(((TdApi.InputMessageAnimation) content).animation);
      case TdApi.InputMessageAudio.CONSTRUCTOR:
        return optionMedia ? null : new TdApi.InputPollMediaAudio(((TdApi.InputMessageAudio) content).audio);
      case TdApi.InputMessageDocument.CONSTRUCTOR:
        return optionMedia ? null : new TdApi.InputPollMediaDocument(((TdApi.InputMessageDocument) content).document);
      case TdApi.InputMessageLocation.CONSTRUCTOR:
        return new TdApi.InputPollMediaLocation(((TdApi.InputMessageLocation) content).location);
      case TdApi.InputMessageVenue.CONSTRUCTOR:
        return new TdApi.InputPollMediaVenue(((TdApi.InputMessageVenue) content).venue);
      case TdApi.InputMessageSticker.CONSTRUCTOR:
        return optionMedia ? new TdApi.InputPollMediaSticker(((TdApi.InputMessageSticker) content).sticker) : null;
      default:
        return null;
    }
  }
}
