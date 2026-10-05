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
 * File created on 09/02/2024 at 18:34
 */
package org.thunderdog.challegram.component.attach;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.MediaCollectorDelegate;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.core.Media;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.filegen.PhotoGenerationInfo;
import org.thunderdog.challegram.filegen.SimpleGenerationInfo;
import org.thunderdog.challegram.filegen.VideoGenerationInfo;
import org.thunderdog.challegram.loader.ImageGalleryFile;
import org.thunderdog.challegram.mediaview.AvatarPickerMode;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.navigation.EditHeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.ContactPhotoStickerController;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.OptionDelegate;

import java.io.File;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableData;

public class AvatarPickerManager extends MediaLayoutManager {
  public static final int MODE_PROFILE = 0;
  public static final int MODE_PROFILE_PUBLIC = 1;
  public static final int MODE_CHAT = 2;
  public static final int MODE_NON_CREATED = 3;

  public AvatarPickerManager (ViewController<?> context) {
    super(context);
  }

  public void showMenuForProfile (@Nullable MediaCollectorDelegate delegate, boolean isPublic) {
    final ViewController.Options.Builder b = new ViewController.Options.Builder();

    final TdApi.User user = tdlib.myUser();
    final TdApi.UserFullInfo userFullInfo = tdlib.myUserFull();

    final long profilePhotoToDelete = isPublic ?
      (userFullInfo != null && userFullInfo.publicPhoto != null ? userFullInfo.publicPhoto.id : 0) :
      (user != null && user.profilePhoto != null ? user.profilePhoto.id : 0);

    if (profilePhotoToDelete != 0 && !isPublic) {
      b.item(new ViewController.OptionItem(R.id.btn_open, Lang.getString(R.string.Open),
        ViewController.OptionColor.NORMAL, R.drawable.baseline_visibility_24));
    }

    b.item(new ViewController.OptionItem(R.id.btn_changePhotoGallery, Lang.getString(isPublic ? R.string.SetPublicPhoto : R.string.SetProfilePhoto),
      ViewController.OptionColor.NORMAL, R.drawable.baseline_image_24));

    final Runnable deleteRunnable = () -> showDeletePhotoConfirm(() -> deleteProfilePhoto(profilePhotoToDelete));
    if (profilePhotoToDelete != 0 && !isPublic) {
      b.item(new ViewController.OptionItem(R.id.btn_changePhotoDelete, Lang.getString(R.string.Delete),
        ViewController.OptionColor.RED, R.drawable.baseline_delete_24));
    }

    showOptions(b.build(), (itemView, id) -> {
      if (id == R.id.btn_open) {
        MediaViewController.openFromProfile(context, user, delegate);
      } else if (id == R.id.btn_changePhotoGallery) {
        openMediaView(AvatarPickerMode.PROFILE, f -> onProfilePhotoReceived(f, isPublic),
          profilePhotoToDelete != 0 ? Lang.getString(isPublic ? R.string.RemovePublicPhoto : R.string.RemoveProfilePhoto) : null,
          ColorId.textNegative, deleteRunnable);
      } else if (id == R.id.btn_changePhotoDelete) {
        deleteRunnable.run();
      }
      return true;
    });
  }

  public void showMenuForChat (TdApi.Chat chat, MediaCollectorDelegate delegate, boolean allowOpenPhoto) {
    if (chat == null) {
      return;
    }

    final boolean isBot = tdlib.canEditBotChat(chat.id);
    final boolean isChannel = tdlib.isChannel(chat.id);
    ViewController.Options.Builder b = new ViewController.Options.Builder();

    if (chat.photo != null && allowOpenPhoto) {
      b.item(new ViewController.OptionItem(R.id.btn_open, Lang.getString(R.string.Open),
        ViewController.OptionColor.NORMAL, R.drawable.baseline_visibility_24));
    }

    b.item(new ViewController.OptionItem(R.id.btn_changePhotoGallery, Lang.getString(isBot ? R.string.SetBotPhoto : isChannel ? R.string.SetChannelPhoto : R.string.SetGroupPhoto),
      ViewController.OptionColor.NORMAL, R.drawable.baseline_image_24));

    final boolean canDelete = chat.photo != null;
    showOptions(b.build(), (itemView, id) -> {
      if (id == R.id.btn_open) {
        if (chat.photo != null && !TD.isFileEmpty(chat.photo.small)) {
          MediaViewController.openFromChat(context, chat, delegate);
        }
      } else if (id == R.id.btn_changePhotoGallery) {
        @AvatarPickerMode int mode = isBot ? AvatarPickerMode.BOT : isChannel ? AvatarPickerMode.CHANNEL : AvatarPickerMode.GROUP;
        openMediaView(mode, f -> onChatPhotoReceived(f, chat.id),
          canDelete ? Lang.getString(isBot ? R.string.RemoveBotPhoto : isChannel ? R.string.RemoveChannelPhoto : R.string.RemoveGroupPhoto) : null, ColorId.textNegative, () -> showDeletePhotoConfirm(() -> setChatPhoto(chat.id, null)));
      }
      return true;
    });
  }

  private boolean contactPhotoInProgress;
  private long contactPhotoAccountId;

  private boolean isContactPhotoContextValid () {
    return !context.isDestroyed() && tdlib.isAuthorized() && tdlib.myUserId() == contactPhotoAccountId;
  }

  public void showContactPhotoPicker (TdApi.User user, boolean suggest) {
    contactPhotoAccountId = tdlib.myUserId();
    if (contactPhotoInProgress) {
      return;
    }
    ViewController.Options.Builder options = new ViewController.Options.Builder();
    options.item(new ViewController.OptionItem(R.id.btn_changePhotoGallery, Lang.getString(R.string.Gallery),
      ViewController.OptionColor.NORMAL, R.drawable.baseline_image_24));
    options.item(new ViewController.OptionItem(R.id.btn_contactPhotoEmoji, Lang.getString(R.string.ContactPhotoEmoji),
      ViewController.OptionColor.NORMAL, R.drawable.deproko_baseline_insert_emoticon_26));
    context.showOptions(options.build(), (view, id) -> {
      if (id == R.id.btn_contactPhotoEmoji) {
        context.navigateTo(new ContactPhotoStickerController(context.context(), tdlib,
          Lang.getString(suggest ? R.string.SuggestContactPhoto : R.string.SetContactPhoto, user.firstName),
          photo -> confirmContactPhoto(user, suggest, photo)));
      } else if (id == R.id.btn_changePhotoGallery) {
        TdApi.UserFullInfo full = tdlib.cache().userFull(user.id, false);
        openMediaView(suggest ? AvatarPickerMode.SUGGEST : AvatarPickerMode.CONTACT,
          file -> confirmContactPhoto(user, suggest, file),
          !suggest && full != null && full.personalPhoto != null ? Lang.getString(R.string.ResetContactPhoto) : null,
          ColorId.textNegative, () -> resetContactPhoto(user));
      }
      return true;
    });
  }

  public void resetContactPhoto (TdApi.User user) {
    contactPhotoAccountId = tdlib.myUserId();
    if (contactPhotoInProgress) {
      return;
    }
    context.showConfirm(Lang.getString(R.string.ResetContactPhotoConfirm, user.firstName),
      Lang.getString(R.string.ResetContactPhoto), R.drawable.baseline_replay_24,
      ViewController.OptionColor.RED, () -> {
        if (currentMediaLayout != null) {
          currentMediaLayout.hide(false);
        }
        sendContactPhoto(user.id, null, false);
      });
  }

  private void confirmContactPhoto (TdApi.User user, boolean suggest, ImageGalleryFile file) {
    if (!isContactPhotoContextValid() || contactPhotoInProgress) {
      return;
    }
    if (file.isVideo()) {
      if (!Config.MODERN_VIDEO_TRANSCODING_ENABLED) {
        UI.showToast(R.string.ContactPhotoVideoUnavailable, Toast.LENGTH_LONG);
        return;
      }
      if (file.getVideoDuration(true, java.util.concurrent.TimeUnit.MILLISECONDS) > 10_000) {
        UI.showToast(R.string.ContactPhotoVideoTooLong, Toast.LENGTH_LONG);
        return;
      }
      int[] outputSize = new int[2];
      file.getOutputSize(outputSize);
      if (Math.min(outputSize[0], outputSize[1]) < 160) {
        UI.showToast(R.string.ContactPhotoVideoTooSmall, Toast.LENGTH_LONG);
        return;
      }
    }
    context.showConfirm(Lang.getString(suggest ? R.string.SuggestContactPhotoConfirm : R.string.SetContactPhotoConfirm, user.firstName),
      Lang.getString(suggest ? R.string.SuggestPhoto : R.string.SetProfilePhoto),
      R.drawable.baseline_camera_alt_24, ViewController.OptionColor.BLUE, () -> {
        if (contactPhotoInProgress) {
          return;
        }
        contactPhotoInProgress = true;
        UI.showToast(R.string.UploadingPhotoWait, Toast.LENGTH_SHORT);
        Media.instance().post(() -> {
          try {
            TdApi.InputChatPhoto photo;
            if (file.isVideo()) {
              // A fixed upload budget keeps a ten-second avatar below TDLib's 2 MB limit.
              photo = new TdApi.InputChatPhotoAnimation(VideoGenerationInfo.newProfilePhotoFile(file), file.getProfilePhotoFrameTimestamp());
            } else {
              photo = new TdApi.InputChatPhotoStatic(PhotoGenerationInfo.newFile(file));
            }
            UI.post(() -> sendContactPhoto(user.id, photo, suggest));
          } catch (Throwable error) {
            Log.e("Cannot prepare contact photo", error);
            UI.post(() -> {
              contactPhotoInProgress = false;
              if (isContactPhotoContextValid()) {
                UI.showToast(R.string.ContactPhotoPrepareFailed, Toast.LENGTH_LONG);
              }
            });
          }
        });
      });
  }

  private void confirmContactPhoto (TdApi.User user, boolean suggest, TdApi.InputChatPhoto photo) {
    if (!isContactPhotoContextValid() || contactPhotoInProgress) {
      return;
    }
    context.showConfirm(Lang.getString(suggest ? R.string.SuggestContactPhotoConfirm : R.string.SetContactPhotoConfirm, user.firstName),
      Lang.getString(suggest ? R.string.SuggestPhoto : R.string.SetProfilePhoto),
      R.drawable.baseline_camera_alt_24, ViewController.OptionColor.BLUE,
      () -> sendContactPhoto(user.id, photo, suggest));
  }

  private void sendContactPhoto (long userId, @Nullable TdApi.InputChatPhoto photo, boolean suggest) {
    if (!isContactPhotoContextValid()) {
      contactPhotoInProgress = false;
      return;
    }
    contactPhotoInProgress = true;
    TdApi.Function<TdApi.Ok> function = suggest ?
      new TdApi.SuggestUserProfilePhoto(userId, photo) :
      new TdApi.SetUserPersonalProfilePhoto(userId, photo);
    tdlib.send(function, (ok, error) -> tdlib.ui().post(() -> {
      contactPhotoInProgress = false;
      if (!isContactPhotoContextValid()) {
        return;
      }
      if (error != null) {
        UI.showError(error);
      } else {
        UI.showToast(suggest ? R.string.ContactPhotoSuggested : photo == null ?
          R.string.ContactPhotoReset : R.string.ContactPhotoSet, Toast.LENGTH_SHORT);
        if (!suggest) {
          tdlib.cache().userFull(userId);
        }
      }
    }));
  }

  public void showMenuForNonCreatedChat (EditHeaderView headerView, boolean isChannel) {
    ViewController.Options.Builder b = new ViewController.Options.Builder();

    b.item(new ViewController.OptionItem(R.id.btn_changePhotoGallery, Lang.getString(isChannel ? R.string.SetChannelPhoto : R.string.SetGroupPhoto),
      ViewController.OptionColor.NORMAL, R.drawable.baseline_image_24));

    final boolean canDelete = headerView.getImageFile() != null;
    showOptions(b.build(), (itemView, id) -> {
      if (id == R.id.btn_changePhotoGallery) {
        openMediaView(isChannel ? AvatarPickerMode.CHANNEL : AvatarPickerMode.GROUP, headerView::setPhoto,
          canDelete ? Lang.getString(isChannel ? R.string.RemoveChannelPhoto : R.string.RemoveGroupPhoto) : null, ColorId.textNegative, () -> showDeletePhotoConfirm(() -> headerView.setPhoto(null)));
      }
      return true;
    });
  }

  private void showDeletePhotoConfirm (Runnable onConfirm) {
    context.showConfirm(Lang.getString(R.string.RemovePhotoConfirm), Lang.getString(R.string.Delete), R.drawable.baseline_delete_24, ViewController.OptionColor.RED, () -> {
      onConfirm.run();
      if (currentMediaLayout != null) {
        currentMediaLayout.hide(false);
      }
    });
  }

  private void showOptions (ViewController.Options options, OptionDelegate delegate) {
    if (options.items.length == 1 && options.items[0].id == R.id.btn_changePhotoGallery) {
      delegate.onOptionItemPressed(null, R.id.btn_changePhotoGallery);
      return;
    }

    context.showOptions(options, delegate);
  }


  /* Picker */

  private void openMediaView (@AvatarPickerMode int avatarPickerMode, RunnableData<ImageGalleryFile> callback, String customButtonText, @ColorId int customButtonColorId, Runnable customButtonCallback) {
    waitPermissionsForOpen(hasMedia -> {
      final MediaLayout mediaLayout = new MediaLayout(context) {
        @Override
        public int getCameraButtonOffset () {
          return !StringUtils.isEmpty(customButtonText) ? super.getCameraButtonOffset() : 0;
        }
      };
      mediaLayout.setAvatarPickerMode(avatarPickerMode);
      mediaLayout.init(MediaLayout.MODE_AVATAR_PICKER, null);
      mediaLayout.setCallback(singleMediaCallback(callback));
      if (!hasMedia) {
        mediaLayout.setNoMediaAccess();
      }


      if (!StringUtils.isEmpty(customButtonText)) {
        TextView button = Views.newTextView(context.context(), 16, Theme.getColor(customButtonColorId), Gravity.CENTER, Views.TEXT_FLAG_BOLD | Views.TEXT_FLAG_HORIZONTAL_PADDING);
        context.addThemeTextColorListener(button, customButtonColorId);

        button.setText(Lang.uppercase(customButtonText));
        button.setOnClickListener(v -> customButtonCallback.run());

        RippleSupport.setSimpleWhiteBackground(button, context);
        Views.setClickable(button);

        mediaLayout.setCustomBottomBar(button);
        button.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(55f), Gravity.BOTTOM));
      }

      openingMediaLayout = true;
      mediaLayout.preload(() -> {
        if (context.isFocused() && !context.isDestroyed()) {
          mediaLayout.show();
        }
        openingMediaLayout = false;
      }, 300L);

      currentMediaLayout = mediaLayout;
    });
  }


  /* Callbacks */    // FIXME: video and webp file checks

  private void onProfilePhotoReceived (ImageGalleryFile file, boolean isPublic) {
    Media.instance().post(() -> {
      final TdApi.InputFileGenerated inputFile = PhotoGenerationInfo.newFile(file);
      UI.post(() -> setProfilePhoto(inputFile, isPublic));
    });
  }

  private void onChatPhotoReceived (ImageGalleryFile file, long chatId) {
    Media.instance().post(() -> {
      final TdApi.InputFileGenerated inputFile = PhotoGenerationInfo.newFile(file);
      UI.post(() -> setChatPhoto(chatId, inputFile));
    });
  }


  /* Activity Result */   // TODO: show editor

  public boolean handleActivityResult (int requestCode, int resultCode, Intent data, int mode, @Nullable TdApi.Chat chat, @Nullable EditHeaderView headerView) {
    if (resultCode != Activity.RESULT_OK) {
      return false;
    }

    if (requestCode == Intents.ACTIVITY_RESULT_IMAGE_CAPTURE) {
      File image = Intents.takeLastOutputMedia();
      if (image != null) {
        if (!Settings.instance().getNewSetting(Settings.SETTING_FLAG_DISABLE_CAMERA_PHOTO_GALLERY_SAVE)) {
          U.addToGallery(image);
        }
        handleActivitySetPhoto(image.getPath(), mode, chat, headerView);
      }
      return true;
    } else if (requestCode == Intents.ACTIVITY_RESULT_GALLERY) {
      if (data == null) {
        UI.showToast("Error", Toast.LENGTH_SHORT);
        return false;
      }

      final Uri image = data.getData();
      if (image != null) {
        String imagePath = U.tryResolveFilePath(image);
        if (imagePath != null) {
          if (imagePath.endsWith(".webp")) {
            UI.showToast("Webp is not supported for profile photos", Toast.LENGTH_LONG);
            return false;
          }
          handleActivitySetPhoto(imagePath, mode, chat, headerView);
        }
      }
      return true;
    }
    return false;
  }

  private void handleActivitySetPhoto (String path, int mode, @Nullable TdApi.Chat chat, @Nullable EditHeaderView headerView) {
    if (mode == MODE_PROFILE || mode == MODE_PROFILE_PUBLIC) {
      setProfilePhoto(new TdApi.InputFileGenerated(path, SimpleGenerationInfo.makeConversion(path), 0), mode == MODE_PROFILE_PUBLIC);
    } else if (mode == MODE_CHAT && chat != null) {
      setChatPhoto(chat.id, new TdApi.InputFileGenerated(path, SimpleGenerationInfo.makeConversion(path), 0));
    } else if (mode == MODE_NON_CREATED && headerView != null) {
      U.toGalleryFile(new File(path), false, headerView::setPhoto);
    }
  }


  /* Setters */

  private void setProfilePhoto (TdApi.InputFileGenerated inputFile, boolean isPublic) {
    UI.showToast(R.string.UploadingPhotoWait, Toast.LENGTH_SHORT);
    tdlib.client().send(new TdApi.SetProfilePhoto(new TdApi.InputChatPhotoStatic(inputFile), isPublic), tdlib.profilePhotoHandler());
  }

  private void deleteProfilePhoto (long profilePhotoId) {
    tdlib.send(new TdApi.DeleteProfilePhoto(profilePhotoId), tdlib.typedOkHandler());
  }

  private void setChatPhoto (long chatId, @Nullable TdApi.InputFileGenerated inputFile) {
    if (inputFile != null) {
      UI.showToast(R.string.UploadingPhotoWait, Toast.LENGTH_SHORT);
    }
    TdApi.InputChatPhoto chatPhoto = inputFile != null ? new TdApi.InputChatPhotoStatic(inputFile) : null;
    TdApi.Function<TdApi.Ok> function;
    if (tdlib.canEditBotChat(chatId)) {
      function = new TdApi.SetBotProfilePhoto(tdlib.chatUserId(chatId), chatPhoto);
    } else {
      function = new TdApi.SetChatPhoto(chatId, chatPhoto);
    }
    tdlib.send(function, tdlib.typedOkHandler());
  }
}
