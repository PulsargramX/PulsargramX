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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageFileMap;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.mediaview.MediaViewController;
import org.thunderdog.challegram.mediaview.MediaViewDelegate;
import org.thunderdog.challegram.mediaview.MediaViewThumbLocation;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.mediaview.data.MediaStack;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.ui.MapController;
import org.thunderdog.challegram.v.MessagesRecyclerView;
import org.thunderdog.challegram.util.CollageContext;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.CodeHighlight;
import org.thunderdog.challegram.util.text.TextColorSet;
import org.thunderdog.challegram.util.text.TextColorSetOverride;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextEntityCustom;
import org.thunderdog.challegram.util.text.TextStyleProvider;
import org.thunderdog.challegram.util.text.TextStreamingAnimator;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;

import me.vkryl.core.StringUtils;
import me.vkryl.core.ColorUtils;
import ru.noties.jlatexmath.JLatexMathDrawable;
import tgx.td.Td;

/**
 * A flat, recyclable rich-message layout designed for a chat bubble.
 *
 * <p>Unlike Instant View, every node is measured and drawn directly by the
 * message. No nested RecyclerView or page-block View is created.</p>
 */
public final class RichMessageLayout implements MessageTextSelectionSurface {
  public interface Callback {
    void onRequestFullMessage ();
  }

  public static final int PARTIAL_MAX_HEIGHT_DP = 900;
  private static final int GAP_DP = 5;
  private static final int PARTIAL_BUTTON_HEIGHT_DP = 48;

  private final @NonNull TGMessage owner;
  private final @NonNull Callback callback;
  private final Rect clipBounds = new Rect();
  private final RectF showMoreBounds = new RectF();
  private final Text.SelectionGeometry temporaryGeometry = new Text.SelectionGeometry();
  private final Text.CaretGeometry temporaryCaret = new Text.CaretGeometry();
  private final Text.SelectionHit temporaryHit = new Text.SelectionHit();
  private final ArrayList<BlockNode> nodes = new ArrayList<>();
  private final ArrayList<SelectionLeaf> selectionLeaves = new ArrayList<>();
  private final HashMap<String, RichMessageFlattener.State> states = new HashMap<>();
  private final HashMap<TdApi.PageBlockList, Integer> listWidths = new HashMap<>();
  private final WeakHashMap<MessageView, RichAccessibilityProvider> accessibilityProviders =
    new WeakHashMap<>();

  private final WeakHashMap<View, Boolean> attachedViews = new WeakHashMap<>();
  private final @Nullable TextStreamingAnimator streamingAnimator;

  private TdApi.RichMessage message;
  private int measuredWidth;
  private int measuredHeight;
  private int visibleHeight;
  private int layoutRevision;
  private boolean partialLoading;
  private boolean partialFailed;
  private @Nullable TdApi.ChatType autoDownloadChatType;
  private boolean autoDownloadEnabled;
  private boolean showMorePressed;
  private @Nullable BlockNode pressedNode;
  private boolean spoilersRevealed;
  private String selectionText = "";
  private float measuredDensity = -1f;
  private float measuredFontScale = -1f;
  private float measuredTextSize = -1f;
  private int measuredThemeId = Integer.MIN_VALUE;
  private String measuredLocale = "";
  private String measuredTimeZone = "";
  private boolean measuredUseAmPm;

  public RichMessageLayout (@NonNull TGMessage owner, @NonNull TdApi.RichMessage message,
                            @NonNull Callback callback,
                            @Nullable RichMessageLayout previous) {
    this.owner = owner;
    this.callback = callback;
    streamingAnimator = owner.isPendingMessage() ? new TextStreamingAnimator(owner::invalidate) :
      null;
    if (previous != null) {
      spoilersRevealed = previous.spoilersRevealed;
      autoDownloadChatType = previous.autoDownloadChatType;
      autoDownloadEnabled = previous.autoDownloadEnabled;
      attachedViews.putAll(previous.attachedViews);
      for (Map.Entry<String, RichMessageFlattener.State> entry : previous.states.entrySet()) {
        states.put(entry.getKey(), entry.getValue().copy());
      }
    }
    setMessage(message);
  }

  public void setMessage (@NonNull TdApi.RichMessage message) {
    if (streamingAnimator != null) streamingAnimator.saveCursor();
    for (View view : attachedViews.keySet()) {
      if (view instanceof MessageView) cancelTouch((MessageView) view);
    }
    boolean reuse = this.message != null && this.message.isRtl == message.isRtl;
    this.message = message;
    rebuildNodes(reuse);
  }

  public @NonNull TdApi.RichMessage getMessage () {
    return message;
  }

  public @NonNull String getHtml () {
    return RichMessageUtils.toHtml(message, states);
  }

  public void setPartialLoading (boolean loading, boolean failed) {
    if (partialLoading != loading || partialFailed != failed) {
      partialLoading = loading;
      partialFailed = failed;
      owner.invalidate();
    }
  }

  public int measure (int width) {
    width = Math.max(Screen.dp(80f), width);
    float density = Screen.density();
    float fontScale = owner.context().getResources().getConfiguration().fontScale;
    float textSize = TGMessage.getTextStyleProvider().getTextSize();
    int themeId = ThemeManager.instance().currentThemeId();
    String locale = Lang.dateFormatLocale().toString();
    TimeZone timeZone = TimeZone.getDefault();
    String timeZoneKey = timeZone.getID() + ":" + timeZone.getRawOffset();
    boolean useAmPm = UI.needAmPm();
    boolean environmentChanged = measuredDensity != density ||
      measuredFontScale != fontScale || measuredTextSize != textSize || measuredThemeId != themeId ||
      !measuredLocale.equals(locale) || !measuredTimeZone.equals(timeZoneKey) ||
      measuredUseAmPm != useAmPm;
    if (measuredWidth == width && measuredHeight != 0 && !environmentChanged) {
      return visibleHeight;
    }
    if (streamingAnimator != null) streamingAnimator.saveCursor();
    if (environmentChanged && measuredDensity >= 0f) {
      rebuildNodes();
    }
    measuredDensity = density;
    measuredFontScale = fontScale;
    measuredTextSize = textSize;
    measuredThemeId = themeId;
    measuredLocale = locale;
    measuredTimeZone = timeZoneKey;
    measuredUseAmPm = useAmPm;
    measuredWidth = width;
    listWidths.clear();
    int y = 0;
    boolean previousVisible = false;
    boolean partialCapped = false;
    for (BlockNode node : nodes) {
      if (!node.visible) {
        continue;
      }
      if (node instanceof AnchorNode) {
        node.top = y + (previousVisible ? Screen.dp(GAP_DP) : 0);
        continue;
      }
      if (partialCapped) {
        node.top = Screen.dp(PARTIAL_MAX_HEIGHT_DP);
        node.height = 0;
        node.measuredAvailableWidth = -1;
        continue;
      }
      if (previousVisible) {
        y += Screen.dp(GAP_DP);
      }
      if (!message.isFull && y >= Screen.dp(PARTIAL_MAX_HEIGHT_DP)) {
        partialCapped = true;
        node.top = Screen.dp(PARTIAL_MAX_HEIGHT_DP);
        node.height = 0;
        node.measuredAvailableWidth = -1;
        continue;
      }
      node.top = y;
      node.contentInset = Math.min(node.indent(), Math.max(0, width - Screen.dp(24f)));
      int availableWidth = width - node.contentInset;
      if (node.measuredAvailableWidth != availableWidth) {
        node.measure(availableWidth);
        node.measuredAvailableWidth = availableWidth;
      }
      if (spoilersRevealed) {
        node.setSpoilersRevealed(true, false);
      }
      y += node.height;
      previousVisible = true;
    }
    measuredHeight = y;
    if (!message.isFull) {
      visibleHeight = Math.min(measuredHeight, Screen.dp(PARTIAL_MAX_HEIGHT_DP)) +
        Screen.dp(PARTIAL_BUTTON_HEIGHT_DP);
    } else {
      visibleHeight = measuredHeight;
    }
    rebuildSelectionLeaves();
    if (streamingAnimator != null) {
      ArrayList<TextStreamingAnimator.Block> blocks = new ArrayList<>();
      for (BlockNode node : nodes) {
        if (isExposed(node)) node.collectStreamingBlocks(blocks);
      }
      streamingAnimator.setBlocks(blocks);
    }
    layoutRevision++;
    downloadExposedMedia();
    return visibleHeight;
  }

  public int getHeight () {
    return visibleHeight;
  }

  public int getLastLineWidth () {
    if (!message.isFull) {
      return TGMessage.BOTTOM_LINE_EXPAND_HEIGHT;
    }
    for (int index = nodes.size() - 1; index >= 0; index--) {
      BlockNode node = nodes.get(index);
      if (isExposed(node)) {
        return node instanceof TextNode ? ((TextNode) node).lastLineWidth() :
          TGMessage.BOTTOM_LINE_EXPAND_HEIGHT;
      }
    }
    return TGMessage.BOTTOM_LINE_EXPAND_HEIGHT;
  }

  public boolean canInlineTime () {
    if (!message.isFull) {
      return false;
    }
    BlockNode last = null;
    for (BlockNode node : nodes) {
      if (isExposed(node)) {
        last = node;
      }
    }
    return last instanceof TextNode &&
      ((TextNode) last).spec.block.getConstructor() == TdApi.PageBlockParagraph.CONSTRUCTOR;
  }

  public void draw (@NonNull MessageView view, @NonNull Canvas canvas, int x, int y,
                    @NonNull ComplexReceiver receiver, float alpha) {
    canvas.getClipBounds(clipBounds);
    int contentBottom = !message.isFull ?
      Math.min(measuredHeight, Screen.dp(PARTIAL_MAX_HEIGHT_DP)) : measuredHeight;
    int save = Views.save(canvas);
    canvas.clipRect(x, y, x + measuredWidth, y + contentBottom);
    for (BlockNode node : nodes) {
      if (!node.visible) {
        continue;
      }
      if (streamingAnimator != null && !streamingAnimator.isVisible(node.streamingKey())) {
        node.viewport.setEmpty();
        continue;
      }
      int nodeTop = y + node.top;
      if (nodeTop + node.height < clipBounds.top || nodeTop > clipBounds.bottom) {
        continue;
      }
      int nodeX = x + (message.isRtl ? 0 : node.contentInset);
      node.viewport.set(nodeX, nodeTop, nodeX + node.width, Math.min(y + contentBottom, nodeTop + node.height));
      float nodeAlpha = streamingAnimator != null ?
        alpha * streamingAnimator.alpha(node.streamingKey()) : alpha;
      node.drawChrome(canvas, x, nodeTop, measuredWidth, nodeAlpha);
      node.draw(view, canvas, nodeX, nodeTop, receiver, nodeAlpha);
    }
    Views.restore(canvas, save);
    if (!message.isFull) {
      drawShowMore(canvas, x, y + contentBottom, alpha);
    }
  }

  public boolean onTouchEvent (@NonNull MessageView view, @NonNull MotionEvent event,
                               int x, int y) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      cancelTouch(view);
    }
    if (!message.isFull) {
      float eventX = event.getX();
      float eventY = event.getY();
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          if (showMoreBounds.contains(eventX, eventY)) {
            showMorePressed = true;
            owner.invalidate();
            return true;
          }
          break;
        case MotionEvent.ACTION_MOVE:
          if (showMorePressed && !showMoreBounds.contains(eventX, eventY)) {
            showMorePressed = false;
            owner.invalidate();
          }
          if (showMorePressed) {
            return true;
          }
          break;
        case MotionEvent.ACTION_UP:
          if (showMorePressed) {
            boolean hit = showMoreBounds.contains(eventX, eventY);
            showMorePressed = false;
            owner.invalidate();
            if (hit && !partialLoading) {
              callback.onRequestFullMessage();
            }
            return true;
          }
          break;
        case MotionEvent.ACTION_CANCEL:
          if (showMorePressed) {
            showMorePressed = false;
            owner.invalidate();
            return true;
          }
          break;
      }
    }
    int action = event.getActionMasked();
    if (action != MotionEvent.ACTION_DOWN) {
      BlockNode target = pressedNode;
      if (target == null) return false;
      // Keep delivering moves and cancellation even outside the original block.
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        pressedNode = null;
      }
      return target.onTouchEvent(view, event, x + (message.isRtl ? 0 : target.contentInset), y + target.top);
    }
    pressedNode = null;
    int localY = (int) event.getY() - y;
    if (event.getX() < x || event.getX() > x + measuredWidth ||
        !message.isFull && localY >= Math.min(measuredHeight, Screen.dp(PARTIAL_MAX_HEIGHT_DP))) {
      return false;
    }
    for (BlockNode node : nodes) {
      if (isExposed(node) && localY >= node.top && localY < node.top + node.height &&
          node.onTouchEvent(view, event, x + (message.isRtl ? 0 : node.contentInset), y + node.top)) {
        pressedNode = node;
        return true;
      }
    }
    return false;
  }

  public void cancelTouch (@NonNull MessageView view) {
    if (pressedNode != null) {
      MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
      onTouchEvent(view, cancel, owner.getContentX(), owner.getContentY());
      cancel.recycle();
    }
    showMorePressed = false;
  }

  public boolean hasScrollableTouch () {
    return pressedNode != null && (pressedNode.scroll.tracking ||
      pressedNode instanceof MediaNode && ((MediaNode) pressedNode).slideshowTracking);
  }

  public boolean canScrollForDragAt (float x, float y, boolean towardRight) {
    for (BlockNode node : nodes) {
      int left = message.isRtl ? 0 : node.contentInset;
      if (!isExposed(node) || x < left || x >= left + node.width ||
          y < node.top || y >= node.top + node.height) continue;
      if (node instanceof TableNode && y < node.top + ((TableNode) node).tableTop) {
        return false;
      }
      boolean towardEnd = towardRight == message.isRtl;
      if (node.scrollRange() > 0f) {
        return towardEnd ? node.spec.state.horizontalOffset < node.scrollRange() :
          node.spec.state.horizontalOffset > 0f;
      }
      if (node instanceof MediaNode && node.spec.block instanceof TdApi.PageBlockSlideshow) {
        int count = ((MediaNode) node).wrappers.size();
        return towardEnd ? node.spec.state.slideshowIndex < count - 1 :
          node.spec.state.slideshowIndex > 0;
      }
      return false;
    }
    return false;
  }

  public boolean navigateToAnchor (@Nullable View sourceView, @Nullable String anchor) {
    if (StringUtils.isEmpty(anchor)) return false;
    String name = anchor.startsWith("#") ? anchor.substring(1) : anchor;
    name = android.net.Uri.decode(name);
    RichMessageUtils.Index index = RichMessageUtils.indexAnchorsAndReferences(message);
    String path = index.anchors.get(name.toLowerCase(Locale.US));
    if (path == null) path = index.references.get(name.toLowerCase(Locale.US));
    if (path == null) return false;

    boolean expanded = false;
    for (Map.Entry<String, RichMessageFlattener.State> entry : states.entrySet()) {
      RichMessageFlattener.State state = entry.getValue();
      if (state.kind == RichMessageFlattener.KIND_DETAILS && !state.detailsOpen &&
          path.startsWith(entry.getKey() + "/")) {
        state.detailsOpen = true;
        expanded = true;
      }
    }
    if (expanded) {
      rebuildNodes();
      owner.rebuildAndUpdateContent();
      owner.invalidateContentReceiver();
    }

    BlockNode target = null;
    for (BlockNode node : nodes) {
      if (node.visible && (path.equals(node.spec.path) || path.startsWith(node.spec.path + "/")) &&
          (target == null || node.spec.path.length() > target.spec.path.length())) {
        target = node;
      }
    }
    if (target == null || !message.isFull &&
        target.top >= Screen.dp(PARTIAL_MAX_HEIGHT_DP)) return false;

    int targetY = target.top;
    if (target instanceof TextNode) {
      TextNode textNode = (TextNode) target;
      Text text = textNode.wrapper.getCurrent();
      int inlineY = text != null ? text.findAnchorY(name) : -1;
      targetY += textNode.paddingTop + Math.max(0, inlineY);
    } else if (target instanceof TableNode) {
      TableNode table = (TableNode) target;
      Text caption = table.caption != null ? table.caption.getCurrent() : null;
      int captionY = caption != null ? caption.findAnchorY(name) : -1;
      if (captionY >= 0) {
        targetY += captionY;
      }
      for (TableNode.TableCell cell : table.cells) {
        if (captionY >= 0) break;
        Text text = cell.wrapper.getCurrent();
        int inlineY = text != null ? text.findAnchorY(name) : -1;
        if (inlineY >= 0 && cell.solved != null) {
          targetY += table.cellTextTop(cell) + inlineY;
          float left = table.tableLayout.cellLeft(cell.solved);
          float right = table.tableLayout.cellRight(cell.solved);
          float range = table.scrollRange();
          float physicalOffset = message.isRtl ? range - table.spec.state.horizontalOffset :
            table.spec.state.horizontalOffset;
          physicalOffset = Math.max(0f, Math.min(left, Math.max(physicalOffset, right - table.width)));
          table.scroll.stop();
          table.spec.state.horizontalOffset = message.isRtl ? range - physicalOffset : physicalOffset;
          table.scroll.clamp();
          owner.invalidate();
          break;
        }
      }
    }
    if (!message.isFull && targetY >= Screen.dp(PARTIAL_MAX_HEIGHT_DP)) return false;
    MessageView view = sourceView instanceof MessageView ? (MessageView) sourceView : null;
    if (view == null) {
      for (View attached : attachedViews.keySet()) {
        if (attached instanceof MessageView) {
          view = (MessageView) attached;
          break;
        }
      }
    }
    if (view == null) return false;
    MessageView targetView = view;
    BlockNode targetNode = target;
    int offset = targetY;
    // Layout changes from opening details must reach the RecyclerView first.
    view.post(() -> {
      if (targetView.getMessage() != owner || owner.isDestroyed() || !nodes.contains(targetNode)) return;
      MessagesRecyclerView recycler = targetView.findParentRecyclerView();
      if (recycler == null) return;
      int relativeTop = Views.getLocationInWindow(targetView)[1] -
        Views.getLocationInWindow(recycler)[1];
      recycler.smoothScrollBy(0, relativeTop + owner.getContentY() + offset -
        recycler.getPaddingTop() - Screen.dp(12f));
    });
    return true;
  }

  public void requestMedia (@NonNull ComplexReceiver receiver) {
    downloadExposedMedia();
    receiver.requestMedia(target -> {
      for (BlockNode node : nodes) {
        if (isExposed(node)) {
          node.requestMedia(target);
        }
      }
    });
  }

  public void autoDownloadContent (TdApi.ChatType type) {
    autoDownloadChatType = type;
    autoDownloadEnabled = true;
    downloadExposedMedia();
  }

  private void downloadExposedMedia () {
    if (!autoDownloadEnabled) {
      return;
    }
    for (BlockNode node : nodes) {
      if (isExposed(node) && node instanceof MediaNode) {
        MediaNode media = (MediaNode) node;
        for (MediaWrapper wrapper : media.wrappers) {
          wrapper.getFileProgress().downloadAutomatically(autoDownloadChatType);
        }
        if (media.audioResult != null) {
          media.audioResult.downloadAutomatically(autoDownloadChatType);
        }
      }
    }
  }

  public void attach (@NonNull View view) {
    attachedViews.put(view, true);
    for (BlockNode node : nodes) {
      node.attach(view);
    }
    if (streamingAnimator != null) streamingAnimator.attach();
  }

  public void detach (@NonNull View view) {
    if (view instanceof MessageView) {
      cancelTouch((MessageView) view);
    }
    attachedViews.remove(view);
    if (streamingAnimator != null && attachedViews.isEmpty()) streamingAnimator.detach();
    for (BlockNode node : nodes) {
      node.scroll.stop();
      node.detach(view);
    }
    if (view instanceof MessageView) {
      accessibilityProviders.remove((MessageView) view);
    }
  }

  public void performDestroy () {
    if (streamingAnimator != null) streamingAnimator.detach();
    destroyNodes();
    accessibilityProviders.clear();
    attachedViews.clear();
  }

  public @NonNull AccessibilityNodeProvider getAccessibilityNodeProvider (
    @NonNull MessageView view) {
    RichAccessibilityProvider provider = accessibilityProviders.get(view);
    if (provider == null) {
      provider = new RichAccessibilityProvider(view);
      accessibilityProviders.put(view, provider);
    }
    return provider;
  }

  public @NonNull TdApi.FormattedText getSelectionText () {
    return new TdApi.FormattedText(selectionText, new TdApi.TextEntity[0]);
  }

  @Override
  public boolean hitTestSelection (float x, float y, @NonNull Text.SelectionHit out,
                                   boolean clampToText) {
    SelectionLeaf nearest = null;
    float nearestDistance = Float.MAX_VALUE;
    for (SelectionLeaf leaf : selectionLeaves) {
      RectF bounds = leaf.node.viewport;
      if (bounds.isEmpty()) continue;
      if (bounds.contains(x, y) && leaf.wrapper.hitTestSelection(x, y, out, false)) {
        out.offsetBy(leaf.globalStart, leaf.lineOffset);
        return true;
      }
      if (clampToText) {
        float clampedX = Math.max(bounds.left, Math.min(bounds.right, x));
        float clampedY = Math.max(bounds.top, Math.min(bounds.bottom, y));
        if (leaf.wrapper.hitTestSelection(clampedX, clampedY, temporaryHit, true) &&
            leaf.wrapper.getCaretGeometry(temporaryHit.offset, temporaryHit.affinity, temporaryCaret)) {
          float dx = x - temporaryCaret.x;
          float dy = y < temporaryCaret.lineTop ? temporaryCaret.lineTop - y :
            Math.max(0f, y - temporaryCaret.lineBottom);
          float distance = dx * dx + dy * dy;
          if (distance < nearestDistance) {
            nearest = leaf;
            nearestDistance = distance;
          }
        }
      }
    }
    if (nearest != null) {
      RectF bounds = nearest.node.viewport;
      if (nearest.wrapper.hitTestSelection(Math.max(bounds.left, Math.min(bounds.right, x)),
          Math.max(bounds.top, Math.min(bounds.bottom, y)), out, true)) {
        out.offsetBy(nearest.globalStart, nearest.lineOffset);
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean buildSelectionGeometry (int start, int end,
                                         @NonNull Text.SelectionGeometry out) {
    out.clear();
    boolean copiedStart = false;
    boolean any = false;
    for (SelectionLeaf leaf : selectionLeaves) {
      int leafEnd = leaf.globalStart + leaf.text.length();
      int from = Math.max(start, leaf.globalStart);
      int to = Math.min(end, leafEnd);
      if (to <= from) {
        continue;
      }
      temporaryGeometry.clear();
      if (leaf.wrapper.buildSelectionGeometry(from - leaf.globalStart,
          to - leaf.globalStart, temporaryGeometry)) {
        temporaryGeometry.clipTo(leaf.node.viewport);
        boolean copyEnd = to == end;
        out.appendTranslated(temporaryGeometry, 0f, 0f, !copiedStart, copyEnd);
        copiedStart = true;
        any = true;
      }
    }
    return any;
  }

  @Override
  public void setSelection (int start, int end) {
    for (SelectionLeaf leaf : selectionLeaves) {
      int leafEnd = leaf.globalStart + leaf.text.length();
      int from = Math.max(start, leaf.globalStart);
      int to = Math.min(end, leafEnd);
      if (to > from) {
        leaf.wrapper.setSelection(from - leaf.globalStart, to - leaf.globalStart);
      } else {
        leaf.wrapper.clearSelection();
      }
    }
    owner.invalidate();
  }

  @Override
  public void clearSelection () {
    for (SelectionLeaf leaf : selectionLeaves) {
      leaf.wrapper.clearSelection();
    }
    owner.invalidate();
  }

  @Override
  public int getLayoutRevision () {
    return layoutRevision;
  }

  @NonNull
  @Override
  public String copyPlainText (int start, int end) {
    int from = Math.max(0, Math.min(start, selectionText.length()));
    int to = Math.max(from, Math.min(end, selectionText.length()));
    return selectionText.substring(from, to);
  }

  @Nullable
  @Override
  public String copyHtml (int start, int end) {
    if (start <= 0 && end >= selectionText.length()) {
      return getHtml();
    }
    int from = Math.max(0, Math.min(start, selectionText.length()));
    int to = Math.max(from, Math.min(end, selectionText.length()));
    if (from == to) {
      return null;
    }
    StringBuilder html = new StringBuilder();
    html.append("<div dir=\"").append(message.isRtl ? "rtl" : "ltr").append("\">");
    int cursor = from;
    for (SelectionLeaf leaf : selectionLeaves) {
      int leafEnd = leaf.globalStart + leaf.text.length();
      int intersectionStart = Math.max(from, leaf.globalStart);
      int intersectionEnd = Math.min(to, leafEnd);
      if (intersectionEnd <= intersectionStart) {
        continue;
      }
      if (cursor < intersectionStart) {
        appendSelectedSeparatorHtml(html,
          selectionText.substring(cursor, intersectionStart));
      }
      if (intersectionStart == leaf.globalStart && intersectionEnd == leafEnd &&
          leaf.html != null) {
        html.append(leaf.html);
      } else {
        html.append(escapeHtml(selectionText.substring(
          intersectionStart, intersectionEnd)));
      }
      cursor = intersectionEnd;
    }
    if (cursor < to) {
      appendSelectedSeparatorHtml(html, selectionText.substring(cursor, to));
    }
    html.append("</div>");
    return html.toString();
  }

  private static void appendSelectedSeparatorHtml (StringBuilder out, String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\n') {
        out.append("<br>");
      } else if (character == '\t') {
        out.append("&emsp;");
      } else {
        out.append(escapeHtml(Character.toString(character)));
      }
    }
  }

  private void rebuildNodes () {
    rebuildNodes(false);
  }

  private void rebuildNodes (boolean reuse) {
    HashMap<String, BlockNode> previous = new HashMap<>();
    if (reuse) {
      for (BlockNode node : nodes) previous.put(node.spec.path, node);
      nodes.clear();
      pressedNode = null;
      selectionLeaves.clear();
      listWidths.clear();
    } else {
      destroyNodes();
    }
    RichMessageFlattener.Result flattened = RichMessageFlattener.flatten(message, states);
    states.clear();
    for (Map.Entry<String, RichMessageFlattener.State> entry : flattened.states.entrySet()) {
      states.put(entry.getKey(), entry.getValue());
    }
    for (RichMessageFlattener.Node spec : flattened.nodes) {
      BlockNode cached = previous.get(spec.path);
      if (cached != null && spec.isCompatibleWith(cached.spec) &&
          spec.nestingDepth == cached.spec.nestingDepth &&
          spec.quoteDepth == cached.spec.quoteDepth &&
          spec.lists.length == cached.spec.lists.length &&
          Td.equalsTo(spec.block, cached.spec.block) && Td.equalsTo(spec.text, cached.spec.text)) {
        previous.remove(spec.path);
        cached.spec = spec;
        cached.visible = spec.visible;
        nodes.add(cached);
        continue;
      }
      int start = nodes.size();
      switch (spec.kind) {
        case RichMessageFlattener.KIND_DIVIDER:
          nodes.add(new DividerNode(spec));
          break;
        case RichMessageFlattener.KIND_DETAILS:
          nodes.add(new DetailsNode(spec));
          break;
        case RichMessageFlattener.KIND_TABLE:
          nodes.add(new TableNode(spec));
          break;
        case RichMessageFlattener.KIND_MEDIA:
          nodes.add(new MediaNode(spec));
          break;
        case RichMessageFlattener.KIND_ANCHOR:
          nodes.add(new AnchorNode(spec));
          break;
        case RichMessageFlattener.KIND_FORMULA:
          nodes.add(new FormulaNode(spec));
          break;
        case RichMessageFlattener.KIND_UNKNOWN:
          nodes.add(new TextNode(spec, fallbackText(spec), styleFor(spec)));
          break;
        case RichMessageFlattener.KIND_TEXT:
        default:
          nodes.add(spec.block.getConstructor() == TdApi.PageBlockPreformatted.CONSTRUCTOR ?
            new CodeNode(spec) : new TextNode(spec, spec.text, styleFor(spec)));
          break;
      }
      for (View view : attachedViews.keySet()) nodes.get(start).attach(view);
    }
    for (BlockNode node : previous.values()) {
      destroyNode(node);
    }
    measuredWidth = measuredHeight = visibleHeight = 0;
    layoutRevision++;
  }

  private void destroyNodes () {
    pressedNode = null;
    for (BlockNode node : nodes) {
      destroyNode(node);
    }
    nodes.clear();
    listWidths.clear();
    selectionLeaves.clear();
  }

  private void destroyNode (BlockNode node) {
    node.scroll.stop();
    for (View view : attachedViews.keySet()) node.detach(view);
    node.performDestroy();
  }

  private void drawText (String key, TextWrapper wrapper, Canvas canvas, int left, int right,
                        int top, float alpha, ComplexReceiver receiver) {
    if (streamingAnimator != null) {
      streamingAnimator.draw(key, wrapper, canvas, left, right, 0, top, alpha, receiver);
    } else {
      wrapper.draw(canvas, left, right, 0, top, null, alpha, receiver);
    }
  }

  private void rebuildSelectionLeaves () {
    selectionLeaves.clear();
    StringBuilder joined = new StringBuilder();
    int lineOffset = 0;
    for (BlockNode node : nodes) {
      if (!isExposed(node) || !message.isFull &&
          node.top + node.height > Screen.dp(PARTIAL_MAX_HEIGHT_DP)) {
        continue;
      }
      List<TextNodePart> parts = node.textParts();
      for (TextNodePart part : parts) {
        if (part.text.isEmpty()) {
          continue;
        }
        if (joined.length() > 0) {
          joined.append(part.separator);
          lineOffset += countNewlines(part.separator);
        }
        if (!part.prefix.isEmpty()) {
          joined.append(part.prefix);
        }
        int start = joined.length();
        joined.append(part.text);
        selectionLeaves.add(new SelectionLeaf(node, part.wrapper, part.text, start,
          lineOffset, part.top, part.bottom, part.html));
        lineOffset += countNewlines(part.text);
      }
    }
    selectionText = joined.toString();
  }

  private void toggleDetails (DetailsNode node) {
    RichMessageFlattener.State state = states.get(node.spec.path);
    if (state == null) {
      return;
    }
    state.detailsOpen = !state.detailsOpen;
    rebuildNodes();
    owner.rebuildAndUpdateContent();
    owner.invalidateContentReceiver();
  }

  private boolean onTextTouch (TextWrapper wrapper, View view, MotionEvent event) {
    Text originalText = wrapper.getCurrent();
    boolean hadHiddenSpoilers = wrapper.hasUnrevealedSpoilers();
    boolean handled = wrapper.onTouchEvent(view, event);
    if (handled && hadHiddenSpoilers && wrapper.getCurrent() == originalText &&
        originalText != null && !wrapper.hasUnrevealedSpoilers()) {
      spoilersRevealed = true;
      for (BlockNode node : nodes) {
        node.setSpoilersRevealed(true, true);
      }
    }
    return handled;
  }

  private boolean isExposed (BlockNode node) {
    return node.visible && node.height > 0 && (message.isFull ||
      node.top < Screen.dp(PARTIAL_MAX_HEIGHT_DP));
  }

  private void drawShowMore (Canvas canvas, int x, int y, float alpha) {
    int height = Screen.dp(PARTIAL_BUTTON_HEIGHT_DP);
    showMoreBounds.set(x, y, x + measuredWidth, y + height);
    int color = owner.getTextColorSet().clickableTextColor(false);
    if (showMorePressed) {
      canvas.drawRoundRect(showMoreBounds, Screen.dp(8f), Screen.dp(8f),
        Paints.fillingPaint((color & 0x00ffffff) | 0x18000000));
    }
    String text = partialLoading ? "…" :
      partialFailed ? Lang.getString(R.string.RichMessageRetry) :
        Lang.getString(R.string.RichMessageShowMore);
    Paint paint = Paints.getMediumTextPaint(15f, color, false);
    paint.setAlpha(Math.round(255f * alpha));
    float textWidth = paint.measureText(text);
    Paint.FontMetrics metrics = paint.getFontMetrics();
    float baseline = y + (height - metrics.bottom + metrics.top) / 2f - metrics.top;
    canvas.drawText(text, x + (measuredWidth - textWidth) / 2f, baseline, paint);
    paint.setAlpha(255);
  }

  private void openMedia (MediaWrapper selected) {
    ArrayList<MediaItem> items = new ArrayList<>();
    ArrayList<MediaWrapper> mediaWrappers = new ArrayList<>();
    ArrayList<MediaNode> mediaNodes = new ArrayList<>();
    int selectedIndex = -1;
    for (BlockNode node : nodes) {
      if (!(node instanceof MediaNode)) {
        continue;
      }
      for (MediaWrapper wrapper : ((MediaNode) node).wrappers) {
        MediaItem item = null;
        if (wrapper.getPhoto() != null) {
          item = MediaItem.valueOf(owner.context(), owner.tdlib(), wrapper.getPhoto(), null);
        } else if (wrapper.getVideo() != null) {
          item = MediaItem.valueOf(owner.context(), owner.tdlib(), wrapper.getVideo(),
            null, null, null);
        } else if (wrapper.getAnimation() != null) {
          item = MediaItem.valueOf(owner.context(), owner.tdlib(), wrapper.getAnimation(), null);
        }
        if (item != null) {
          if (wrapper == selected) {
            selectedIndex = items.size();
          }
          items.add(item);
          mediaWrappers.add(wrapper);
          mediaNodes.add((MediaNode) node);
        }
      }
    }
    if (selectedIndex >= 0 && !items.isEmpty()) {
      MediaStack stack = new MediaStack(owner.context(), owner.tdlib());
      stack.set(selectedIndex, items);
      MediaViewDelegate delegate = new MediaViewDelegate() {
        @Override
        public MediaViewThumbLocation getTargetLocation (int index, MediaItem item) {
          if (index < 0 || index >= items.size() || items.get(index) != item) return null;
          return getMediaThumbLocation(mediaNodes.get(index), mediaWrappers.get(index));
        }

        @Override
        public void setMediaItemVisible (int index, MediaItem item, boolean isVisible) {
          // Like regular photo messages, keep the source drawn. The viewer's placeholder
          // covers it during the reveal animation and leaves it visible at factor zero.
        }
      };
      MediaViewController.openWithStack(owner.controller(), stack, null,
        (cause, args) -> args.delegate = delegate, true);
    }
  }

  private @Nullable MediaViewThumbLocation getMediaThumbLocation (MediaNode node,
                                                                 MediaWrapper wrapper) {
    if (owner.isDestroyed() || !nodes.contains(node) || !isExposed(node)) return null;
    Rect bounds = new Rect();
    if (!node.getMediaBounds(wrapper, bounds)) return null;
    bounds.offset(owner.getContentX() + (message.isRtl ? 0 : node.contentInset),
      owner.getContentY() + node.top);
    int contentBottom = owner.getContentY() + (message.isFull ? measuredHeight :
      Math.min(measuredHeight, Screen.dp(PARTIAL_MAX_HEIGHT_DP)));
    for (View attached : attachedViews.keySet()) {
      if (!(attached instanceof MessageView) || attached.getVisibility() != View.VISIBLE ||
          ((MessageView) attached).getMessage() != owner) continue;
      MessageView view = (MessageView) attached;
      MessagesRecyclerView recycler = view.findParentRecyclerView();
      if (recycler == null) continue;
      int[] position = Views.getLocationInWindow(view);
      int viewX = position[0], viewY = position[1];
      position = Views.getLocationInWindow(recycler);
      int visibleLeft = Math.max(viewX + owner.getContentX(),
        position[0] + recycler.getPaddingLeft());
      int visibleRight = Math.min(viewX + owner.getContentX() + measuredWidth,
        position[0] + recycler.getWidth() - recycler.getPaddingRight());
      int visibleTop = Math.max(viewY + owner.getContentY(),
        position[1] + owner.messagesController().getTopOffset());
      int visibleBottom = Math.min(viewY + contentBottom,
        position[1] + recycler.getHeight() - recycler.getPaddingBottom());
      int left = viewX + bounds.left, top = viewY + bounds.top;
      int right = viewX + bounds.right, bottom = viewY + bounds.bottom;
      if (left >= visibleRight || right <= visibleLeft ||
          top >= visibleBottom || bottom <= visibleTop) continue;

      // The viewer can return to a slide that has never been drawn in the bubble.
      if (node.spec.block instanceof TdApi.PageBlockSlideshow) {
        int index = node.wrappers.indexOf(wrapper);
        if (node.spec.state.slideshowIndex != index) {
          node.spec.state.slideshowIndex = index;
          owner.invalidate();
        }
      }
      MediaViewThumbLocation location = wrapper.getMediaThumbLocation(view, 0, 0, 0);
      location.set(left, top, right, bottom);
      location.setClip(Math.max(0, visibleLeft - left), Math.max(0, visibleTop - top),
        Math.max(0, right - visibleRight), Math.max(0, bottom - visibleBottom));
      location.setColorId(owner.useBubbles() && owner.isOutgoingBubble() ?
        ColorId.bubbleOut_background : ColorId.filling);
      return location;
    }
    return null;
  }

  private abstract class BlockNode implements RichBlockNode {
    RichMessageFlattener.Node spec;
    boolean visible;
    int measuredAvailableWidth = -1;
    int width;
    int height;
    int top;
    int contentInset;
    final RectF viewport = new RectF();
    final HorizontalScroll scroll = new HorizontalScroll(this);
    @Nullable TextWrapper pressedText;

    int indent () {
      int result = Screen.dp(spec.quoteDepth * 12f +
        Math.max(0, spec.nestingDepth - spec.quoteDepth - spec.lists.length) * 14f);
      Paint paint = Paints.getRegularTextPaint(TGMessage.getTextStyleProvider().getTextSize(), 0);
      for (TdApi.PageBlockList list : spec.lists) {
        Integer cachedWidth = listWidths.get(list);
        if (cachedWidth == null) {
          float markerWidth = Screen.dp(18f);
          if (list.items != null) {
            for (TdApi.PageBlockListItem item : list.items) {
              if (item != null) {
                markerWidth = Math.max(markerWidth,
                  paint.measureText(RichMessageFlattener.listLabel(item)) + Screen.dp(8f) +
                    (item.hasCheckbox ? Screen.dp(22f) : 0));
              }
            }
          }
          cachedWidth = (int) Math.ceil(markerWidth);
          listWidths.put(list, cachedWidth);
        }
        result += cachedWidth;
      }
      return result;
    }

    void drawChrome (Canvas canvas, int x, int y, int fullWidth, float alpha) {
      int color = owner.getTextColorSet().defaultTextColor();
      for (int depth = 0; depth < spec.quoteDepth; depth++) {
        int lineX = message.isRtl ? x + fullWidth - Screen.dp(3f + depth * 12f) :
          x + Screen.dp(depth * 12f);
        canvas.drawRoundRect(lineX, y, lineX + Screen.dp(3f), y + height,
          Screen.dp(1.5f), Screen.dp(1.5f),
          Paints.fillingPaint(Theme.getColor(owner.getTextColorSet().quoteLineColorId())));
      }
      if (spec.listLabel == null && !spec.hasCheckbox) return;
      float edge = message.isRtl ? x + fullWidth - contentInset + Screen.dp(4f) :
        x + contentInset - Screen.dp(4f);
      if (spec.hasCheckbox) {
        float center = edge + (message.isRtl ? Screen.dp(9f) : -Screen.dp(9f));
        RectF box = new RectF(center - Screen.dp(7f), y + Screen.dp(2f),
          center + Screen.dp(7f), y + Screen.dp(16f));
        canvas.drawRoundRect(box, Screen.dp(3f), Screen.dp(3f),
          Paints.getProgressPaint(color, Screen.dp(1.5f)));
        if (spec.checked) {
          Paint check = Paints.getProgressPaint(color, Screen.dp(2f));
          canvas.drawLine(box.left + Screen.dp(3f), box.centerY(),
            box.centerX() - Screen.dp(1f), box.bottom - Screen.dp(3f), check);
          canvas.drawLine(box.centerX() - Screen.dp(1f), box.bottom - Screen.dp(3f),
            box.right - Screen.dp(2f), box.top + Screen.dp(3f), check);
        }
        edge += message.isRtl ? Screen.dp(22f) : -Screen.dp(22f);
      }
      if (!StringUtils.isEmpty(spec.listLabel)) {
        Paint paint = Paints.getRegularTextPaint(TGMessage.getTextStyleProvider().getTextSize(), color);
        paint.setAlpha(Math.round(255f * alpha));
        canvas.drawText(spec.listLabel, message.isRtl ? edge : edge - paint.measureText(spec.listLabel),
          y - paint.ascent(), paint);
        paint.setAlpha(255);
      }
    }

    float scrollRange () { return 0f; }

    void drawScrollbar (Canvas canvas, int x, int y, float alpha) {
      float range = scrollRange();
      if (range <= 0f) return;
      float trackWidth = width;
      float thumbWidth = Math.max(Screen.dp(24f), trackWidth * width / (width + range));
      thumbWidth = Math.min(trackWidth, thumbWidth);
      float offset = spec.state.horizontalOffset;
      float physicalOffset = message.isRtl ? range - offset : offset;
      float left = x + (trackWidth - thumbWidth) * physicalOffset / range;
      float top = y + height - Screen.dp(3f);
      int color = owner.getTextColorSet().defaultTextColor();
      RectF bounds = Paints.getRectF();
      bounds.set(x, top, x + trackWidth, y + height);
      canvas.drawRoundRect(bounds, Screen.dp(1.5f), Screen.dp(1.5f),
        Paints.fillingPaint(ColorUtils.alphaColor(alpha * .07f, color)));
      bounds.set(left, top, left + thumbWidth, y + height);
      canvas.drawRoundRect(bounds, Screen.dp(1.5f), Screen.dp(1.5f),
        Paints.fillingPaint(ColorUtils.alphaColor(alpha * .25f, color)));
    }

    boolean onNodeTextTouch (TextWrapper wrapper, View view, MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        pressedText = null;
        if (onTextTouch(wrapper, view, event)) {
          pressedText = wrapper;
          return true;
        }
        return false;
      }
      TextWrapper target = pressedText;
      if (target == null) return false;
      if (event.getActionMasked() == MotionEvent.ACTION_UP ||
          event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        pressedText = null;
      }
      return onTextTouch(target, view, event);
    }

    void cancelTextTouch (View view, MotionEvent event) {
      if (pressedText == null) return;
      MotionEvent cancel = MotionEvent.obtain(event);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      onNodeTextTouch(pressedText, view, cancel);
      cancel.recycle();
    }

    BlockNode (RichMessageFlattener.Node spec) {
      this.spec = spec;
      this.visible = spec.visible;
    }

    String streamingKey () { return spec.path; }

    void collectStreamingBlocks (List<TextStreamingAnimator.Block> blocks) {
      blocks.add(new TextStreamingAnimator.Block(streamingKey(), null));
    }

    @NonNull
    @Override
    public String path () {
      return spec.path;
    }

    @Override
    public int constructor () {
      return spec.block.getConstructor();
    }

    @Override
    public int width () {
      return width;
    }

    @Override
    public int height () {
      return height;
    }

    @Override
    public int top () {
      return top;
    }

    @Override
    public void setTop (int top) {
      this.top = top;
    }

    @Override
    public void draw (@NonNull View view, @NonNull Canvas canvas, int x, int y,
                      @NonNull ComplexReceiver receiver) {
      draw((MessageView) view, canvas, x, y, receiver, 1f);
    }

    abstract void draw (MessageView view, Canvas canvas, int x, int y,
                        ComplexReceiver receiver, float alpha);

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      return false;
    }

    @Override
    public void requestMedia (@NonNull ComplexReceiver receiver) { }

    @Override
    public void attach (@NonNull View view) { }

    @Override
    public void detach (@NonNull View view) { }

    @Override
    public void getBounds (@NonNull Rect out) {
      out.set(0, top, width, top + height);
    }

    @Override
    public void performDestroy () { }

    void setSpoilersRevealed (boolean revealed, boolean animated) { }

    List<TextNodePart> textParts () {
      return java.util.Collections.emptyList();
    }
  }

  private class TextNode extends BlockNode {
    final TextWrapper wrapper;
    final int inset;
    final int paddingTop;
    final int paddingBottom;
    final int horizontalPadding;
    final boolean scrollable;
    int contentWidth;

    TextNode (RichMessageFlattener.Node spec, @Nullable TdApi.RichText text,
              TextStyleProvider style) {
      this(spec, text, style,
        spec.block.getConstructor() == TdApi.PageBlockPreformatted.CONSTRUCTOR);
    }

    TextNode (RichMessageFlattener.Node spec, @Nullable TdApi.RichText text,
              TextStyleProvider style, boolean scrollable) {
      super(spec);
      TdApi.RichText safeText = text != null ? text : new TdApi.RichTextPlain("");
      boolean code = spec.block.getConstructor() == TdApi.PageBlockPreformatted.CONSTRUCTOR;
      boolean pullquote = spec.block.getConstructor() == TdApi.PageBlockPullQuote.CONSTRUCTOR;
      boolean credit = spec.path.endsWith("/credit");
      boolean quoteCredit = credit && (pullquote ||
        spec.block.getConstructor() == TdApi.PageBlockBlockQuote.CONSTRUCTOR);
      TextColorSet colors = quoteCredit ? new TextColorSetOverride(owner.getTextColorSet()) {
        @Override
        public int defaultTextColor () {
          return Theme.getColor(owner.getTextColorSet().quoteLineColorId());
        }
      } : owner.getTextColorSet();
      if (isHeading(spec.block) || quoteCredit) {
        safeText = new TdApi.RichTextBold(safeText);
      } else if (pullquote && !credit) {
        safeText = new TdApi.RichTextItalic(safeText);
      }
      wrapper = code ? codeWrapper(spec, style) :
        TextWrapper.parseRichText(owner.controller(), owner.clickCallback(), safeText,
        style, colors, owner.openParameters(),
        (changedWrapper, changedText, specificMedia) ->
          owner.invalidateContentReceiver());
      prepareReferences(wrapper);
      wrapper.setViewProvider(owner.currentViews);
      wrapper.addTextFlags(Text.FLAG_ARTICLE | Text.FLAG_CUSTOM_LONG_PRESS);
      if (message.isRtl && !code) {
        wrapper.addTextFlags(Text.FLAG_ALIGN_RIGHT);
      }
      if (pullquote) {
        wrapper.addTextFlags(Text.FLAG_ALIGN_CENTER);
      }
      inset = spec.kind == RichMessageFlattener.KIND_DETAILS ? Screen.dp(20f) : 0;
      horizontalPadding = Screen.dp(code ? 12f : pullquote ? 22f : 0f);
      paddingTop = Screen.dp(code ? 40f : pullquote && !credit ? 8f :
        isHeading(spec.block) ? 4f : 0f);
      paddingBottom = Screen.dp(code ? 12f : pullquote && !credit ? 8f : 0f);
      this.scrollable = scrollable;
    }

    @Override
    public int measure (int width) {
      this.width = width;
      int available = Math.max(Screen.dp(24f), width - inset - horizontalPadding * 2);
      if (scrollable) {
        int unwrappedWidth = available;
        Paint paint = wrapper.getTextStyleProvider().getMonospacePaint();
        String value = wrapper.getText();
        for (int start = 0; start < value.length();) {
          int end = value.indexOf('\n', start);
          if (end < 0) end = value.length();
          unwrappedWidth = Math.max(unwrappedWidth,
            (int) Math.ceil(paint.measureText(value, start, end)) + Screen.dp(8f));
          start = end + 1;
        }
        wrapper.prepare(unwrappedWidth);
        contentWidth = wrapper.getWidth();
        if (contentWidth <= available) {
          wrapper.prepare(available);
          contentWidth = wrapper.getWidth();
        }
      } else {
        wrapper.prepare(available);
        contentWidth = wrapper.getWidth();
      }
      scroll.clamp();
      height = paddingTop + wrapper.getHeight() + paddingBottom;
      return height;
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      int left = (message.isRtl ? x : x + inset) + horizontalPadding;
      int right = (message.isRtl ? x + width - inset : x + width) - horizontalPadding;
      int save = Views.save(canvas);
      if (scrollable) {
        canvas.clipRect(left, y, right, y + height);
      }
      int offset = Math.round(spec.state.horizontalOffset);
      int drawWidth = scrollable ? Math.max(right - left, contentWidth) : right - left;
      int drawLeft = message.isRtl ? right - drawWidth + offset : left - offset;
      int drawRight = drawLeft + drawWidth;
      drawText(streamingKey(), wrapper, canvas, drawLeft, drawRight,
        y + paddingTop, alpha, receiver);
      Views.restore(canvas, save);
      if (spec.block.getConstructor() == TdApi.PageBlockPullQuote.CONSTRUCTOR) {
        boolean credit = spec.path.endsWith("/credit");
        boolean hasCredit = !RichMessageUtils.richTextToPlain(
          ((TdApi.PageBlockPullQuote) spec.block).credit).isEmpty();
        Paint quote = Paints.getRegularTextPaint(
          TGMessage.getTextStyleProvider().getTextSize() + 10f,
          Theme.getColor(owner.getTextColorSet().quoteLineColorId()));
        quote.setAlpha(Math.round(255f * alpha));
        quote.setTextSize(wrapper.getTextStyleProvider().convertUnit(
          TGMessage.getTextStyleProvider().getTextSize() + 10f));
        if (!credit) {
          canvas.drawText("“", x + Screen.dp(3f), y - quote.ascent(), quote);
        }
        if (credit || !hasCredit) {
          canvas.drawText("”", x + width - Screen.dp(3f) - quote.measureText("”"),
            y + height - quote.descent(), quote);
        }
        quote.setAlpha(255);
      }
    }

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      boolean handledScroll = scroll.onTouch(view, event);
      if (scroll.dragging || scroll.finishedDrag) return true;
      boolean handledText = onNodeTextTouch(wrapper, view, event);
      return handledScroll || handledText;
    }

    @Override
    float scrollRange () {
      return scrollable ?
        Math.max(0, contentWidth - (width - inset - horizontalPadding * 2)) : 0f;
    }

    @Override
    public void requestMedia (@NonNull ComplexReceiver receiver) {
      wrapper.requestMedia(receiver, RichMessageFlattener.receiverKey(spec.path, 0), 256);
    }

    @Override
    public void performDestroy () {
      wrapper.performDestroy();
    }

    @Override
    void collectStreamingBlocks (List<TextStreamingAnimator.Block> blocks) {
      blocks.add(new TextStreamingAnimator.Block(streamingKey(), wrapper));
    }

    @Override
    void setSpoilersRevealed (boolean revealed, boolean animated) {
      wrapper.setSpoilersRevealed(revealed, animated);
    }

    @Override
    List<TextNodePart> textParts () {
      String prefix = "";
      if (spec.hasCheckbox) {
        prefix = spec.checked ? "☑ " : "☐ ";
      }
      if (!StringUtils.isEmpty(spec.listLabel)) {
        prefix += spec.listLabel + " ";
      }
      return java.util.Collections.singletonList(new TextNodePart(wrapper, wrapper.getText(),
        "\n", top + paddingTop, top + paddingTop + wrapper.getHeight(),
        spec.text != null ? RichMessageUtils.richTextToHtml(spec.text) : null, prefix));
    }

    int lastLineWidth () {
      return wrapper.getLastLineWidth() + inset + horizontalPadding * 2 + contentInset;
    }
  }

  private final class CodeNode extends TextNode {
    private final RectF copyBounds = new RectF();
    private final RectF backgroundBounds = new RectF();
    private boolean copyPressed;

    CodeNode (RichMessageFlattener.Node spec) {
      super(spec, spec.text, styleFor(spec), true);
    }

    void copyCode () {
      UI.copyText(wrapper.getText(), R.string.CopiedText);
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      int textColor = owner.getTextColorSet().defaultTextColor();
      int background = Theme.getColor(ColorId.iv_textCodeBackground);
      Paint fill = Paints.fillingPaint(background);
      fill.setAlpha(Math.round(255f * alpha));
      backgroundBounds.set(x, y, x + width, y + height);
      canvas.drawRoundRect(backgroundBounds, Screen.dp(8f), Screen.dp(8f), fill);
      fill.setAlpha(255);
      Paint header = Paints.getRegularTextPaint(
        Math.max(10f, TGMessage.getTextStyleProvider().getTextSize() - 3f), textColor);
      header.setTextSize(wrapper.getTextStyleProvider().convertUnit(
        Math.max(10f, TGMessage.getTextStyleProvider().getTextSize() - 3f)));
      header.setAlpha(Math.round(255f * alpha * (copyPressed ? 1f : .7f)));
      String copy = Lang.getString(R.string.Copy);
      float copyWidth = header.measureText(copy);
      float copyLeft = message.isRtl ? x + Screen.dp(12f) :
        x + width - Screen.dp(12f) - copyWidth;
      copyBounds.set(copyLeft - Screen.dp(8f), y,
        copyLeft + copyWidth + Screen.dp(8f), y + Screen.dp(36f));
      canvas.drawText(copy, copyLeft, y + Screen.dp(10f) - header.ascent(), header);
      String language = ((TdApi.PageBlockPreformatted) spec.block).language;
      if (!StringUtils.isEmpty(language)) {
        int save = Views.save(canvas);
        float languageLeft = message.isRtl ? copyBounds.right + Screen.dp(8f) :
          x + Screen.dp(12f);
        float languageRight = message.isRtl ? x + width - Screen.dp(12f) :
          copyBounds.left - Screen.dp(8f);
        canvas.clipRect(languageLeft, y, languageRight, y + Screen.dp(36f));
        canvas.drawText(language, message.isRtl ? languageRight - header.measureText(language) :
          languageLeft, y + Screen.dp(10f) - header.ascent(), header);
        Views.restore(canvas, save);
      }
      header.setAlpha(255);
      super.draw(view, canvas, x, y, receiver, alpha);
      drawScrollbar(canvas, x, y, alpha);
    }

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          copyPressed = copyBounds.contains(event.getX(), event.getY());
          if (copyPressed) {
            owner.invalidate();
            return true;
          }
          break;
        case MotionEvent.ACTION_MOVE:
          if (copyPressed) {
            if (!copyBounds.contains(event.getX(), event.getY())) {
              copyPressed = false;
              owner.invalidate();
            }
            return true;
          }
          break;
        case MotionEvent.ACTION_UP:
          if (copyPressed) {
            copyPressed = false;
            owner.invalidate();
            if (copyBounds.contains(event.getX(), event.getY())) {
              copyCode();
            }
            return true;
          }
          break;
        case MotionEvent.ACTION_CANCEL:
          if (copyPressed) {
            copyPressed = false;
            owner.invalidate();
            return true;
          }
          break;
      }
      return super.onTouchEvent(view, event, x, y);
    }

    @Override
    int lastLineWidth () {
      return width + contentInset;
    }
  }

  private final class FormulaNode extends TextNode {
    private @Nullable Bitmap bitmap;
    private int formulaWidth;
    private int formulaHeight;
    private int formulaTextSize;

    FormulaNode (RichMessageFlattener.Node spec) {
      super(spec, new TdApi.RichTextPlain(
          ((TdApi.PageBlockMathematicalExpression) spec.block).expression),
        styleFor(spec), true);
    }

    private void prepareFormula () {
      int textSize = wrapper.getTextStyleProvider().getTextSizeInPixels();
      if (formulaTextSize == textSize) return;
      formulaTextSize = textSize;
      if (bitmap != null) {
        bitmap.recycle();
        bitmap = null;
      }
      String expression =
        ((TdApi.PageBlockMathematicalExpression) spec.block).expression;
      if (!StringUtils.isEmpty(expression)) {
        try {
          JLatexMathDrawable drawable = JLatexMathDrawable.builder(expression)
            .textSize(textSize)
            .build();
          formulaWidth = drawable.getIntrinsicWidth();
          formulaHeight = drawable.getIntrinsicHeight();
          if (formulaWidth > 0 && formulaHeight > 0) {
            bitmap = Bitmap.createBitmap(formulaWidth, formulaHeight,
              Bitmap.Config.ALPHA_8);
            drawable.setBounds(0, 0, formulaWidth, formulaHeight);
            drawable.draw(new Canvas(bitmap));
          }
        } catch (Throwable ignored) {
          bitmap = null;
        }
      }
    }

    @Override
    public int measure (int width) {
      prepareFormula();
      if (bitmap == null) {
        return super.measure(width);
      }
      this.width = width;
      contentWidth = formulaWidth + Screen.dp(16f);
      scroll.clamp();
      height = formulaHeight + Screen.dp(16f);
      return height;
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      if (bitmap == null) {
        super.draw(view, canvas, x, y, receiver, alpha);
        return;
      }
      int save = Views.save(canvas);
      canvas.clipRect(x, y, x + width, y + height);
      int scroll = Math.round(spec.state.horizontalOffset);
      int left = formulaWidth + Screen.dp(16f) <= width ? x + (width - formulaWidth) / 2 :
        message.isRtl ? x + width - formulaWidth - Screen.dp(8f) + scroll :
          x + Screen.dp(8f) - scroll;
      Paint paint = Paints.fillingPaint(owner.getTextColorSet().defaultTextColor());
      paint.setAlpha(Math.round(255f * alpha));
      canvas.drawBitmap(bitmap, left, y + Screen.dp(8f), paint);
      paint.setAlpha(255);
      Views.restore(canvas, save);
      drawScrollbar(canvas, x, y, alpha);
    }

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      return bitmap == null ? super.onTouchEvent(view, event, x, y) : scroll.onTouch(view, event);
    }

    @Override
    List<TextNodePart> textParts () {
      return bitmap == null ? super.textParts() : java.util.Collections.emptyList();
    }

    @Override
    public void performDestroy () {
      super.performDestroy();
      if (bitmap != null) {
        bitmap.recycle();
        bitmap = null;
      }
    }

  }

  private final class DetailsNode extends TextNode {
    private boolean pressed;
    private float downX, downY;
    DetailsNode (RichMessageFlattener.Node spec) {
      super(spec, new TdApi.RichTextBold(spec.text != null ? spec.text :
        new TdApi.RichTextPlain("")), styleFor(spec));
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      super.draw(view, canvas, x, y, receiver, alpha);
      int color = owner.getTextColorSet().defaultTextColor();
      Paint paint = Paints.getProgressPaint(color, Screen.dp(2f));
      float cx = message.isRtl ? x + width - Screen.dp(10f) : x + Screen.dp(10f);
      float cy = y + height / 2f;
      float direction = spec.state.detailsOpen ? 1f : 0f;
      int save = Views.save(canvas);
      canvas.rotate(message.isRtl ? 180f - direction * 90f : direction * 90f, cx, cy);
      canvas.drawLine(cx - Screen.dp(3f), cy - Screen.dp(5f), cx + Screen.dp(2f), cy, paint);
      canvas.drawLine(cx + Screen.dp(2f), cy, cx - Screen.dp(3f), cy + Screen.dp(5f), paint);
      Views.restore(canvas, save);
    }

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          pressed = true;
          downX = event.getX();
          downY = event.getY();
          return true;
        case MotionEvent.ACTION_MOVE:
          int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
          if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
            pressed = false;
          }
          return true;
        case MotionEvent.ACTION_UP:
          boolean click = pressed && event.getX() >= x && event.getX() <= x + width &&
            event.getY() >= y && event.getY() <= y + height;
          pressed = false;
          if (click) toggleDetails(this);
          return true;
        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_POINTER_DOWN:
          pressed = false;
          return true;
      }
      return false;
    }
  }

  private final class DividerNode extends BlockNode {
    DividerNode (RichMessageFlattener.Node spec) {
      super(spec);
    }

    @Override
    public int measure (int width) {
      this.width = width;
      height = Screen.dp(13f);
      return height;
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      canvas.drawRect(x, y + Screen.dp(6f), x + width, y + Screen.dp(7f),
        Paints.fillingPaint(Theme.getColor(ColorId.separator)));
    }
  }

  private final class AnchorNode extends BlockNode {
    AnchorNode (RichMessageFlattener.Node spec) {
      super(spec);
    }

    @Override
    public int measure (int width) {
      this.width = width;
      height = 0;
      return 0;
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) { }
  }

  private final class MediaNode extends BlockNode {
    final ArrayList<MediaWrapper> wrappers = new ArrayList<>();
    @Nullable TextWrapper fallback;
    @Nullable ImageFile mapImage;
    @Nullable InlineResultCommon audioResult;
    @Nullable CollageContext collageContext;
    @Nullable String[] slideLabels;
    int columns;
    int cellWidth;
    int cellHeight;
    float touchStartX, touchStartY;
    int initialSlide;
    boolean sliding;
    boolean slideshowTracking;
    boolean mapPressed;
    @Nullable View touchView;
    @Nullable MediaWrapper pressedMedia;

    MediaNode (RichMessageFlattener.Node spec) {
      super(spec);
      collectWrappers(spec.block);
      if (spec.block.getConstructor() == TdApi.PageBlockCollage.CONSTRUCTOR &&
          !wrappers.isEmpty()) {
        collageContext = new CollageContext(wrappers, Screen.dp(2f),
          RichMessageFlattener.receiverKey(spec.path, 0) & 0x7fffffffffff0000L);
      }
      if (spec.block.getConstructor() == TdApi.PageBlockAudio.CONSTRUCTOR &&
          ((TdApi.PageBlockAudio) spec.block).audio != null) {
        audioResult = InlineResultCommon.forRichMessage(owner.context(), owner.tdlib(),
          (TdApi.PageBlockAudio) spec.block);
      } else if (spec.block.getConstructor() == TdApi.PageBlockVoiceNote.CONSTRUCTOR &&
          ((TdApi.PageBlockVoiceNote) spec.block).voiceNote != null) {
        audioResult = InlineResultCommon.forRichMessage(owner.context(), owner.tdlib(),
          (TdApi.PageBlockVoiceNote) spec.block, Lang.getString(R.string.Audio));
      }
      if (spec.block.getConstructor() == TdApi.PageBlockMap.CONSTRUCTOR) {
        TdApi.PageBlockMap map = (TdApi.PageBlockMap) spec.block;
        if (map.location != null) {
          int sourceWidth = Math.max(14, Math.min(1024, map.width));
          int sourceHeight = Math.max(14, Math.min(1024, map.height));
          int zoom = Math.max(13, Math.min(20, map.zoom));
          mapImage = new ImageFileMap(map.location.latitude, map.location.longitude,
            zoom, sourceWidth, sourceHeight);
          mapImage.setScaleType(ImageFile.CENTER_CROP);
        }
      }
      if (wrappers.isEmpty() && mapImage == null && audioResult == null) {
        String label = mediaLabel(spec.block);
        fallback = TextWrapper.parseRichText(owner.controller(), owner.clickCallback(),
          new TdApi.RichTextPlain(label), PageBlockRichText.getParagraphProvider(),
          owner.getTextColorSet(), owner.openParameters(), null);
        fallback.setViewProvider(owner.currentViews);
      } else if (spec.block.getConstructor() == TdApi.PageBlockSlideshow.CONSTRUCTOR &&
          wrappers.size() > 1) {
        slideLabels = new String[wrappers.size()];
        for (int index = 0; index < slideLabels.length; index++) {
          slideLabels[index] = (index + 1) + "/" + slideLabels.length;
        }
      }
    }

    private void collectWrappers (TdApi.PageBlock block) {
      switch (block.getConstructor()) {
        case TdApi.PageBlockPhoto.CONSTRUCTOR: {
          TdApi.PageBlockPhoto photo = (TdApi.PageBlockPhoto) block;
          if (photo.photo != null) {
            MediaWrapper wrapper = new MediaWrapper(owner.context(), owner.tdlib(), photo.photo,
              owner.getChatId(), owner.getId(), owner, false);
            wrapper.setRevealOnTap(photo.hasSpoiler);
            addWrapper(wrapper);
          }
          break;
        }
        case TdApi.PageBlockVideo.CONSTRUCTOR: {
          TdApi.PageBlockVideo video = (TdApi.PageBlockVideo) block;
          if (video.video != null) {
            MediaWrapper wrapper = new MediaWrapper(owner.context(), owner.tdlib(), video.video,
              null, owner.getChatId(), owner.getId(), owner, false);
            wrapper.setRevealOnTap(video.hasSpoiler);
            addWrapper(wrapper);
          }
          break;
        }
        case TdApi.PageBlockAnimation.CONSTRUCTOR: {
          TdApi.PageBlockAnimation animation = (TdApi.PageBlockAnimation) block;
          if (animation.animation != null) {
            MediaWrapper wrapper = new MediaWrapper(owner.context(), owner.tdlib(),
              animation.animation, owner.getChatId(), owner.getId(), owner, false);
            wrapper.setRevealOnTap(animation.hasSpoiler);
            addWrapper(wrapper);
          }
          break;
        }
        case TdApi.PageBlockCollage.CONSTRUCTOR: {
          TdApi.PageBlock[] blocks = ((TdApi.PageBlockCollage) block).blocks;
          if (blocks != null) {
            for (TdApi.PageBlock child : blocks) {
              if (child != null) collectWrappers(child);
            }
          }
          break;
        }
        case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
          TdApi.PageBlock[] blocks = ((TdApi.PageBlockSlideshow) block).blocks;
          if (blocks != null) {
            for (TdApi.PageBlock child : blocks) {
              if (child != null) collectWrappers(child);
            }
          }
          break;
        }
      }
    }

    private void addWrapper (MediaWrapper wrapper) {
      wrapper.setOnClickListener((view, clicked) -> {
        openMedia(clicked);
        return true;
      });
      wrapper.setViewProvider(owner.currentViews);
      wrappers.add(wrapper);
    }

    @Override
    public int measure (int width) {
      this.width = width;
      if (mapImage != null) {
        TdApi.PageBlockMap map = (TdApi.PageBlockMap) spec.block;
        int sourceWidth = Math.max(1, map.width);
        int sourceHeight = Math.max(1, map.height);
        height = Math.min(Screen.dp(260f), Math.max(Screen.dp(120f),
          Math.round((float) width * sourceHeight / sourceWidth)));
        return height;
      }
      if (audioResult != null) {
        audioResult.layout(width, null);
        height = audioResult.getHeight();
        return height;
      }
      if (fallback != null) {
        fallback.prepare(width);
        height = fallback.getHeight() + Screen.dp(16f);
        return height;
      }
      if (collageContext != null) {
        height = collageContext.getHeight(width, Screen.dp(420f));
        return height;
      }
      boolean collage = spec.block.getConstructor() == TdApi.PageBlockCollage.CONSTRUCTOR;
      boolean slideshow = spec.block.getConstructor() == TdApi.PageBlockSlideshow.CONSTRUCTOR;
      columns = collage && wrappers.size() > 1 ? 2 : 1;
      cellWidth = columns == 1 ? width : (width - Screen.dp(2f)) / 2;
      if (slideshow) {
        cellHeight = Math.min(Screen.dp(280f), Math.max(Screen.dp(140f),
          Math.round(cellWidth * .72f)));
        height = cellHeight;
      } else if (collage) {
        cellHeight = cellWidth;
        height = ((wrappers.size() + columns - 1) / columns) * cellHeight +
          Math.max(0, (wrappers.size() + columns - 1) / columns - 1) * Screen.dp(2f);
      } else {
        MediaWrapper wrapper = wrappers.get(0);
        int sourceWidth = Math.max(1, wrapper.getContentWidth());
        int sourceHeight = Math.max(1, wrapper.getContentHeight());
        cellHeight = Math.min(Screen.dp(320f), Math.max(Screen.dp(120f),
          Math.round((float) width * sourceHeight / sourceWidth)));
        height = cellHeight;
      }
      for (MediaWrapper wrapper : wrappers) {
        wrapper.buildContent(cellWidth, cellHeight);
      }
      return height;
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      if (mapImage != null) {
        ImageReceiver image = receiver.getImageReceiver(
          RichMessageFlattener.receiverKey(spec.path, 0));
        image.setBounds(x, y, x + width, y + height);
        image.setRadius(Screen.dp(8f));
        image.setPaintAlpha(alpha);
        image.draw(canvas);
        image.restorePaintAlpha();
        return;
      }
      if (audioResult != null) {
        audioResult.drawRichMessage(view, canvas, x, y, width, height,
          receiver, RichMessageFlattener.receiverKey(spec.path, 0), alpha);
        return;
      }
      if (fallback != null) {
        fallback.draw(canvas, x, x + width, 0, y + Screen.dp(8f), null, alpha, receiver);
        return;
      }
      if (collageContext != null) {
        collageContext.draw(view, canvas, x, y, receiver);
        return;
      }
      boolean slideshow = spec.block.getConstructor() == TdApi.PageBlockSlideshow.CONSTRUCTOR;
      int first = slideshow ? Math.max(0, Math.min(wrappers.size() - 1,
        spec.state.slideshowIndex)) : 0;
      int last = slideshow ? first + 1 : wrappers.size();
      for (int index = first; index < last; index++) {
        MediaWrapper wrapper = wrappers.get(index);
        int drawIndex = slideshow ? 0 : index;
        int column = drawIndex % columns;
        int row = drawIndex / columns;
        int drawX = x + column * (cellWidth + Screen.dp(2f));
        int drawY = y + row * (cellHeight + Screen.dp(2f));
        long key = RichMessageFlattener.receiverKey(spec.path, index * 4);
        DoubleImageReceiver preview = receiver.getPreviewReceiver(key);
        Receiver target = wrapper.needGif() ? receiver.getGifReceiver(key + 1) :
          receiver.getImageReceiver(key + 1);
        wrapper.draw(view, canvas, drawX, drawY, preview, target, alpha);
      }
      if (slideshow && wrappers.size() > 1) {
        Paint paint = Paints.getRegularTextPaint(12f, 0xffffffff);
        String count = slideLabels[first];
        float countWidth = paint.measureText(count);
        RectF background = Paints.getRectF();
        background.set(x + width - countWidth - Screen.dp(16f), y + Screen.dp(8f),
          x + width - Screen.dp(8f), y + Screen.dp(28f));
        canvas.drawRoundRect(background, Screen.dp(10f), Screen.dp(10f),
          Paints.fillingPaint(0x66000000));
        canvas.drawText(count, background.left + Screen.dp(4f),
          background.centerY() - (paint.ascent() + paint.descent()) / 2f, paint);
      }
    }

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      if (mapImage != null) {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            mapPressed = true;
            touchStartX = event.getX();
            touchStartY = event.getY();
            return true;
          case MotionEvent.ACTION_MOVE:
            int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            if (Math.abs(event.getX() - touchStartX) > slop ||
                Math.abs(event.getY() - touchStartY) > slop) mapPressed = false;
            return true;
          case MotionEvent.ACTION_UP:
            boolean click = mapPressed && event.getX() >= x && event.getX() <= x + width &&
              event.getY() >= y && event.getY() <= y + height;
            mapPressed = false;
            if (click) openMap();
            return true;
          case MotionEvent.ACTION_CANCEL:
          case MotionEvent.ACTION_POINTER_DOWN:
            mapPressed = false;
            return true;
        }
        return false;
      }
      if (audioResult != null) {
        return audioResult.onTouchEvent(view, event);
      }
      if (collageContext != null) {
        return collageContext.onTouchEvent(view, event, x, y);
      }
      boolean slideshow = spec.block.getConstructor() == TdApi.PageBlockSlideshow.CONSTRUCTOR &&
        wrappers.size() > 1;
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        touchView = view;
        touchStartX = event.getX();
        touchStartY = event.getY();
        initialSlide = Math.max(0, Math.min(wrappers.size() - 1, spec.state.slideshowIndex));
        sliding = false;
        slideshowTracking = slideshow;
        pressedMedia = wrappers.isEmpty() ? null : wrappers.get(slideshow ? initialSlide : 0);
        boolean handled = pressedMedia != null && pressedMedia.onTouchEvent(view, event);
        return handled || slideshow;
      }
      if (slideshowTracking && action == MotionEvent.ACTION_MOVE) {
        float dx = event.getX() - touchStartX;
        float dy = event.getY() - touchStartY;
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        if (!sliding && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
          cancelMediaTouch(view, event);
          slideshowTracking = false;
          return false;
        }
        if (!sliding && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
          sliding = true;
          if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
          cancelMediaTouch(view, event);
        }
        if (sliding) return true;
      }
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
          action == MotionEvent.ACTION_POINTER_DOWN) {
        boolean wasSliding = sliding;
        if (sliding && action == MotionEvent.ACTION_UP) {
          float distance = event.getX() - touchStartX;
          int direction = (message.isRtl ? -distance : distance) < 0 ? 1 : -1;
          spec.state.slideshowIndex = Math.max(0,
            Math.min(wrappers.size() - 1, initialSlide + direction));
          owner.invalidate();
        }
        if (sliding && view.getParent() != null) {
          view.getParent().requestDisallowInterceptTouchEvent(false);
        }
        sliding = slideshowTracking = false;
        touchView = null;
        if (action != MotionEvent.ACTION_UP) cancelMediaTouch(view, event);
        if (wasSliding) return true;
      }
      MediaWrapper target = pressedMedia;
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pressedMedia = null;
      return target != null && target.onTouchEvent(view, event);
    }

    private void cancelMediaTouch (View view, MotionEvent event) {
      if (pressedMedia == null) return;
      MotionEvent cancel = MotionEvent.obtain(event);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      pressedMedia.onTouchEvent(view, cancel);
      cancel.recycle();
      pressedMedia = null;
    }

    private boolean getMediaBounds (MediaWrapper wrapper, Rect bounds) {
      if (collageContext != null) {
        return collageContext.getMediaBounds(wrapper, bounds);
      }
      int index = wrappers.indexOf(wrapper);
      if (index < 0 || columns <= 0) return false;
      int drawIndex = spec.block instanceof TdApi.PageBlockSlideshow ? 0 : index;
      int x = drawIndex % columns * (cellWidth + Screen.dp(2f));
      int y = drawIndex / columns * (cellHeight + Screen.dp(2f));
      bounds.set(x, y, x + wrapper.getCellWidth(), y + wrapper.getCellHeight());
      return !bounds.isEmpty();
    }

    boolean openMap () {
      if (mapImage == null) {
        return false;
      }
      TdApi.PageBlockMap map = (TdApi.PageBlockMap) spec.block;
      owner.tdlib().ui().openMap(owner.controller(),
        new MapController.Args(map.location.latitude, map.location.longitude));
      return true;
    }

    @Override
    public void requestMedia (@NonNull ComplexReceiver receiver) {
      if (mapImage != null) {
        receiver.getImageReceiver(RichMessageFlattener.receiverKey(spec.path, 0))
          .requestFile(mapImage);
        return;
      }
      if (audioResult != null) {
        audioResult.requestRichMessageContent(receiver,
          RichMessageFlattener.receiverKey(spec.path, 0));
        return;
      }
      if (collageContext != null) {
        collageContext.requestFiles(receiver, false);
        return;
      }
      if (fallback != null) {
        fallback.requestMedia(receiver, RichMessageFlattener.receiverKey(spec.path, 0), 64);
        return;
      }
      for (int index = 0; index < wrappers.size(); index++) {
        MediaWrapper wrapper = wrappers.get(index);
        long key = RichMessageFlattener.receiverKey(spec.path, index * 4);
        wrapper.requestPreview(receiver.getPreviewReceiver(key));
        ImageReceiver image = receiver.getImageReceiver(key + 1);
        GifReceiver gif = receiver.getGifReceiver(key + 1);
        if (wrapper.needGif()) {
          wrapper.requestGif(gif);
          image.requestFile(null);
        } else {
          wrapper.requestImage(image);
          gif.requestFile(null);
        }
      }
    }

    @Override
    public void performDestroy () {
      for (MediaWrapper wrapper : wrappers) {
        wrapper.destroy();
      }
      if (fallback != null) {
        fallback.performDestroy();
      }
      if (audioResult != null) {
        audioResult.performRichMessageDestroy();
      }
    }

    @Override
    public void attach (@NonNull View view) {
      if (audioResult != null) {
        audioResult.attachToView(view);
      }
    }

    @Override
    public void detach (@NonNull View view) {
      if (touchView == view) {
        if (sliding && view.getParent() != null) {
          view.getParent().requestDisallowInterceptTouchEvent(false);
        }
        sliding = slideshowTracking = mapPressed = false;
        touchView = null;
        pressedMedia = null;
      }
      if (audioResult != null) {
        audioResult.detachFromView(view);
      }
    }

    @Override
    void setSpoilersRevealed (boolean revealed, boolean animated) {
      if (fallback != null) {
        fallback.setSpoilersRevealed(revealed, animated);
      }
    }

    @Override
    List<TextNodePart> textParts () {
      return fallback != null ? java.util.Collections.singletonList(
        new TextNodePart(fallback, fallback.getText(), "\n", top, top + height,
          escapeHtml(fallback.getText()), "")) :
        java.util.Collections.emptyList();
    }
  }

  private final class TableNode extends BlockNode {
    final ArrayList<TableCell> cells = new ArrayList<>();
    final String captionStreamingKey;
    final Paint leftFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint rightFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable TextWrapper caption;
    SpanTableSolver.Layout tableLayout;
    float[] rowCoordinates;
    int captionHeight;
    int tableTop;
    int fadeWidth;
    int cellPadding;
    int tableHeight;
    final Path tableClip = new Path();

    TableNode (RichMessageFlattener.Node spec) {
      super(spec);
      captionStreamingKey = spec.path + "/caption";
      TdApi.PageBlockTable table = (TdApi.PageBlockTable) spec.block;
      if (table.caption != null && !RichMessageUtils.richTextToPlain(table.caption).isEmpty()) {
        caption = makeWrapper(table.caption, styleFor(spec));
      }
      if (table.cells != null) {
        for (int row = 0; row < table.cells.length; row++) {
          TdApi.PageBlockTableCell[] rowCells = table.cells[row];
          if (rowCells == null) continue;
          for (int column = 0; column < rowCells.length; column++) {
            if (rowCells[column] != null) {
              cells.add(new TableCell(row, column, rowCells[column]));
            }
          }
        }
      }
    }

    @Override
    String streamingKey () {
      return caption != null ? captionStreamingKey :
        cells.isEmpty() ? spec.path : cellStreamingKey(cells.get(0));
    }

    private String cellStreamingKey (TableCell cell) {
      return cell.streamingKey;
    }

    @Override
    void collectStreamingBlocks (List<TextStreamingAnimator.Block> blocks) {
      if (caption != null) {
        blocks.add(new TextStreamingAnimator.Block(captionStreamingKey, caption));
      }
      for (TableCell cell : cells) {
        blocks.add(new TextStreamingAnimator.Block(cellStreamingKey(cell), cell.wrapper));
      }
      if (caption == null && cells.isEmpty()) super.collectStreamingBlocks(blocks);
    }

    @Override
    public int measure (int width) {
      this.width = width;
      captionHeight = 0;
      if (caption != null) {
        caption.prepare(width);
        captionHeight = caption.getHeight() + Screen.dp(6f);
      }
      ArrayList<SpanTableSolver.InputCell> input = new ArrayList<>(cells.size());
      TdApi.PageBlockTable table = (TdApi.PageBlockTable) spec.block;
      int padding = cellPadding = Screen.dp(table.isCompact ? 5f : 8f);
      int minimumHeight = Screen.dp(table.isCompact ? 18f : 36f);
      // Measure intrinsic columns before fitting the viewport. Squeezing every
      // column toward 120dp broke short words and hid the table's structure.
      int maxCellWidth = Math.max(Screen.dp(60f), width * 2 / 3);
      for (TableCell cell : cells) {
        cell.wrapper.prepare(maxCellWidth);
        int maximumWidth = cell.wrapper.getWidth() + padding * 2;
        int minimumWidth = maximumWidth;
        input.add(new SpanTableSolver.InputCell(cell.sourceRow, cell.sourceColumn,
          cell.cell.colspan, cell.cell.rowspan, minimumWidth, maximumWidth,
          cell.wrapper.getHeight() + padding * 2, cell.wrapper.getHeight() + padding * 2));
      }
      tableLayout = SpanTableSolver.solve(input, width, message.isRtl);
      scroll.clamp();
      float[] rowHeights = new float[tableLayout.rowCount];
      for (int index = 0; index < tableLayout.cells.size(); index++) {
        SpanTableSolver.Cell solved = tableLayout.cells.get(index);
        TableCell cell = findCell(solved.sourceRow, solved.sourceColumn);
        if (cell == null) continue;
        int available = Math.max(1,
          Math.round(tableLayout.cellRight(solved) - tableLayout.cellLeft(solved)) - padding * 2);
        cell.wrapper.prepare(available);
        cell.solved = solved;
        float part = Math.max(minimumHeight,
          cell.wrapper.getHeight() + padding * 2f) / solved.rowSpan;
        for (int row = solved.row; row < solved.row + solved.rowSpan && row < rowHeights.length; row++) {
          rowHeights[row] = Math.max(rowHeights[row], part);
        }
      }
      rowCoordinates = new float[rowHeights.length + 1];
      for (int row = 0; row < rowHeights.length; row++) {
        rowCoordinates[row + 1] = rowCoordinates[row] + rowHeights[row];
      }
      fadeWidth = Screen.dp(14f);
      int background = owner.getContentBackgroundColor();
      int transparent = background & 0x00ffffff;
      leftFadePaint.setShader(new LinearGradient(0, 0, fadeWidth, 0,
        background, transparent, Shader.TileMode.CLAMP));
      rightFadePaint.setShader(new LinearGradient(width - fadeWidth, 0, width, 0,
        transparent, background, Shader.TileMode.CLAMP));
      tableTop = captionHeight;
      tableHeight = Math.round(rowCoordinates[rowCoordinates.length - 1]);
      height = captionHeight + tableHeight + (scrollRange() > 0f ? Screen.dp(8f) : 0);
      return height;
    }

    @Override
    void draw (MessageView view, Canvas canvas, int x, int y,
               ComplexReceiver receiver, float alpha) {
      if (caption != null) {
        drawText(captionStreamingKey, caption, canvas, x, x + width, y, alpha, receiver);
      }
      if (tableLayout == null) return;
      int save = Views.save(canvas);
      canvas.clipRect(x, y + tableTop, x + width, y + tableTop + tableHeight);
      float offset = spec.state.horizontalOffset;
      float origin = message.isRtl ? x + width - tableLayout.width + offset : x - offset;
      TdApi.PageBlockTable table = (TdApi.PageBlockTable) spec.block;
      int padding = cellPadding;
      int foreground = owner.getTextColorSet().defaultTextColor();
      float radius = Screen.dp(6f);
      tableClip.reset();
      RectF tableBounds = Paints.getRectF();
      tableBounds.set(origin, y + tableTop, origin + tableLayout.width,
        y + tableTop + tableHeight);
      tableClip.addRoundRect(tableBounds, radius, radius, Path.Direction.CW);
      canvas.clipPath(tableClip);
      for (TableCell cell : cells) {
        if (cell.solved == null) continue;
        float left = origin + tableLayout.cellLeft(cell.solved);
        float right = origin + tableLayout.cellRight(cell.solved);
        float top = y + tableTop + rowCoordinates[cell.solved.row];
        float bottom = y + tableTop + rowCoordinates[cell.solved.row + cell.solved.rowSpan];
        cell.bounds.set(left, top, right, bottom);
        if (cell.cell.isHeader || table.isStriped && cell.solved.row % 2 == 0) {
          canvas.drawRect(cell.bounds, Paints.fillingPaint(
            ColorUtils.alphaColor(alpha * (cell.cell.isHeader ? .08f : .035f), foreground)));
        }
        if (table.isBordered) {
          Paint border = Paints.getProgressPaint(
            ColorUtils.alphaColor(alpha * .2f, foreground), Screen.dpf(.66f));
          if (cell.solved.column > 0) {
            float edge = message.isRtl ? right : left;
            canvas.drawLine(edge, top, edge, bottom, border);
          }
          if (cell.solved.row > 0) {
            canvas.drawLine(left, top, right, top, border);
          }
        }
        int textY = y + cellTextTop(cell);
        int cellSave = Views.save(canvas);
        canvas.clipRect(left, top, right, bottom);
        drawText(cellStreamingKey(cell), cell.wrapper, canvas,
          Math.round(left) + padding, Math.round(right) - padding, textY, alpha, receiver);
        Views.restore(canvas, cellSave);
      }
      if (table.isBordered) {
        float stroke = Screen.dpf(.66f);
        tableBounds.set(origin + stroke / 2f, y + tableTop + stroke / 2f,
          origin + tableLayout.width - stroke / 2f,
          y + tableTop + tableHeight - stroke / 2f);
        canvas.drawRoundRect(tableBounds, radius, radius,
          Paints.getProgressPaint(ColorUtils.alphaColor(alpha * .2f, foreground), stroke));
      }
      Views.restore(canvas, save);
      if (tableLayout.width > width) {
        boolean hiddenLeft = origin < x;
        boolean hiddenRight = origin + tableLayout.width > x + width;
        leftFadePaint.setAlpha(Math.round(alpha * 255f));
        rightFadePaint.setAlpha(Math.round(alpha * 255f));
        int fadeSave = Views.save(canvas);
        canvas.translate(x, 0);
        if (hiddenLeft) {
          canvas.drawRect(0, y + tableTop, fadeWidth, y + tableTop + tableHeight, leftFadePaint);
        }
        if (hiddenRight) {
          canvas.drawRect(width - fadeWidth, y + tableTop, width,
            y + tableTop + tableHeight, rightFadePaint);
        }
        Views.restore(canvas, fadeSave);
      }
      drawScrollbar(canvas, x, y, alpha);
    }

    private int cellTextTop (TableCell cell) {
      float top = tableTop + rowCoordinates[cell.solved.row];
      float bottom = tableTop + rowCoordinates[cell.solved.row + cell.solved.rowSpan];
      int textHeight = cell.wrapper.getHeight();
      if (cell.cell.valign instanceof TdApi.PageBlockVerticalAlignmentBottom) {
        return Math.round(bottom) - cellPadding - textHeight;
      }
      if (cell.cell.valign instanceof TdApi.PageBlockVerticalAlignmentMiddle) {
        return Math.round((top + bottom - textHeight) / 2f);
      }
      return Math.round(top) + cellPadding;
    }

    @Override
    public boolean onTouchEvent (@NonNull View view, @NonNull MotionEvent event, int x, int y) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN && event.getY() < y + tableTop) {
        return caption != null && onNodeTextTouch(caption, view, event);
      }
      boolean handledScroll = scroll.onTouch(view, event);
      if (scroll.dragging || scroll.finishedDrag) return true;
      if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
        boolean handledText = pressedText != null && onNodeTextTouch(pressedText, view, event);
        return handledScroll || handledText;
      }
      for (TableCell cell : cells) {
        if (cell.bounds.contains(event.getX(), event.getY()) &&
            onNodeTextTouch(cell.wrapper, view, event)) {
          return true;
        }
      }
      return handledScroll;
    }

    @Override
    float scrollRange () {
      return tableLayout != null ? Math.max(0f, tableLayout.width - width) : 0f;
    }

    @Override
    public void requestMedia (@NonNull ComplexReceiver receiver) {
      long key = RichMessageFlattener.receiverKey(spec.path, 0);
      if (caption != null) {
        caption.requestMedia(receiver, key, 128);
        key += 128;
      }
      for (TableCell cell : cells) {
        cell.wrapper.requestMedia(receiver, key, 128);
        key += 128;
      }
    }

    @Override
    public void performDestroy () {
      if (caption != null) caption.performDestroy();
      for (TableCell cell : cells) {
        cell.wrapper.performDestroy();
      }
    }

    @Override
    void setSpoilersRevealed (boolean revealed, boolean animated) {
      if (caption != null) {
        caption.setSpoilersRevealed(revealed, animated);
      }
      for (TableCell cell : cells) {
        cell.wrapper.setSpoilersRevealed(revealed, animated);
      }
    }

    @Override
    List<TextNodePart> textParts () {
      ArrayList<TextNodePart> result = new ArrayList<>();
      if (caption != null) {
        result.add(new TextNodePart(caption, caption.getText(), "\n", top,
          top + caption.getHeight(),
          RichMessageUtils.richTextToHtml(
            ((TdApi.PageBlockTable) spec.block).caption), ""));
      }
      for (TableCell cell : cells) {
        if (cell.solved != null) {
          result.add(new TextNodePart(cell.wrapper, cell.wrapper.getText(),
            cell.sourceColumn == 0 ? "\n" : "\t",
            Math.round(cell.bounds.top), Math.round(cell.bounds.bottom),
            RichMessageUtils.richTextToHtml(cell.cell.text), ""));
        }
      }
      return result;
    }

    @Nullable
    private TableCell findCell (int row, int column) {
      for (TableCell cell : cells) {
        if (cell.sourceRow == row && cell.sourceColumn == column) return cell;
      }
      return null;
    }

    private final class TableCell {
      final int sourceRow;
      final int sourceColumn;
      final TdApi.PageBlockTableCell cell;
      final TextWrapper wrapper;
      final String streamingKey;
      final RectF bounds = new RectF();
      @Nullable SpanTableSolver.Cell solved;

      TableCell (int sourceRow, int sourceColumn, TdApi.PageBlockTableCell cell) {
        this.sourceRow = sourceRow;
        this.sourceColumn = sourceColumn;
        this.cell = cell;
        streamingKey = spec.path + "/cell/" + sourceRow + "/" + sourceColumn;
        wrapper = makeWrapper(cell.text != null ? cell.text : new TdApi.RichTextPlain(""),
          new TextStyleProvider(Fonts.newRobotoStorage()).setTextSize(
            Math.max(8f, TGMessage.getTextStyleProvider().getTextSize() - 2f)).setAllowSp(true));
        if (cell.align != null) {
          switch (cell.align.getConstructor()) {
            case TdApi.PageBlockHorizontalAlignmentLeft.CONSTRUCTOR:
              wrapper.setTextFlagEnabled(Text.FLAG_ALIGN_RIGHT, false);
              wrapper.setTextFlagEnabled(Text.FLAG_ALIGN_CENTER, false);
              break;
            case TdApi.PageBlockHorizontalAlignmentCenter.CONSTRUCTOR:
              wrapper.setTextFlagEnabled(Text.FLAG_ALIGN_RIGHT, false);
              wrapper.addTextFlags(Text.FLAG_ALIGN_CENTER);
              break;
            case TdApi.PageBlockHorizontalAlignmentRight.CONSTRUCTOR:
              wrapper.setTextFlagEnabled(Text.FLAG_ALIGN_CENTER, false);
              wrapper.addTextFlags(Text.FLAG_ALIGN_RIGHT);
              break;
          }
        }
      }
    }
  }

  /** Shared gesture ownership and fling behavior for tables, code and formulas. */
  private final class HorizontalScroll implements Runnable {
    final BlockNode node;
    @Nullable View view;
    @Nullable VelocityTracker velocity;
    @Nullable OverScroller scroller;
    boolean tracking;
    boolean dragging;
    boolean finishedDrag;
    float downX, downY, initialOffset;

    HorizontalScroll (BlockNode node) {
      this.node = node;
    }

    void clamp () {
      node.spec.state.horizontalOffset = Math.max(0f,
        Math.min(node.scrollRange(), node.spec.state.horizontalOffset));
    }

    boolean onTouch (View view, MotionEvent event) {
      finishedDrag = false;
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        stop();
        if (node.scrollRange() <= 0f) return false;
        this.view = view;
        tracking = true;
        downX = event.getX();
        downY = event.getY();
        initialOffset = node.spec.state.horizontalOffset;
        velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        return true;
      }
      if (!tracking) return false;
      if (velocity != null) velocity.addMovement(event);
      if (action == MotionEvent.ACTION_MOVE) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        if (!dragging && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
          node.cancelTextTouch(view, event);
          stop();
          return false;
        }
        if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
          float delta = message.isRtl ? dx : -dx;
          if (delta < 0f && initialOffset <= 0f ||
              delta > 0f && initialOffset >= node.scrollRange()) {
            // Decide at the start of the drag. Once captured, reversing or
            // reaching an edge must never turn a table drag into navigation.
            node.cancelTextTouch(view, event);
            stop();
            return false;
          }
          dragging = true;
          if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
          node.cancelTextTouch(view, event);
        }
        if (dragging) {
          node.spec.state.horizontalOffset = initialOffset + (message.isRtl ? dx : -dx);
          clamp();
          owner.invalidate();
        }
        return true;
      }
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
          action == MotionEvent.ACTION_POINTER_DOWN) {
        finishedDrag = dragging;
        if (action == MotionEvent.ACTION_POINTER_DOWN) node.cancelTextTouch(view, event);
        if (dragging && view.getParent() != null) {
          view.getParent().requestDisallowInterceptTouchEvent(false);
        }
        if (dragging && action == MotionEvent.ACTION_UP && velocity != null) {
          ViewConfiguration config = ViewConfiguration.get(view.getContext());
          velocity.computeCurrentVelocity(1000, config.getScaledMaximumFlingVelocity());
          float speed = velocity.getXVelocity() * (message.isRtl ? 1f : -1f);
          if (Math.abs(speed) >= config.getScaledMinimumFlingVelocity()) {
            scroller = new OverScroller(view.getContext());
            scroller.fling(Math.round(node.spec.state.horizontalOffset), 0, (int) speed, 0,
              0, (int) Math.ceil(node.scrollRange()), 0, 0);
            view.postOnAnimation(this);
          }
        }
        tracking = dragging = false;
        recycleVelocity();
        if (scroller == null || scroller.isFinished()) this.view = null;
        return finishedDrag;
      }
      return true;
    }

    @Override
    public void run () {
      if (view != null && scroller != null && scroller.computeScrollOffset()) {
        node.spec.state.horizontalOffset = scroller.getCurrX();
        clamp();
        owner.invalidate();
        view.postOnAnimation(this);
      } else if (!tracking) {
        view = null;
      }
    }

    void stop () {
      if (view != null) {
        view.removeCallbacks(this);
        if (dragging && view.getParent() != null) {
          view.getParent().requestDisallowInterceptTouchEvent(false);
        }
      }
      if (scroller != null) scroller.forceFinished(true);
      recycleVelocity();
      tracking = dragging = false;
      view = null;
    }

    private void recycleVelocity () {
      if (velocity != null) velocity.recycle();
      velocity = null;
    }
  }

  private TextWrapper makeWrapper (TdApi.RichText text, TextStyleProvider provider) {
    TextWrapper wrapper = TextWrapper.parseRichText(owner.controller(), owner.clickCallback(),
      text, provider, owner.getTextColorSet(), owner.openParameters(),
      (changedWrapper, changedText, specificMedia) ->
        owner.invalidateContentReceiver());
    prepareReferences(wrapper);
    wrapper.setViewProvider(owner.currentViews);
    wrapper.addTextFlags(Text.FLAG_ARTICLE | Text.FLAG_CUSTOM_LONG_PRESS);
    if (message.isRtl) {
      wrapper.addTextFlags(Text.FLAG_ALIGN_RIGHT);
    }
    return wrapper;
  }

  private void prepareReferences (TextWrapper wrapper) {
    TextEntity[] entities = wrapper.getEntities();
    if (entities != null) {
      for (TextEntity entity : entities) {
        if (entity instanceof TextEntityCustom) {
          ((TextEntityCustom) entity)
            .setMonospaceWithoutBackground(wrapper.getTextColorSet())
            .setReferenceAsSuperscript(owner.getTextColorSet());
        }
      }
    }
  }

  private TextWrapper codeWrapper (RichMessageFlattener.Node spec, TextStyleProvider style) {
    String code = RichMessageUtils.richTextToPlain(spec.text);
    List<CodeHighlight.Token> tokens = CodeHighlight.tokenize(code,
      ((TdApi.PageBlockPreformatted) spec.block).language);
    TextEntity[] entities = new TextEntity[tokens.size()];
    TextColorSet[] colors = new TextColorSet[8];
    for (int index = 0; index < tokens.size(); index++) {
      CodeHighlight.Token token = tokens.get(index);
      if (colors[token.type] == null) {
        final int type = token.type;
        colors[type] = new TextColorSet() {
          @Override
          public int defaultTextColor () {
            boolean dark = Theme.isDark();
            switch (type) {
              case CodeHighlight.KEYWORD: return dark ? 0xffc792ea : 0xff8250df;
              case CodeHighlight.OPERATOR: return dark ? 0xff89ddff : 0xff0550ae;
              case CodeHighlight.CONSTANT: return dark ? 0xffffcb6b : 0xff953800;
              case CodeHighlight.STRING: return dark ? 0xffc3e88d : 0xff22863a;
              case CodeHighlight.NUMBER: return dark ? 0xfff78c6c : 0xffb35900;
              case CodeHighlight.COMMENT: return dark ? 0xff969eaa : 0xff6a737d;
              case CodeHighlight.FUNCTION: return dark ? 0xff82aaff : 0xff6f42c1;
              default: return owner.getTextColorSet().defaultTextColor();
            }
          }
        };
      }
      entities[index] = new TextEntityCustom(owner.controller(), owner.tdlib(), code,
        token.start, token.end, TextEntityCustom.FLAG_MONOSPACE, owner.openParameters())
        .setCustomColorSet(colors[token.type]);
    }
    return new TextWrapper(code, style, owner.getTextColorSet(), entities, null)
      .setClickCallback(owner.clickCallback());
  }

  private final class SelectionLeaf {
    final BlockNode node;
    final TextWrapper wrapper;
    final String text;
    final int globalStart;
    final int lineOffset;
    final int top;
    final int bottom;
    final @Nullable String html;

    SelectionLeaf (BlockNode node, TextWrapper wrapper, String text, int globalStart, int lineOffset,
                   int top, int bottom, @Nullable String html) {
      this.node = node;
      this.wrapper = wrapper;
      this.text = text;
      this.globalStart = globalStart;
      this.lineOffset = lineOffset;
      this.top = top;
      this.bottom = bottom;
      this.html = html;
    }
  }

  private static final class TextNodePart {
    final TextWrapper wrapper;
    final String text;
    final String separator;
    final int top;
    final int bottom;
    final @Nullable String html;
    final String prefix;

    TextNodePart (TextWrapper wrapper, String text, String separator, int top, int bottom,
                  @Nullable String html, String prefix) {
      this.wrapper = wrapper;
      this.text = text;
      this.separator = separator;
      this.top = top;
      this.bottom = bottom;
      this.html = html;
      this.prefix = prefix;
    }
  }

  private static int countNewlines (String text) {
    int result = 0;
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) == '\n') result++;
    }
    return result;
  }

  private static TdApi.RichText fallbackText (RichMessageFlattener.Node spec) {
    String readable = RichMessageUtils.toPlainText(new TdApi.RichMessage(
      new TdApi.PageBlock[] { spec.block }, false, true));
    if (readable.isEmpty()) {
      readable = "[Unsupported content]";
    }
    return new TdApi.RichTextPlain(readable);
  }

  private static boolean isHeading (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockTitle.CONSTRUCTOR:
      case TdApi.PageBlockSubtitle.CONSTRUCTOR:
      case TdApi.PageBlockHeader.CONSTRUCTOR:
      case TdApi.PageBlockSubheader.CONSTRUCTOR:
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
        return true;
    }
    return false;
  }

  private static TextStyleProvider styleFor (RichMessageFlattener.Node spec) {
    float size = TGMessage.getTextStyleProvider().getTextSize();
    if (spec.path.endsWith("/credit") || spec.path.endsWith("/caption")) {
      size -= 2f;
    } else {
      switch (spec.block.getConstructor()) {
        case TdApi.PageBlockTitle.CONSTRUCTOR: size += 3f; break;
        case TdApi.PageBlockSubtitle.CONSTRUCTOR:
        case TdApi.PageBlockHeader.CONSTRUCTOR: size += 2f; break;
        case TdApi.PageBlockSubheader.CONSTRUCTOR: size += 1f; break;
        case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
          size += 4 - Math.max(1, Math.min(6, ((TdApi.PageBlockSectionHeading) spec.block).size));
          break;
        case TdApi.PageBlockPreformatted.CONSTRUCTOR: size -= 1f; break;
        case TdApi.PageBlockPullQuote.CONSTRUCTOR: size -= 2f; break;
        case TdApi.PageBlockFooter.CONSTRUCTOR:
        case TdApi.PageBlockAuthorDate.CONSTRUCTOR: size -= 2f; break;
      }
    }
    return new TextStyleProvider(Fonts.newRobotoStorage()).setTextSize(Math.max(8f, size)).setAllowSp(true);
  }

  private static String mediaLabel (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockAudio.CONSTRUCTOR: return "[Audio]";
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR: return "[Voice message]";
      case TdApi.PageBlockMap.CONSTRUCTOR: return "[Map]";
      case TdApi.PageBlockCollage.CONSTRUCTOR: return "[Media collage]";
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: return "[Slideshow]";
      default: return "[Media]";
    }
  }

  private static String escapeHtml (String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private final class RichAccessibilityProvider extends AccessibilityNodeProvider {
    private static final int HOST_ID = -1;
    private static final int SHOW_MORE_ID = -2;
    private int accessibilityFocus = Integer.MIN_VALUE;
    private final MessageView view;
    private final int[] screenLocation = new int[2];
    private final Rect bounds = new Rect();

    RichAccessibilityProvider (MessageView view) {
      this.view = view;
    }

    @Nullable
    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo (int virtualViewId) {
      if (virtualViewId == HOST_ID) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(view);
        info.setClassName(MessageView.class.getName());
        info.setPackageName(view.getContext().getPackageName());
        String summary = selectionText;
        if (summary.length() > 320) {
          summary = summary.substring(0, 320) + "…";
        }
        info.setContentDescription(summary);
        for (BlockNode node : nodes) {
          if (isExposed(node)) {
            info.addChild(view, virtualId(node));
          }
        }
        if (!message.isFull) info.addChild(view, SHOW_MORE_ID);
        return info;
      }
      if (virtualViewId == SHOW_MORE_ID && !message.isFull) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(view, SHOW_MORE_ID);
        info.setParent(view);
        info.setPackageName(view.getContext().getPackageName());
        info.setClassName("android.widget.Button");
        info.setText(Lang.getString(partialFailed ? R.string.RichMessageRetry : R.string.RichMessageShowMore));
        info.setEnabled(!partialLoading);
        info.setVisibleToUser(view.isShown());
        info.setClickable(!partialLoading);
        if (!partialLoading) info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        int top = owner.getContentY() + Math.min(measuredHeight, Screen.dp(PARTIAL_MAX_HEIGHT_DP));
        bounds.set(owner.getContentX(), top, owner.getContentX() + measuredWidth,
          top + Screen.dp(PARTIAL_BUTTON_HEIGHT_DP));
        info.setBoundsInParent(bounds);
        view.getLocationOnScreen(screenLocation);
        bounds.offset(screenLocation[0], screenLocation[1]);
        info.setBoundsInScreen(bounds);
        addFocusActions(info, virtualViewId);
        return info;
      }
      BlockNode node = findNode(virtualViewId);
      if (node == null || !isExposed(node)) {
        return null;
      }
      AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
      info.setPackageName(view.getContext().getPackageName());
      info.setClassName(accessibilityClass(node));
      info.setSource(view, virtualViewId);
      info.setParent(view);
      info.setVisibleToUser(view.isShown());
      info.setEnabled(true);
      CharSequence description = accessibilityDescription(node);
      info.setText(description);
      info.setContentDescription(description);
      int left = owner.getContentX() + (message.isRtl ? 0 : node.contentInset);
      int top = owner.getContentY() + node.top;
      bounds.set(left, top, left + Math.max(1, node.width),
        top + Math.max(1, node.height));
      info.setBoundsInParent(bounds);
      view.getLocationOnScreen(screenLocation);
      bounds.offset(screenLocation[0], screenLocation[1]);
      info.setBoundsInScreen(bounds);
      if (isHeading(node.spec.block) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.setHeading(true);
      }
      if (node.spec.hasCheckbox) {
        info.setCheckable(true);
        info.setChecked(node.spec.checked);
      }
      if (node instanceof DetailsNode) {
        info.setClickable(true);
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        boolean expanded = node.spec.state.detailsOpen;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          info.addAction(expanded ? AccessibilityNodeInfo.ACTION_COLLAPSE :
            AccessibilityNodeInfo.ACTION_EXPAND);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          info.setStateDescription(Lang.getString(expanded ?
            R.string.RichMessageExpanded : R.string.RichMessageCollapsed));
        }
      } else if (node instanceof MediaNode) {
        boolean clickable = !((MediaNode) node).wrappers.isEmpty() ||
          ((MediaNode) node).mapImage != null ||
          ((MediaNode) node).audioResult != null;
        info.setClickable(clickable);
        if (clickable) {
          info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
      } else if (node.scrollRange() > 0f) {
        info.setScrollable(true);
        if (node.spec.state.horizontalOffset < node.scrollRange()) {
          info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        if (node.spec.state.horizontalOffset > 0f) {
          info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
      }
      if (node instanceof CodeNode) {
        info.addAction(AccessibilityNodeInfo.ACTION_COPY);
      }
      addFocusActions(info, virtualViewId);
      return info;
    }

    private void addFocusActions (AccessibilityNodeInfo info, int id) {
      boolean focused = accessibilityFocus == id;
      info.setAccessibilityFocused(focused);
      info.addAction(focused ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS :
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
    }

    @Nullable
    @Override
    public AccessibilityNodeInfo findFocus (int focus) {
      return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY ?
        createAccessibilityNodeInfo(accessibilityFocus) : null;
    }

    private void sendFocusEvent (int id, int type) {
      if (view.getParent() == null) return;
      AccessibilityEvent event = AccessibilityEvent.obtain(type);
      event.setPackageName(view.getContext().getPackageName());
      event.setSource(view, id);
      view.getParent().requestSendAccessibilityEvent(view, event);
    }

    @Override
    public boolean performAction (int virtualViewId, int action,
                                  @Nullable Bundle arguments) {
      if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
        if (accessibilityFocus != virtualViewId) return false;
        accessibilityFocus = Integer.MIN_VALUE;
        sendFocusEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        return true;
      }
      if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
        if (virtualViewId == HOST_ID || accessibilityFocus == virtualViewId) return false;
        AccessibilityNodeInfo info = createAccessibilityNodeInfo(virtualViewId);
        if (info == null) return false;
        info.recycle();
        if (accessibilityFocus != Integer.MIN_VALUE) {
          sendFocusEvent(accessibilityFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        }
        accessibilityFocus = virtualViewId;
        sendFocusEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        return true;
      }
      if (virtualViewId == SHOW_MORE_ID && !message.isFull && !partialLoading &&
          action == AccessibilityNodeInfo.ACTION_CLICK) {
        callback.onRequestFullMessage();
        return true;
      }
      BlockNode node = findNode(virtualViewId);
      if (node == null) {
        return false;
      }
      if (node instanceof CodeNode && action == AccessibilityNodeInfo.ACTION_COPY) {
        ((CodeNode) node).copyCode();
        return true;
      }
      if (action == AccessibilityNodeInfo.ACTION_CLICK ||
          action == AccessibilityNodeInfo.ACTION_EXPAND ||
          action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
        if (node instanceof DetailsNode) {
          boolean open = node.spec.state.detailsOpen;
          if (action == AccessibilityNodeInfo.ACTION_CLICK ||
              action == AccessibilityNodeInfo.ACTION_EXPAND && !open ||
              action == AccessibilityNodeInfo.ACTION_COLLAPSE && open) {
            toggleDetails((DetailsNode) node);
          }
          announceChanged();
          return true;
        }
        if (node instanceof MediaNode &&
            !((MediaNode) node).wrappers.isEmpty()) {
          openMedia(((MediaNode) node).wrappers.get(0));
          return true;
        }
        if (node instanceof MediaNode && ((MediaNode) node).openMap()) {
          return true;
        }
        if (node instanceof MediaNode && ((MediaNode) node).audioResult != null) {
          return ((MediaNode) node).audioResult.performClick(view);
        }
      }
      if (node.scrollRange() > 0f &&
          (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
        node.scroll.stop();
        float oldOffset = node.spec.state.horizontalOffset;
        float delta = node.width * .7f *
          (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1f : -1f);
        node.spec.state.horizontalOffset += delta;
        node.scroll.clamp();
        if (oldOffset == node.spec.state.horizontalOffset) return false;
        owner.invalidate();
        announceChanged();
        return true;
      }
      return false;
    }

    private void announceChanged () {
      view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    private @Nullable BlockNode findNode (int id) {
      for (BlockNode node : nodes) {
        if (isExposed(node) && virtualId(node) == id) {
          return node;
        }
      }
      return null;
    }

    private int virtualId (BlockNode node) {
      int id = (int) RichMessageFlattener.receiverKey(node.spec.path,
        node.spec.kind) & Integer.MAX_VALUE;
      return id == HOST_ID ? Integer.MAX_VALUE : id;
    }

    private String accessibilityClass (BlockNode node) {
      if (node instanceof DetailsNode) return "android.widget.Button";
      if (node instanceof MediaNode) return "android.widget.ImageView";
      if (node instanceof TableNode) return "android.widget.HorizontalScrollView";
      if (node.spec.hasCheckbox) return "android.widget.CheckBox";
      return "android.widget.TextView";
    }

    private CharSequence accessibilityDescription (BlockNode node) {
      if (node instanceof TableNode) {
        return RichMessageUtils.toPlainText(new TdApi.RichMessage(
          new TdApi.PageBlock[] { node.spec.block }, message.isRtl, true));
      }
      if (node.spec.text != null) {
        String text = RichMessageUtils.richTextToPlain(node.spec.text);
        if (!text.isEmpty()) return text;
      }
      if (node instanceof MediaNode) return mediaLabel(node.spec.block);
      if (node instanceof FormulaNode) {
        return ((TdApi.PageBlockMathematicalExpression) node.spec.block).expression;
      }
      return fallbackText(node.spec).getConstructor() ==
        TdApi.RichTextPlain.CONSTRUCTOR ?
        ((TdApi.RichTextPlain) fallbackText(node.spec)).text : "";
    }
  }
}
