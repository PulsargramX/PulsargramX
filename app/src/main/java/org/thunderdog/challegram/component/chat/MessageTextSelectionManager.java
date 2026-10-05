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
 */
package org.thunderdog.challegram.component.chat;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Magnifier;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.MessageTextSelectionTarget;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.MessageTextSelectionBoundary;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.v.MessagesRecyclerView;

import java.util.ArrayList;

import tgx.td.Td;
import tgx.td.data.MessageWithProperties;

public final class MessageTextSelectionManager {
  public static final int HANDLE_NONE = 0;
  public static final int HANDLE_START = 1;
  public static final int HANDLE_END = 2;
  private static final int HANDLE_INITIAL = 3;

  public static final int ACTION_COPY = 1;
  public static final int ACTION_QUOTE = 2;
  public static final int ACTION_SELECT_ALL = 3;

  private static final String HINT_COUNT_KEY = "message_text_selection_hint_count";

  private final MessagesController controller;
  private final MessagesRecyclerView recyclerView;
  private final MessageTextSelectionOverlay overlay;
  private final Text.SelectionHit hit = new Text.SelectionHit();
  private final Text.SelectionGeometry geometry = new Text.SelectionGeometry();
  private final Rect actionViewport = new Rect();
  private final RectF actionContentRect = new RectF();
  private final MessageTextSelectionBoundary.Range range =
    new MessageTextSelectionBoundary.Range();
  private final int[] viewLocation = new int[2];
  private final int[] overlayLocation = new int[2];

  private @Nullable MessageTextSelectionTarget target;
  private @Nullable MessageTextSelectionBoundary boundaries;
  private @Nullable MessageView activeView;
  private int start;
  private int end;
  private int seedStart;
  private int seedEnd;
  private int activeHandle;
  private boolean dragging;
  private boolean rejectedLongPressConsumed;
  private boolean recyclerScrolling;
  private boolean destroyed;

  private float viewOffsetX;
  private float viewOffsetY;
  private float startHandleX;
  private float startHandleY;
  private float endHandleX;
  private float endHandleY;
  private boolean startHandleRtl;
  private boolean endHandleRtl;
  private float actionAnchorX;
  private float actionAnchorTop;
  private float actionAnchorBottom;

  private float lastPointerX;
  private float lastPointerY;
  private float handleTouchOffsetX;
  private float handleTouchOffsetY;
  private int autoScrollSpeed;
  private boolean autoScrollPosted;

  private @Nullable ActionMode actionMode;
  private boolean finishingActionMode;
  private @Nullable Object magnifier;

  private final Runnable autoScrollRunnable = new Runnable() {
    @Override
    public void run () {
      autoScrollPosted = false;
      if (!dragging || autoScrollSpeed == 0 || destroyed) {
        return;
      }
      if (!recyclerView.canScrollVertically(autoScrollSpeed > 0 ? 1 : -1)) {
        autoScrollSpeed = 0;
        return;
      }
      dismissMagnifier();
      recyclerView.scrollBy(0, autoScrollSpeed);
      updateFromOverlay(lastPointerX, lastPointerY, true);
      overlay.invalidate();
      scheduleAutoScroll();
    }
  };

  public MessageTextSelectionManager (
    @NonNull MessagesController controller,
    @NonNull MessagesRecyclerView recyclerView
  ) {
    this.controller = controller;
    this.recyclerView = recyclerView;
    this.overlay = new MessageTextSelectionOverlay(recyclerView.getContext(), this);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrollStateChanged (
        @NonNull RecyclerView recyclerView,
        int newState
      ) {
        recyclerScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
        if (recyclerScrolling) {
          hideActions();
          dismissMagnifier();
        } else if (isActive() && !dragging) {
          overlay.post(MessageTextSelectionManager.this::showActions);
        }
        overlay.invalidate();
      }

      @Override
      public void onScrolled (
        @NonNull RecyclerView recyclerView,
        int dx,
        int dy
      ) {
        if (isActive()) {
          overlay.invalidate();
        }
      }
    });
  }

  public MessageTextSelectionOverlay getOverlay () {
    return overlay;
  }

  public boolean isActive () {
    return target != null;
  }

  public boolean tryStartSelection (
    @NonNull MessageView view,
    @NonNull TGMessage message,
    float x,
    float y
  ) {
    rejectedLongPressConsumed = false;
    if (destroyed || isActive() || !controller.inSelectMode()) {
      return false;
    }
    MessageTextSelectionTarget target = message.findSelectableTextTarget(x, y);
    if (target == null ||
        !controller.isMessageSelected(
          target.chatId,
          target.selectionMessageId,
          message
        )) {
      return false;
    }
    rejectedLongPressConsumed = true;
    float textX = x - message.getMessageTextSelectionTranslationX();
    if (!target.surface.hitTestSelection(textX, y, hit, false) ||
        hit.unrevealedSpoiler) {
      return false;
    }

    MessageTextSelectionBoundary boundaries = new MessageTextSelectionBoundary(
      target.displayedText.text,
      target.displayedText.entities,
      Lang.locale()
    );
    boundaries.rangeAt(hit.characterOffset, range);
    if (range.isEmpty()) {
      return false;
    }

    this.target = target;
    this.boundaries = boundaries;
    this.activeView = view;
    this.start = this.seedStart = range.start;
    this.end = this.seedEnd = range.end;
    this.activeHandle = HANDLE_INITIAL;
    this.dragging = true;
    target.surface.setSelection(start, end);
    overlay.setActive(true);
    overlay.invalidate();
    Settings.instance().putInt(HINT_COUNT_KEY, 3);
    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    announceSelection();
    return true;
  }

  public boolean consumeRejectedLongPress () {
    boolean consumed = rejectedLongPressConsumed;
    rejectedLongPressConsumed = false;
    return consumed;
  }

  public boolean onInitialTouch (@NonNull MessageView view, @NonNull MotionEvent event) {
    if (!isActive() || activeView != view || !dragging) {
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_MOVE:
        if (event.getPointerCount() != 1) {
          finishDrag();
          return true;
        }
        updateFromLocal(event.getX(), event.getY(), true);
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_CANCEL:
        finishDrag();
        return true;
      case MotionEvent.ACTION_UP:
        updateFromLocal(event.getX(), event.getY(), true);
        finishDrag();
        return true;
      default:
        return true;
    }
  }

  public boolean onHandleTouch (
    int handle,
    @NonNull MotionEvent event,
    float overlayX,
    float overlayY
  ) {
    if (!isActive()) {
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        activeHandle = handle;
        dragging = true;
        float handleX = handle == HANDLE_START ? startHandleX : endHandleX;
        float handleY = handle == HANDLE_START ? startHandleY : endHandleY;
        handleTouchOffsetX = handleX - overlayX;
        handleTouchOffsetY = handleY - overlayY;
        lastPointerX = overlayX;
        lastPointerY = overlayY;
        hideActions();
        overlay.getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (event.getPointerCount() != 1) {
          finishDrag();
          return true;
        }
        updateFromOverlay(
          overlayX + handleTouchOffsetX,
          overlayY + handleTouchOffsetY,
          true
        );
        updateAutoScroll(overlayY);
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_CANCEL:
        finishDrag();
        return true;
      case MotionEvent.ACTION_UP:
        updateFromOverlay(
          overlayX + handleTouchOffsetX,
          overlayY + handleTouchOffsetY,
          true
        );
        finishDrag();
        return true;
      default:
        return true;
    }
  }

  public boolean onOverlayTouch (@NonNull MotionEvent event) {
    return false;
  }

  private void updateFromLocal (float x, float y, boolean clampToText) {
    MessageTextSelectionTarget target = this.target;
    MessageTextSelectionBoundary boundaries = this.boundaries;
    if (target == null || boundaries == null ||
        !target.surface.hitTestSelection(
          x - target.container.getMessageTextSelectionTranslationX(),
          y,
          hit,
          clampToText
        )) {
      return;
    }
    updateRange(boundaries.nearest(hit.offset));
    showMagnifier(x, y);
  }

  private void updateFromOverlay (float x, float y, boolean clampToText) {
    MessageView view = resolveActiveView();
    if (view == null) {
      return;
    }
    updateViewOffset(view);
    lastPointerX = x;
    lastPointerY = y;
    updateFromLocal(x - viewOffsetX, y - viewOffsetY, clampToText);
  }

  private void updateRange (int offset) {
    MessageTextSelectionTarget target = this.target;
    if (target == null) {
      return;
    }
    if (activeHandle == HANDLE_INITIAL) {
      if (offset < seedStart) {
        activeHandle = HANDLE_START;
      } else if (offset > seedEnd) {
        activeHandle = HANDLE_END;
      } else {
        return;
      }
    }

    int oldStart = start;
    int oldEnd = end;
    if (activeHandle == HANDLE_START) {
      if (offset < end) {
        start = offset;
      } else if (offset > end) {
        start = end;
        end = offset;
        activeHandle = HANDLE_END;
      }
    } else if (activeHandle == HANDLE_END) {
      if (offset > start) {
        end = offset;
      } else if (offset < start) {
        end = start;
        start = offset;
        activeHandle = HANDLE_START;
      }
    }
    if (start == end) {
      start = oldStart;
      end = oldEnd;
    }
    if (oldStart != start || oldEnd != end) {
      target.surface.setSelection(start, end);
      performSelectionMoveHaptic();
      overlay.invalidate();
      if (actionMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        actionMode.invalidate();
        actionMode.invalidateContentRect();
      }
    }
  }

  private void performSelectionMoveHaptic () {
    boolean performed = false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      performed = overlay.performHapticFeedback(
        HapticFeedbackConstants.TEXT_HANDLE_MOVE
      );
    }
    if (!performed) {
      overlay.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
  }

  private void finishDrag () {
    dragging = false;
    activeHandle = HANDLE_NONE;
    stopAutoScroll();
    dismissMagnifier();
    if (overlay.getParent() != null) {
      overlay.getParent().requestDisallowInterceptTouchEvent(false);
    }
    overlay.invalidate();
    overlay.announceForAccessibility(Lang.getString(R.string.TextSelectionChanged));
    showActions();
  }

  private void updateAutoScroll (float y) {
    int zone = Screen.dp(52f);
    int height = overlay.getHeight();
    int speed = 0;
    if (y < zone) {
      speed = -Math.max(Screen.dp(2f), Math.round((zone - y) / zone * Screen.dp(18f)));
    } else if (y > height - zone) {
      speed = Math.max(
        Screen.dp(2f),
        Math.round((y - (height - zone)) / zone * Screen.dp(18f))
      );
    }
    autoScrollSpeed = speed;
    if (speed != 0) {
      hideActions();
      scheduleAutoScroll();
    }
  }

  private void scheduleAutoScroll () {
    if (!autoScrollPosted && autoScrollSpeed != 0) {
      autoScrollPosted = true;
      overlay.postOnAnimation(autoScrollRunnable);
    }
  }

  private void stopAutoScroll () {
    autoScrollSpeed = 0;
    if (autoScrollPosted) {
      overlay.removeCallbacks(autoScrollRunnable);
      autoScrollPosted = false;
    }
  }

  public boolean prepareOverlayGeometry (
    @NonNull View overlayView,
    @NonNull Rect viewport
  ) {
    MessageTextSelectionTarget target = this.target;
    MessageView view = resolveActiveView();
    if (target == null || !target.container.isSelectableTextTargetValid(target)) {
      cancel();
      return false;
    }
    if (view == null ||
        !target.surface.buildSelectionGeometry(start, end, geometry)) {
      return false;
    }
    viewport.set(
      overlayView.getPaddingLeft(),
      overlayView.getPaddingTop(),
      overlayView.getWidth() - overlayView.getPaddingRight(),
      overlayView.getHeight() - overlayView.getPaddingBottom()
    );
    updateViewOffset(view);
    float contentTranslationX =
      target.container.getMessageTextSelectionTranslationX();
    startHandleX = geometry.start.x + contentTranslationX + viewOffsetX;
    startHandleY = geometry.start.lineBottom + viewOffsetY;
    endHandleX = geometry.end.x + contentTranslationX + viewOffsetX;
    endHandleY = geometry.end.lineBottom + viewOffsetY;
    startHandleRtl = geometry.start.rtl;
    endHandleRtl = geometry.end.rtl;
    RectF first = null;
    RectF last = null;
    for (int index = 0; index < geometry.getRectangleCount(); index++) {
      RectF rect = geometry.rectangles.get(index);
      if (rect.right + contentTranslationX + viewOffsetX >= viewport.left &&
          rect.left + contentTranslationX + viewOffsetX <= viewport.right &&
          rect.bottom + viewOffsetY >= viewport.top &&
          rect.top + viewOffsetY <= viewport.bottom) {
        if (first == null) {
          first = rect;
        }
        last = rect;
      }
    }
    if (first == null) {
      actionContentRect.setEmpty();
      return true;
    }
    actionAnchorX = (first.left + first.right) / 2f +
      contentTranslationX + viewOffsetX;
    actionAnchorTop = first.top + viewOffsetY;
    actionAnchorBottom = last.bottom + viewOffsetY;
    actionContentRect.set(
      first.left + contentTranslationX + viewOffsetX,
      first.top + viewOffsetY,
      first.right + contentTranslationX + viewOffsetX,
      first.bottom + viewOffsetY
    );
    return true;
  }

  private void updateViewOffset (@NonNull MessageView view) {
    view.getLocationInWindow(viewLocation);
    overlay.getLocationInWindow(overlayLocation);
    viewOffsetX = viewLocation[0] - overlayLocation[0];
    viewOffsetY = viewLocation[1] - overlayLocation[1];
  }

  private @Nullable MessageView resolveActiveView () {
    if (activeView != null && activeView.getWindowToken() != null &&
        target != null && activeView.getMessage() == target.container) {
      return activeView;
    }
    activeView = null;
    MessageTextSelectionTarget target = this.target;
    if (target == null) {
      return null;
    }
    for (int index = 0; index < recyclerView.getChildCount(); index++) {
      MessageView found = findMessageView(
        recyclerView.getChildAt(index),
        target.container
      );
      if (found != null) {
        activeView = found;
        return found;
      }
    }
    return null;
  }

  private static @Nullable MessageView findMessageView (
    @NonNull View view,
    @NonNull TGMessage target
  ) {
    if (view instanceof MessageView && ((MessageView) view).getMessage() == target) {
      return (MessageView) view;
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        MessageView found = findMessageView(group.getChildAt(index), target);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  public void performAction (int action) {
    switch (action) {
      case ACTION_COPY:
        copy();
        break;
      case ACTION_QUOTE:
        quote();
        break;
      case ACTION_SELECT_ALL:
        selectAll();
        break;
    }
  }

  private void copy () {
    MessageTextSelectionTarget target = this.target;
    if (target == null || !target.canCopy) {
      return;
    }
    String plainText = target.surface.copyPlainText(start, end);
    String html = target.surface.copyHtml(start, end);
    if (html != null) {
      U.copyText(plainText, html);
      UI.showToast(R.string.CopiedText, android.widget.Toast.LENGTH_SHORT);
    } else {
      TdApi.FormattedText selected = Td.substring(target.displayedText, start, end);
      UI.copyText(TD.toCopyText(selected), R.string.CopiedText);
    }
    finishBothSelectionLayers();
  }

  private void selectAll () {
    MessageTextSelectionTarget target = this.target;
    MessageTextSelectionBoundary boundaries = this.boundaries;
    if (target == null || boundaries == null || !target.canSelectAll) {
      return;
    }
    start = boundaries.atOrBefore(0);
    end = boundaries.atOrBefore(boundaries.length());
    target.surface.setSelection(start, end);
    hideActions();
    overlay.invalidate();
    overlay.post(this::showActions);
    announceSelection();
  }

  private void quote () {
    MessageTextSelectionTarget target = this.target;
    MessageTextSelectionBoundary boundaries = this.boundaries;
    if (target == null || boundaries == null || !canQuote()) {
      return;
    }
    TdApi.Message message = target.container.getMessage(target.replyMessageId);
    TdApi.MessageProperties properties =
      target.container.lastMessageProperties(target.replyMessageId);
    if (message == null || properties == null) {
      return;
    }
    int maxLength = controller.tdlib().options().messageReplyQuoteLengthMax;
    int quoteEnd = end;
    if (quoteEnd - start > maxLength) {
      quoteEnd = boundaries.atOrBefore(start + maxLength);
    }
    if (quoteEnd <= start) {
      return;
    }
    TdApi.FormattedText quoteText = Td.substring(target.originalText, start, quoteEnd);
    quoteText.entities = filterQuoteEntities(quoteText.entities);
    TdApi.InputTextQuote quote = new TdApi.InputTextQuote(quoteText, start);
    cancel();
    controller.showReply(
      new MessageWithProperties(message, properties),
      quote,
      0,
      "",
      true,
      true
    );
  }

  private static TdApi.TextEntity[] filterQuoteEntities (
    @Nullable TdApi.TextEntity[] entities
  ) {
    if (entities == null || entities.length == 0) {
      return new TdApi.TextEntity[0];
    }
    ArrayList<TdApi.TextEntity> filtered = new ArrayList<>(entities.length);
    for (TdApi.TextEntity entity : entities) {
      switch (entity.type.getConstructor()) {
        case TdApi.TextEntityTypeBold.CONSTRUCTOR:
        case TdApi.TextEntityTypeItalic.CONSTRUCTOR:
        case TdApi.TextEntityTypeUnderline.CONSTRUCTOR:
        case TdApi.TextEntityTypeStrikethrough.CONSTRUCTOR:
        case TdApi.TextEntityTypeSpoiler.CONSTRUCTOR:
        case TdApi.TextEntityTypeCustomEmoji.CONSTRUCTOR:
        case TdApi.TextEntityTypeDateTime.CONSTRUCTOR:
          filtered.add(entity);
          break;
      }
    }
    return filtered.toArray(new TdApi.TextEntity[0]);
  }

  private boolean canQuote () {
    MessageTextSelectionTarget target = this.target;
    if (target == null || !target.canQuote || !target.hasIdentitySourceMapping() ||
        !controller.canWriteMessages()) {
      return false;
    }
    TdApi.Message message = target.container.getMessage(target.replyMessageId);
    return message != null && TD.canReplyTo(message);
  }

  private boolean canSelectAll () {
    MessageTextSelectionTarget target = this.target;
    return target != null && target.canSelectAll &&
      (start != 0 || end != target.displayedText.text.length());
  }

  private void finishBothSelectionLayers () {
    cancel();
    controller.finishSelectMode(-1);
  }

  public void cancel () {
    MessageTextSelectionTarget target = this.target;
    if (target == null) {
      return;
    }
    stopAutoScroll();
    dismissMagnifier();
    hideActions();
    target.surface.clearSelection();
    this.target = null;
    this.boundaries = null;
    this.activeView = null;
    this.activeHandle = HANDLE_NONE;
    this.dragging = false;
    overlay.setActive(false);
  }

  public boolean cancelFromBackPress (boolean commit) {
    if (!isActive()) {
      return false;
    }
    if (commit) {
      cancel();
    }
    return true;
  }

  public void onSingleMessageSelected (@NonNull TGMessage message) {
    if (!message.hasSelectableTextTarget()) {
      return;
    }
    int count = Settings.instance().getInt(HINT_COUNT_KEY, 0);
    if (count >= 3) {
      return;
    }
    View view = message.findCurrentView();
    if (view != null) {
      Settings.instance().putInt(HINT_COUNT_KEY, count + 1);
      message.showContentHint(view, null, R.string.MessageTextSelectionHint).hideDelayed();
    }
  }

  private void showActions () {
    if (!isActive() || dragging || recyclerScrolling || destroyed) {
      return;
    }
    if (!prepareOverlayGeometry(overlay, actionViewport) ||
        actionContentRect.isEmpty()) {
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      actionMode = overlay.startActionMode(new FloatingCallback(), ActionMode.TYPE_FLOATING);
      if (actionMode != null) {
        overlay.showLegacyActions(false, false, false);
        return;
      }
    }
    overlay.showLegacyActions(true, canQuote(), canSelectAll());
  }

  private void hideActions () {
    overlay.showLegacyActions(false, false, false);
    if (actionMode != null) {
      finishingActionMode = true;
      actionMode.finish();
      actionMode = null;
      finishingActionMode = false;
    }
  }

  private final class FloatingCallback extends ActionMode.Callback2 {
    @Override
    public boolean onCreateActionMode (ActionMode mode, Menu menu) {
      menu.add(Menu.NONE, ACTION_COPY, 0, R.string.Copy);
      menu.add(Menu.NONE, ACTION_QUOTE, 1, R.string.TextFormatQuote);
      menu.add(Menu.NONE, ACTION_SELECT_ALL, 2, R.string.SelectAll);
      return true;
    }

    @Override
    public boolean onPrepareActionMode (ActionMode mode, Menu menu) {
      MenuItem copy = menu.findItem(ACTION_COPY);
      MenuItem quote = menu.findItem(ACTION_QUOTE);
      MenuItem selectAll = menu.findItem(ACTION_SELECT_ALL);
      copy.setVisible(target != null && target.canCopy);
      quote.setVisible(canQuote());
      selectAll.setVisible(canSelectAll());
      return true;
    }

    @Override
    public boolean onActionItemClicked (ActionMode mode, MenuItem item) {
      performAction(item.getItemId());
      return true;
    }

    @Override
    public void onDestroyActionMode (ActionMode mode) {
      if (actionMode == mode) {
        actionMode = null;
      }
      if (!finishingActionMode && isActive() && !dragging && !recyclerScrolling) {
        overlay.postDelayed(MessageTextSelectionManager.this::showActions, 180L);
      }
    }

    @Override
    public void onGetContentRect (ActionMode mode, View view, Rect outRect) {
      if (prepareOverlayGeometry(overlay, outRect) && !actionContentRect.isEmpty()) {
        outRect.set(
          Math.round(actionContentRect.left),
          Math.round(actionContentRect.top),
          Math.round(actionContentRect.right),
          Math.round(actionContentRect.bottom)
        );
      } else {
        super.onGetContentRect(mode, view, outRect);
      }
    }
  }

  private void showMagnifier (float localX, float localY) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
        activeHandle == HANDLE_INITIAL) {
      return;
    }
    MessageView view = resolveActiveView();
    if (view == null) {
      return;
    }
    if (magnifier == null) {
      magnifier = Api28.createMagnifier(view);
    }
    Api28.showMagnifier(
      magnifier,
      Math.max(0f, Math.min(view.getWidth(), localX)),
      Math.max(0f, Math.min(view.getHeight(), localY))
    );
  }

  private void dismissMagnifier () {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && magnifier != null) {
      Api28.dismissMagnifier(magnifier);
      magnifier = null;
    }
  }

  private static final class Api28 {
    private Api28 () { }

    @RequiresApi(Build.VERSION_CODES.P)
    static Object createMagnifier (View view) {
      return new Magnifier(view);
    }

    @RequiresApi(Build.VERSION_CODES.P)
    static void showMagnifier (Object magnifier, float x, float y) {
      ((Magnifier) magnifier).show(x, y);
    }

    @RequiresApi(Build.VERSION_CODES.P)
    static void dismissMagnifier (Object magnifier) {
      ((Magnifier) magnifier).dismiss();
    }
  }

  private void announceSelection () {
    overlay.announceForAccessibility(Lang.getString(R.string.TextSelectionStarted));
  }

  public void destroy () {
    if (destroyed) {
      return;
    }
    cancel();
    destroyed = true;
    stopAutoScroll();
    overlay.removeCallbacks(autoScrollRunnable);
  }

  public float getStartHandleX () {
    return startHandleX;
  }

  public float getStartHandleY () {
    return startHandleY;
  }

  public float getEndHandleX () {
    return endHandleX;
  }

  public float getEndHandleY () {
    return endHandleY;
  }

  public boolean isStartHandleRtl () {
    return startHandleRtl;
  }

  public boolean isEndHandleRtl () {
    return endHandleRtl;
  }

  public int getHandleColor () {
    return target != null ? target.container.getTextLinkColor() : 0;
  }

  public float getActionAnchorX () {
    return actionAnchorX;
  }

  public float getActionAnchorTop () {
    return actionAnchorTop;
  }

  public float getActionAnchorBottom () {
    return actionAnchorBottom;
  }
}
