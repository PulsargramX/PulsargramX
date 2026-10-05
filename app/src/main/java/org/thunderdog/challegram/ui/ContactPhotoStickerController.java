/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.sticker.StickerSmallView;
import org.thunderdog.challegram.component.sticker.TGStickerObj;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.EmojiLayout;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableData;

/** Builds a server-rendered avatar from a sticker or custom emoji and a gradient. */
public class ContactPhotoStickerController extends ViewController<Void> implements EmojiLayout.Listener {
  private static final int[][] BACKGROUNDS = {
    {0x6fb8ff, 0x4665dc}, {0xf4a46b, 0xe65c78}, {0x95ce74, 0x3f9c83},
    {0xc38aea, 0x7957c4}, {0x72d5d1, 0x368bb3}, {0xf0c95e, 0xd88e3f}
  };

  private final RunnableData<TdApi.InputChatPhoto> callback;
  private final CharSequence title;
  private EmojiLayout emojiLayout;
  private StickerSmallView preview;
  private TextView done;
  private TdApi.ChatPhotoStickerType stickerType;
  private int backgroundIndex;
  private int stickerRequest;

  public ContactPhotoStickerController (Context context, Tdlib tdlib, CharSequence title,
                                       RunnableData<TdApi.InputChatPhoto> callback) {
    super(context, tdlib);
    this.title = title;
    this.callback = callback;
  }

  @Override
  public int getId () {
    return R.id.controller_contact_photo_sticker;
  }

  @Override
  public CharSequence getName () {
    return title;
  }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_BACK;
  }

  @Override
  protected View onCreateView (Context context) {
    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Theme.fillingColor());
    addThemeBackgroundColorListener(root, ColorId.filling);

    FrameLayoutFix previewWrap = new FrameLayoutFix(context);
    preview = new StickerSmallView(context, Screen.dp(24));
    preview.init(tdlib);
    preview.setEnabled(false);
    updateBackground();
    previewWrap.addView(preview, FrameLayoutFix.newParams(Screen.dp(144), Screen.dp(144), Gravity.CENTER));
    root.addView(previewWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(160)));

    HorizontalScrollView colorsScroll = new HorizontalScrollView(context);
    colorsScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout colors = new LinearLayout(context);
    colors.setGravity(Gravity.CENTER);
    for (int i = 0; i < BACKGROUNDS.length; i++) {
      final int index = i;
      View color = new View(context);
      color.setContentDescription(Lang.getString(R.string.ContactPhotoBackground, i + 1));
      color.setBackground(makeBackground(i));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Screen.dp(32), Screen.dp(32));
      params.setMargins(Screen.dp(10), Screen.dp(6), Screen.dp(10), Screen.dp(6));
      colors.addView(color, params);
      color.setOnClickListener(v -> {
        backgroundIndex = index;
        updateBackground();
      });
    }
    colorsScroll.addView(colors);
    root.addView(colorsScroll);

    done = new TextView(context);
    done.setText(Lang.getString(R.string.Continue));
    done.setTextSize(16);
    done.setGravity(Gravity.CENTER);
    done.setTextColor(Theme.getColor(ColorId.textNeutral));
    addThemeTextColorListener(done, ColorId.textNeutral);
    done.setEnabled(false);
    root.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48)));
    done.setOnClickListener(v -> {
      if (stickerType != null) {
        int[] colorsPair = BACKGROUNDS[backgroundIndex];
        TdApi.InputChatPhoto photo = new TdApi.InputChatPhotoSticker(new TdApi.ChatPhotoSticker(
          stickerType, new TdApi.BackgroundFillGradient(colorsPair[0], colorsPair[1], 0)));
        navigateBack();
        callback.runWithData(photo);
      }
    });

    emojiLayout = new EmojiLayout(context);
    emojiLayout.setAllowPremiumFeatures(true);
    emojiLayout.initWithMediasEnabled(this, true, this, this, false);
    root.addView(emojiLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    final int defaultRequest = stickerRequest;
    tdlib.send(new TdApi.GetDefaultProfilePhotoCustomEmojiStickers(), (stickers, error) ->
      tdlib.ui().post(() -> {
        if (!isDestroyed() && defaultRequest == stickerRequest && stickers != null && stickers.stickers.length > 0) {
          selectSticker(new TGStickerObj(tdlib, stickers.stickers[0], (String) null, stickers.stickers[0].fullType));
        }
      }));
    return root;
  }

  private GradientDrawable makeBackground (int index) {
    int[] colors = BACKGROUNDS[index];
    GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
      new int[] {0xff000000 | colors[0], 0xff000000 | colors[1]});
    background.setShape(GradientDrawable.OVAL);
    return background;
  }

  private void updateBackground () {
    preview.setBackground(makeBackground(backgroundIndex));
  }

  private boolean selectSticker (TGStickerObj sticker) {
    TdApi.Sticker data = sticker.getSticker();
    long customEmojiId = sticker.getCustomEmojiId();
    if (data == null || (sticker.isCustomEmoji() ? customEmojiId == 0 : data.setId == 0 || data.id == 0)) {
      UI.showToast(R.string.ContactPhotoChooseSticker, Toast.LENGTH_SHORT);
      return false;
    }
    int request = ++stickerRequest;
    stickerType = null;
    done.setEnabled(false);
    preview.setSticker(sticker);
    if (sticker.isCustomEmoji()) {
      stickerType = new TdApi.ChatPhotoStickerTypeCustomEmoji(customEmojiId);
      done.setEnabled(true);
    } else {
      // TDLib checks membership in the loaded set for chatPhotoStickerTypeRegularOrMask.
      // Knowing a sticker from recents, favorites or set covers is not sufficient.
      tdlib.send(new TdApi.GetStickerSet(data.setId), (set, error) -> tdlib.ui().post(() -> {
        if (isDestroyed() || request != stickerRequest) {
          return;
        }
        if (error != null) {
          UI.showError(error);
        } else if (!applyStickerSet(set, data)) {
          if (!StringUtils.isEmpty(set.name)) {
            // GetStickerSet may return a stale cached set while reloading it in the background.
            tdlib.send(new TdApi.SearchStickerSet(set.name, true), (reloadedSet, reloadError) -> tdlib.ui().post(() -> {
              if (isDestroyed() || request != stickerRequest) {
                return;
              }
              if (reloadError != null) {
                UI.showError(reloadError);
              } else if (!applyStickerSet(reloadedSet, data)) {
                UI.showToast(R.string.ContactPhotoChooseSticker, Toast.LENGTH_SHORT);
              }
            }));
          } else {
            UI.showToast(R.string.ContactPhotoChooseSticker, Toast.LENGTH_SHORT);
          }
        }
      }));
    }
    return true;
  }

  private boolean applyStickerSet (TdApi.StickerSet set, TdApi.Sticker selectedSticker) {
    if (set.id == selectedSticker.setId) {
      for (TdApi.Sticker sticker : set.stickers) {
        if (sticker.id == selectedSticker.id) {
          stickerType = new TdApi.ChatPhotoStickerTypeRegularOrMask(set.id, sticker.id);
          done.setEnabled(true);
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void onEnterCustomEmoji (TGStickerObj sticker) {
    selectSticker(sticker);
  }

  @Override
  public boolean onSendSticker (View view, TGStickerObj sticker, TdApi.MessageSendOptions options) {
    return selectSticker(sticker);
  }

  @Override
  public void onEnterEmoji (String emoji) {
    int request = ++stickerRequest;
    stickerType = null;
    done.setEnabled(false);
    tdlib.send(new TdApi.GetAnimatedEmoji(emoji), (animated, error) -> tdlib.ui().post(() -> {
      if (isDestroyed() || request != stickerRequest) {
        return;
      }
      if (animated != null && animated.sticker != null) {
        selectSticker(new TGStickerObj(tdlib, animated.sticker, (String) null, animated.sticker.fullType));
      } else {
        UI.showToast(R.string.ContactPhotoChooseSticker, Toast.LENGTH_SHORT);
      }
    }));
  }

  @Override
  public boolean onSendGIF (View view, TdApi.Animation animation) {
    UI.showToast(R.string.ContactPhotoChooseSticker, Toast.LENGTH_SHORT);
    return false;
  }

  @Override
  public void destroy () {
    super.destroy();
    if (emojiLayout != null) {
      emojiLayout.destroy();
    }
    if (preview != null) {
      preview.performDestroy();
    }
  }
}
