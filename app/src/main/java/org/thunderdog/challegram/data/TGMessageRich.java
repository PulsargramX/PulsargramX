/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.tool.UI;

/**
 * Read-only incoming rich message.
 */
public final class TGMessageRich extends TGMessage implements RichMessageLayout.Callback {
  private TdApi.RichMessage sourceMessage;
  private @Nullable TdApi.RichMessage fullMessage;
  private @Nullable TdApi.RichMessage translatedMessage;
  private RichMessageLayout layout;
  private TdApi.FormattedText selectionText = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
  private String contentIdentity;
  private @Nullable String pendingAnchor;
  private final RichMessageRequestGuard requestGuard = new RichMessageRequestGuard();

  public TGMessageRich (MessagesManager context, TdApi.Message message,
                        @NonNull TdApi.MessageRichMessage content) {
    super(context, message);
    sourceMessage = nonNullRich(content.message);
    contentIdentity = RichMessageUtils.deterministicSource(sourceMessage);
    layout = new RichMessageLayout(this, sourceMessage, this, null);
  }

  public @NonNull TdApi.RichMessage getDisplayedRichMessage () {
    return translatedMessage != null ? translatedMessage :
      fullMessage != null ? fullMessage : sourceMessage;
  }

  public @NonNull String getPlainText () {
    return RichMessageUtils.toPlainText(getDisplayedRichMessage());
  }

  public @NonNull String getHtml () {
    return layout.getHtml();
  }

  @Override
  protected int getBubbleContentPadding () {
    return xBubblePadding + xBubblePaddingSmall;
  }

  @Override
  protected void buildContent (int maxWidth) {
    layout.measure(maxWidth);
    selectionText = layout.getSelectionText();
  }

  @Override
  protected int getContentHeight () {
    return layout.getHeight();
  }

  @Override
  protected int getBottomLineContentWidth () {
    return layout.canInlineTime() ? layout.getLastLineWidth() : BOTTOM_LINE_EXPAND_HEIGHT;
  }

  @Override
  public boolean needComplexReceiver () {
    return true;
  }

  @Override
  public void autoDownloadContent (TdApi.ChatType type) {
    layout.autoDownloadContent(type);
  }

  @Override
  public void requestMediaContent (ComplexReceiver receiver, boolean invalidate, int invalidateArg) {
    layout.requestMedia(receiver);
  }

  @Override
  protected void drawContent (MessageView view, Canvas canvas, int startX, int startY,
                              int maxWidth, ComplexReceiver receiver) {
    layout.draw(view, canvas, startX, startY, receiver, getTranslationLoadingAlphaValue());
  }

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent event) {
    if (super.onTouchEvent(view, event)) {
      return true;
    }
    return layout.onTouchEvent(view, event, getContentX(), getContentY());
  }

  public void cancelTouch (MessageView view) {
    layout.cancelTouch(view);
  }

  public boolean hasScrollableTouch () {
    return layout.hasScrollableTouch();
  }

  public boolean canScrollForDragAt (float x, float y, boolean towardRight) {
    return layout.canScrollForDragAt(x - getContentX(), y - getContentY(), towardRight);
  }

  @Override
  protected boolean onAnchorClick (View view, String anchor) {
    if (layout.navigateToAnchor(view, anchor)) return true;
    if (!getDisplayedRichMessage().isFull && !isPendingMessage()) {
      pendingAnchor = anchor;
      onRequestFullMessage();
      return true;
    }
    return false;
  }

  @Override
  protected void onMessageAttachedToView (@NonNull MessageView view, boolean attached) {
    if (attached) {
      layout.attach(view);
    } else {
      layout.detach(view);
    }
  }

  @Override
  protected boolean isSupportedMessageContent (TdApi.Message message,
                                               TdApi.MessageContent messageContent) {
    return messageContent.getConstructor() == TdApi.MessageRichMessage.CONSTRUCTOR;
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent,
                                          boolean isBottomMessage) {
    TdApi.RichMessage next = nonNullRich(((TdApi.MessageRichMessage) newContent).message);
    String nextIdentity = RichMessageUtils.deterministicSource(next);
    boolean changed = !contentIdentity.equals(nextIdentity);
    sourceMessage = next;
    if (changed) {
      pendingAnchor = null;
      contentIdentity = nextIdentity;
      fullMessage = null;
      translatedMessage = null;
      requestGuard.invalidate();
      layout.setMessage(sourceMessage);
      layout.setPartialLoading(false, false);
      selectionText = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
      rebuildContent();
      invalidateMediaContentForAllViews();
    }
    return changed;
  }

  @Override
  protected void onMessageIdChanged (long oldMessageId, long newMessageId, boolean success) {
    requestGuard.invalidate();
  }

  @Override
  protected void onMessageContainerDestroyed () {
    requestGuard.invalidate();
    layout.performDestroy();
  }

  @Override
  public @Nullable MessageTextSelectionTarget findSelectableTextTarget (float x, float y) {
    x -= getMessageTextSelectionTranslationX();
    if (selectionText.text.isEmpty() || x < getContentX() ||
        x > getContentX() + getContentWidth() ||
        y < getContentY() || y > getContentY() + getContentHeight()) {
      return null;
    }
    return new MessageTextSelectionTarget(
      getChatId(),
      getId(),
      getId(),
      MessageTextSelectionTarget.Kind.BODY,
      this,
      layout,
      selectionText,
      null,
      true,
      false,
      true
    );
  }

  @Override
  public boolean hasSelectableTextTarget () {
    return !selectionText.text.isEmpty();
  }

  @Nullable
  @Override
  public AccessibilityNodeProvider getAccessibilityNodeProvider (
    @NonNull MessageView view) {
    return layout.getAccessibilityNodeProvider(view);
  }

  @Override
  public boolean isSelectableTextTargetValid (@NonNull MessageTextSelectionTarget target) {
    return target.container == this && target.surface == layout &&
      target.displayedText == selectionText &&
      target.layoutRevision == layout.getLayoutRevision();
  }

  @Nullable
  @Override
  protected TdApi.FormattedText getTextToTranslateImpl () {
    return isPendingMessage() ? null : RichMessageUtils.toSearchableText(sourceMessage);
  }

  @Override
  public @NonNull TranslationAdapter getTranslationAdapter () {
    return new TranslationAdapter() {
      @NonNull
      @Override
      public String sourceIdentity () {
        return getChatId() + ":" + getId() + ":" + contentIdentity;
      }

      @Nullable
      @Override
      public TdApi.FormattedText languageDetectionText () {
        return RichMessageUtils.toSearchableText(sourceMessage);
      }

      @Override
      public void request (@NonNull org.thunderdog.challegram.telegram.Tdlib tdlib,
                           @NonNull String targetLanguage, @NonNull String tone,
                           @NonNull org.drinkless.tdlib.Client.ResultHandler callback) {
        tdlib.client().send(new TdApi.TranslateMessageRichMessage(
          getChatId(), getId(), targetLanguage, tone), callback);
      }

      @Nullable
      @Override
      public TdApi.Object validate (@NonNull TdApi.Object response) {
        return response instanceof TdApi.RichMessage ? response : null;
      }
    };
  }

  @Override
  protected void setTranslationObject (@Nullable TdApi.Object object) {
    translatedMessage = object instanceof TdApi.RichMessage ?
      (TdApi.RichMessage) object : null;
    replaceLayout(getDisplayedRichMessage());
    rebuildAndUpdateContent();
    invalidateMediaContentForAllViews();
  }

  @Override
  public void onRequestFullMessage () {
    if (isPendingMessage() || sourceMessage.isFull || fullMessage != null || requestGuard.isInFlight()) {
      return;
    }
    final long chatId = getChatId();
    final long messageId = getId();
    final String identity = contentIdentity;
    final RichMessageRequestGuard.Token token =
      requestGuard.begin(chatId, messageId, identity);
    if (token == null) {
      return;
    }
    layout.setPartialLoading(true, false);
    tdlib.client().send(new TdApi.GetFullRichMessage(chatId, messageId), object ->
      tdlib.ui().post(() -> {
        if (isDestroyed() || !requestGuard.complete(token, getChatId(),
            getId(), contentIdentity)) {
          return;
        }
        if (object instanceof TdApi.RichMessage) {
          fullMessage = (TdApi.RichMessage) object;
          translatedMessage = null;
          replaceLayout(fullMessage);
          rebuildAndUpdateContent();
          invalidateMediaContentForAllViews();
          if (pendingAnchor != null) {
            layout.navigateToAnchor(null, pendingAnchor);
            pendingAnchor = null;
          }
        } else {
          layout.setPartialLoading(false, true);
          if (object instanceof TdApi.Error) {
            UI.showError(object);
          }
        }
      })
    );
  }

  private void replaceLayout (@NonNull TdApi.RichMessage message) {
    RichMessageLayout previous = layout;
    layout = new RichMessageLayout(this, message, this, previous);
    previous.performDestroy();
    selectionText = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
  }

  private void invalidateMediaContentForAllViews () {
    invalidateContentReceiver();
  }

  private static TdApi.RichMessage nonNullRich (@Nullable TdApi.RichMessage message) {
    return message != null ? message :
      new TdApi.RichMessage(new TdApi.PageBlock[0], false, true);
  }
}
