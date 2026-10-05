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
 * File created for forum topics support
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifFile;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibEmojiManager;
import org.thunderdog.challegram.telegram.TdlibStatusManager;
import org.thunderdog.challegram.tool.DrawAlgorithms;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeInvalidateListener;
import org.thunderdog.challegram.theme.ThemeManager;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Icons;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.Highlight;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextMedia;
import org.thunderdog.challegram.widget.BaseView;

import java.util.LinkedHashMap;
import java.util.Map;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

public class ForumTopicView extends BaseView implements TdlibEmojiManager.Watcher, TdlibStatusManager.HelperTarget, Text.TextMediaListener, ThemeInvalidateListener {
  // Static cache for visible messages across all ForumTopicView instances
  private static final int MAX_CACHE_SIZE = 100;
  private static final Map<VisibleMessageCacheKey, TdApi.Message> globalVisibleMessageCache =
    new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true);

  private static final class VisibleMessageCacheKey {
    private final int accountId;
    private final long chatId;
    private final long topicId;
    private final long hiddenMessageId;

    private VisibleMessageCacheKey (int accountId, long chatId, long topicId, long hiddenMessageId) {
      this.accountId = accountId;
      this.chatId = chatId;
      this.topicId = topicId;
      this.hiddenMessageId = hiddenMessageId;
    }

    @Override
    public boolean equals (Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof VisibleMessageCacheKey)) return false;
      VisibleMessageCacheKey other = (VisibleMessageCacheKey) obj;
      return accountId == other.accountId && chatId == other.chatId && topicId == other.topicId &&
        hiddenMessageId == other.hiddenMessageId;
    }

    @Override
    public int hashCode () {
      int result = accountId;
      result = 31 * result + Long.hashCode(chatId);
      result = 31 * result + Long.hashCode(topicId);
      result = 31 * result + Long.hashCode(hiddenMessageId);
      return result;
    }
  }
  
  private static TextPaint titlePaint;
  private static TextPaint senderPaint;
  private static TextPaint previewPaint;
  private static TextPaint timePaint;
  private static Paint iconPaint;

  private Tdlib tdlib;
  private TdApi.ForumTopic topic;
  
  // Unique identifier to track which topic this view is currently displaying
  // Used to prevent stale async callbacks from updating recycled views
  private long currentTopicId;
  private long currentChatId;
  private long currentLastMessageId;
  private long bindGeneration;
  
  // Chat list mode (2-line, 3-line, or 3-line big)
  private int listMode;

  private String titleText;
  private String senderText;
  private String previewText;
  private String timeText;
  private Counter unreadCounter;
  private Counter reactionsCounter;
  private boolean isMuted;
  private String highlightQuery;

  // Message status for outgoing messages
  private boolean isSending;
  private boolean isOutgoing;
  private boolean isMessageUnread;
  private boolean showingDraft;

  // Icon loading
  private long customEmojiId;
  private TdlibEmojiManager.Entry customEmoji;
  private ImageFile thumbnail;
  private ImageFile imageFile;
  private GifFile gifFile;
  private final ComplexReceiver iconReceiver;

  // Text media (custom emoji in preview)
  private final ComplexReceiver textMediaReceiver;

  // Typing status
  private TdlibStatusManager.Helper statusHelper;
  private boolean isAttached;

  private Text displayTitle;
  private Text displaySender;
  private Text displayPreview;
  private TdApi.FormattedText previewFormattedText;
  
  // Message filtering support
  private TdApi.Message visibleMessage;
  private boolean isLookingUpVisibleMessage;
  private TdApi.Message cachedVisibleLastMessage;
  private long cachedForHiddenMessageId; // Track which hidden message this cache is for
  private boolean isPreviewHighlighted;
  
  private static final int PADDING_RIGHT = 12; // Reduced from 16 to 12 to give more space for text
  
  // Icon/avatar size and position - matching ChatView
  private static int getIconSize (int chatListMode) {
    switch (chatListMode) {
      case Settings.CHAT_MODE_3LINE_BIG:
        return Screen.dp(60f);
      case Settings.CHAT_MODE_3LINE:
        return Screen.dp(58f);
      case Settings.CHAT_MODE_2LINE:
      default:
        return Screen.dp(52f);
    }
  }
  
  private static int getIconLeft (int chatListMode) {
    return Screen.dp(7f); // Same as ChatView.getAvatarLeft()
  }
  
  private static int getIconTop (int chatListMode) {
    switch (chatListMode) {
      case Settings.CHAT_MODE_3LINE_BIG:
        return Screen.dp(11f);
      case Settings.CHAT_MODE_3LINE:
      case Settings.CHAT_MODE_2LINE:
      default:
        return Screen.dp(10f);
    }
  }
  
  private static int getPaddingLeft (int chatListMode) {
    // Same calculation as ChatView.getLeftPadding() for 3-line modes
    return getIconLeft(chatListMode) + getIconSize(chatListMode) + Screen.dp(11f);
  }
  
  // View height based on chat list mode
  public static int getViewHeight (int chatListMode) {
    switch (chatListMode) {
      case Settings.CHAT_MODE_3LINE_BIG:
        return Screen.dp(82f);
      case Settings.CHAT_MODE_3LINE:
        return Screen.dp(78f);
      case Settings.CHAT_MODE_2LINE:
      default:
        return Screen.dp(72f);
    }
  }

  public ForumTopicView (Context context) {
    super(context, null);
    setWillNotDraw(false);
    RippleSupport.setTransparentSelector(this);
    initPaints();
    iconReceiver = new ComplexReceiver(this, Config.MAX_ANIMATED_EMOJI_REFRESH_RATE);
    textMediaReceiver = new ComplexReceiver(this, Config.MAX_ANIMATED_EMOJI_REFRESH_RATE);
    listMode = Settings.instance().getChatListMode();
  }

  private static void initPaints () {
    if (titlePaint == null) {
      titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
      titlePaint.setTextSize(Screen.dp(16f));
      titlePaint.setTypeface(Fonts.getRobotoMedium());
      titlePaint.setColor(Theme.textAccentColor());
      ThemeManager.addThemeListener(titlePaint, ColorId.text);

      senderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
      senderPaint.setTextSize(Screen.dp(15f));
      senderPaint.setTypeface(Fonts.getRobotoRegular());
      senderPaint.setColor(Theme.textAccentColor());
      ThemeManager.addThemeListener(senderPaint, ColorId.text);

      previewPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
      previewPaint.setTextSize(Screen.dp(15f));
      previewPaint.setTypeface(Fonts.getRobotoRegular());
      previewPaint.setColor(Theme.textDecentColor());
      ThemeManager.addThemeListener(previewPaint, ColorId.textLight);

      timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
      timePaint.setTextSize(Screen.dp(12f));
      timePaint.setTypeface(Fonts.getRobotoRegular());
      timePaint.setColor(Theme.textDecentColor());
      ThemeManager.addThemeListener(timePaint, ColorId.textLight);

      iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    }
  }

  public void attach () {
    iconReceiver.attach();
    textMediaReceiver.attach();
    isAttached = true;
    if (statusHelper != null && topic != null) {
      statusHelper.attachToChat(topic.info.chatId, new TdApi.MessageTopicForum(topic.info.forumTopicId));
    }
  }

  public void detach () {
    iconReceiver.detach();
    textMediaReceiver.detach();
    isAttached = false;
    if (statusHelper != null) {
      statusHelper.detachFromAnyChat();
    }
  }

  public void destroy () {
    iconReceiver.performDestroy();
    textMediaReceiver.performDestroy();
    if (customEmojiId != 0 && customEmoji == null && tdlib != null) {
      tdlib.emoji().forgetWatcher(customEmojiId, this);
    }
    if (statusHelper != null) {
      statusHelper.detachFromAnyChat();
    }
    
    // Clear topic identity to invalidate any pending async callbacks
    bindGeneration++;
    isLookingUpVisibleMessage = false;
    currentTopicId = 0;
    currentChatId = 0;
    currentLastMessageId = 0;
    topic = null;
  }

  // TdlibStatusManager.HelperTarget implementation

  @Override
  public void layoutChatAction () {
    // Typing text layout is handled in onDraw
    invalidate();
  }

  @Override
  public void invalidateTypingPart (boolean onlyIcon) {
    invalidate();
  }

  @Override
  public boolean canLoop () {
    return isAttached;
  }

  @Override
  public boolean canAnimate () {
    return isAttached;
  }

  // ThemeInvalidateListener implementation

  @Override
  public void onThemeInvalidate (boolean isTempUpdate) {
    // Rebuild text layouts with new theme colors
    if (lastMeasuredWidth > 0) {
      buildTextLayouts();
    }
    // Redraw the view with new colors
    invalidate();
  }

  public void setTopic (Tdlib tdlib, TdApi.ForumTopic topic) {
    setTopic(tdlib, topic, null);
  }

  public void setTopic (Tdlib tdlib, TdApi.ForumTopic topic, @Nullable String highlightQuery) {
    boolean topicChanged = this.tdlib != tdlib || this.currentTopicId != topic.info.forumTopicId ||
      this.currentChatId != topic.info.chatId;
    int newListMode = Settings.instance().getChatListMode();
    long nextCustomEmojiId = topic.info.icon != null ? topic.info.icon.customEmojiId : 0;
    boolean iconChanged = topicChanged || customEmojiId != nextCustomEmojiId ||
      getIconSize(listMode) != getIconSize(newListMode);
    if (iconChanged && this.customEmojiId != 0 && this.customEmoji == null && this.tdlib != null) {
      this.tdlib.emoji().forgetWatcher(this.customEmojiId, this);
    }
    
    // Text can change on every bind; the icon animation keeps its receiver and
    // playback state until its identity or requested size actually changes.
    textMediaReceiver.clear();
    
    // Clear text layouts immediately to prevent drawing stale content
    // This is critical for preventing topic identity mix-ups during recycling
    displayTitle = null;
    displaySender = null;
    displayPreview = null;
    
    // Check if this is a different topic or if last message changed
    long lastMessageId = topic.lastMessage != null ? topic.lastMessage.id : 0;
    boolean lastMessageChanged = currentLastMessageId != lastMessageId;
    
    // Clear message filtering state only if topic or last message changed
    if (topicChanged || lastMessageChanged) {
      visibleMessage = null;
      cachedVisibleLastMessage = null;
      cachedForHiddenMessageId = 0;
      isLookingUpVisibleMessage = false;
      isPreviewHighlighted = false;
      bindGeneration++;
    }
    
    // Update topic identity FIRST before any async operations
    this.currentTopicId = topic.info.forumTopicId;
    this.currentChatId = topic.info.chatId;
    this.currentLastMessageId = lastMessageId;
    this.tdlib = tdlib;
    this.topic = topic;
    this.highlightQuery = highlightQuery;
    
    // Update list mode from settings (check if it changed)
    this.listMode = newListMode;

    // Initialize status helper for typing status
    if (statusHelper == null) {
      statusHelper = new TdlibStatusManager.Helper(context(), tdlib, this, null);
    }
    // Attach to this topic for typing status
    if (isAttached) {
      statusHelper.attachToChat(topic.info.chatId, new TdApi.MessageTopicForum(topic.info.forumTopicId));
    }

    // Build title (no emoji prefixes - icons are drawn separately)
    this.titleText = topic.info.name;

    // Check if we should show draft (draft exists with text input)
    boolean hasDraft = topic.draftMessage != null && topic.draftMessage.content != null &&
      topic.draftMessage.content.getConstructor() == TdApi.DraftMessageContentText.CONSTRUCTOR;

    if (hasDraft) {
      // Show draft preview
      this.showingDraft = true;
      TdApi.DraftMessageContentText draftText = (TdApi.DraftMessageContentText) topic.draftMessage.content;
      String draftString = draftText.text != null && !StringUtils.isEmpty(draftText.text.text) ?
        draftText.text.text : "";
      this.senderText = Lang.getString(R.string.Draft);
      this.previewText = draftString;
      this.previewFormattedText = draftText.text;
      this.timeText = Lang.timeOrDateShort(topic.draftMessage.date, java.util.concurrent.TimeUnit.SECONDS);
      this.isOutgoing = false;
      this.isSending = false;
      this.isMessageUnread = false;
      this.isPreviewHighlighted = false;
      this.visibleMessage = null;
    } else if (topic.lastMessage != null) {
      // Build preview text from last message
      this.showingDraft = false;
      
      TdApi.Message msg = topic.lastMessage;
      
      // Check if last message is hidden by filter
      if (ContentPreview.isMessageHiddenByFilter(msg)) {
        TdApi.Message cachedMessage = null;
        
        // First, check global cache (persists across view instances)
        cachedMessage = getCachedVisibleMessage(tdlib.id(), topic.info.chatId,
          topic.info.forumTopicId, msg.id);
        if (cachedMessage != null && ContentPreview.isMessageHiddenByFilter(cachedMessage)) {
          removeCachedVisibleMessage(tdlib.id(), topic.info.chatId,
            topic.info.forumTopicId, msg.id);
          cachedMessage = null;
        }
        
        // Fall back to instance cache if global cache misses
        if (cachedMessage == null && cachedVisibleLastMessage != null && 
            cachedVisibleLastMessage.chatId == topic.info.chatId &&
            cachedForHiddenMessageId == msg.id) {
          cachedMessage = cachedVisibleLastMessage;
          if (ContentPreview.isMessageHiddenByFilter(cachedMessage)) {
            cachedVisibleLastMessage = null;
            cachedForHiddenMessageId = 0;
            cachedMessage = null;
          }
        }
        
        if (cachedMessage != null) {
          // Use the cached visible message
          msg = cachedMessage;
        } else {
          // Start async lookup for visible message (if not already in progress)
          if (!isLookingUpVisibleMessage) {
            findVisibleMessage(msg);
          }
          // Don't process the hidden message - show empty preview
          // This creates a brief blank state until the lookup completes
          this.senderText = "";
          this.previewText = "";
          this.previewFormattedText = null;
          this.timeText = Lang.timeOrDateShort(topic.lastMessage.date, java.util.concurrent.TimeUnit.SECONDS);
          this.isOutgoing = false;
          this.isSending = false;
          this.isMessageUnread = false;
          this.isPreviewHighlighted = false;
          this.visibleMessage = null;
          msg = null; // Clear msg so it won't be processed below
        }
      }
      
      // Process the message (either original if not hidden, or cached visible message)
      // Only process if we have a valid message (not in lookup state)
      if (msg != null && !ContentPreview.isMessageHiddenByFilter(msg)) {
        setMessagePreview(msg);
      }
    } else {
      this.showingDraft = false;
      this.senderText = "";
      this.previewText = "";
      this.previewFormattedText = null;
      this.timeText = "";
      this.isOutgoing = false;
      this.isSending = false;
      this.isMessageUnread = false;
      this.isPreviewHighlighted = false;
      this.visibleMessage = null;
    }

    // Check muted state (respects useDefaultMuteFor and parent chat settings)
    this.isMuted = tdlib.forumTopicNeedsMuteIcon(topic.info.chatId, topic);

    // Unread counter - pass muted state for proper badge coloring
    if (topic.unreadCount > 0) {
      if (unreadCounter == null) {
        unreadCounter = new Counter.Builder().callback(this).build();
      }
      unreadCounter.setCount(topic.unreadCount, isMuted, false);
    } else {
      unreadCounter = null;
    }

    // Match TGChat's icon-only badge when there are unread reactions.
    if (topic.unreadReactionCount > 0) {
      if (reactionsCounter == null) {
        reactionsCounter = new Counter.Builder()
          .drawable(R.drawable.baseline_favorite_14, 16f, 0f, Gravity.CENTER)
          .callback(this)
          .build();
      }
      reactionsCounter.setCount(Tdlib.CHAT_MARKED_AS_UNREAD, isMuted, false);
    } else {
      reactionsCounter = null;
    }

    // Load topic icon
    if (iconChanged) {
      loadTopicIcon();
    }

    // Rebuild text layouts immediately with the new data
    // This ensures layouts are ready before the next draw cycle
    if (lastMeasuredWidth > 0) {
      buildTextLayouts();
    }

    invalidate();
  }

  private void setMessagePreview (TdApi.Message message) {
    ContentPreview preview = ContentPreview.getChatListPreview(tdlib, topic.info.chatId, message, true);
    TdApi.FormattedText formattedPreview = preview != null ? preview.buildFormattedText(false) : null;

    if (message.isOutgoing) {
      senderText = Lang.getString(R.string.FromYou);
    } else {
      String senderName = tdlib.senderName(message, false, false);
      senderText = !StringUtils.isEmpty(senderName) ? senderName : "";
    }
    previewText = formattedPreview != null ? formattedPreview.text : "";
    previewFormattedText = formattedPreview;
    timeText = Lang.timeOrDateShort(message.date, java.util.concurrent.TimeUnit.SECONDS);
    isOutgoing = message.isOutgoing;
    isSending = tdlib.messageSending(message);
    long relevantReadId = isOutgoing ? topic.lastReadOutboxMessageId : topic.lastReadInboxMessageId;
    isMessageUnread = message.id > relevantReadId;
    isPreviewHighlighted = preview != null && preview.isHighlighted;
    visibleMessage = message;
  }

  private int lastMeasuredWidth;

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    int width = getMeasuredWidth();
    if (lastMeasuredWidth != width) {
      lastMeasuredWidth = width;
      buildTextLayouts();
    }
  }

  private void buildTextLayouts () {
    int width = getMeasuredWidth();
    if (width <= 0 || topic == null) {
      displayTitle = null;
      displaySender = null;
      displayPreview = null;
      return;
    }
    
    // Validate that we're building layouts for the current topic
    // This prevents building layouts with stale data after recycling
    // Check BOTH the topic object AND that the string data matches
    if (topic.info.forumTopicId != currentTopicId || topic.info.chatId != currentChatId) {
      return;
    }
    
    // Additional validation: ensure the titleText matches the current topic's name
    // This catches cases where buildTextLayouts is called before setTopic completes
    if (!StringUtils.equalsOrBothEmpty(titleText, topic.info.name)) {
      return;
    }

    int textLeft = getPaddingLeft(listMode);
    int textRight = width - Screen.dp(PADDING_RIGHT);

    // CRITICAL FIX: Calculate widths that EXACTLY match what's used in onDraw()
    
    // ===== TITLE LINE WIDTH =====
    // Title line has: time + status icon + mute icon (inline with title text)
    int titleReservedWidth = 0;
    
    if (!StringUtils.isEmpty(timeText)) {
      float timeWidth = timePaint.measureText(timeText);
      titleReservedWidth += (int) Math.ceil(timeWidth);
      titleReservedWidth += Screen.dp(4f); // Space between time and status icon (from onDraw)
    }
    
    if (isOutgoing) {
      // Status icon width varies: 14dp for clock, 18dp for ticks (use max)
      titleReservedWidth += Screen.dp(18f);
    }
    
    // rightOffset calculation from onDraw: timeWidth + statusIconWidth + 8dp
    titleReservedWidth += Screen.dp(8f);
    
    if (isMuted) {
      // Mute icon is drawn inline after title, included in rightOffset
      titleReservedWidth += Screen.dp(18f);
    }
    
    int titleAvailWidth = textRight - textLeft - titleReservedWidth;
    
    // ===== PREVIEW LINE WIDTH =====
    // Preview line has: lock icon + unread counter + reactions counter
    int previewReservedWidth = 0;
    
    // Lock icon (only if topic is closed and no unread)
    boolean showLockIcon = topic.info.isClosed && (unreadCounter == null || topic.unreadCount == 0);
    if (showLockIcon) {
      previewReservedWidth += Screen.dp(18f); // Lock icon size
      previewReservedWidth += Screen.dp(4f);  // Spacing after lock icon
    }
    
    // Unread counter (rightmost)
    if (unreadCounter != null) {
      float counterWidth = unreadCounter.getWidth();
      previewReservedWidth += (int) Math.ceil(counterWidth);
      previewReservedWidth += Screen.dp(8f); // Spacing used in onDraw for unread counter
    }
    
    // Reactions counter (to the left of unread counter)
    if (reactionsCounter != null) {
      float counterWidth = reactionsCounter.getWidth();
      previewReservedWidth += (int) Math.ceil(counterWidth);
      previewReservedWidth += Screen.dp(4f); // Spacing used in onDraw for reactions counter
    }
    
    int previewAvailWidth = textRight - textLeft - previewReservedWidth;
    
    // Ensure minimum available widths
    int minWidth = Screen.dp(80f); // Reduced from 100dp to allow more flexibility
    if (titleAvailWidth < minWidth) {
      titleAvailWidth = minWidth;
    }
    if (previewAvailWidth < minWidth) {
      previewAvailWidth = minWidth;
    }

    // Determine font sizes based on chat list mode (matching ChatView pattern)
    float titleFontSize = listMode == Settings.CHAT_MODE_3LINE ? 16f : 17f;
    float textFontSize = listMode == Settings.CHAT_MODE_3LINE ? 15f : 16f;

    // Build title Text with emoji support - use titleAvailWidth for more space
    if (!StringUtils.isEmpty(titleText)) {
      Highlight highlight = !StringUtils.isEmpty(highlightQuery) ? Highlight.valueOf(titleText, highlightQuery) : null;
      displayTitle = new Text.Builder(
        titleText,
        titleAvailWidth,  // Use title-specific width
        Paints.robotoStyleProvider(titleFontSize),
        TextColorSets.Regular.NORMAL
      ).singleLine()
       .highlight(highlight)
       .allBold()
       .ignoreNewLines()
       .build();
    } else {
      displayTitle = null;
    }

    // In 2-line mode, keep the prefix separate so it can use the same color as ChatView drafts.
    if (listMode == Settings.CHAT_MODE_2LINE) {
      boolean hasSender = !StringUtils.isEmpty(senderText);
      boolean hasPreview = !StringUtils.isEmpty(previewText);
      if (hasSender) {
        displaySender = new Text.Builder(
          senderText,
          Math.min(previewAvailWidth, Screen.dp(120f)),
          Paints.robotoStyleProvider(textFontSize),
          showingDraft ? TextColorSets.Regular.NEGATIVE : TextColorSets.Regular.NORMAL
        ).singleLine()
         .ignoreNewLines()
         .suffix(hasPreview ? ": " : null)
         .build();
      } else {
        displaySender = null;
      }

      int textAvailWidth = previewAvailWidth - (displaySender != null ? displaySender.getWidth() : 0);
      if (previewFormattedText != null && hasPreview && textAvailWidth > 0) {
        displayPreview = new Text.Builder(
          tdlib,
          previewFormattedText,
          null, // urlOpenParameters
          textAvailWidth,
          Paints.robotoStyleProvider(textFontSize),
          TextColorSets.Regular.LIGHT,
          this // textMediaListener for custom emoji loading
        ).singleLine()
         .ignoreNewLines()
         .noClickable() // Disable link highlighting in chat list preview (consistent with TGChat)
         .build();
      } else if (hasPreview && textAvailWidth > 0) {
        displayPreview = new Text.Builder(
          previewText,
          textAvailWidth,
          Paints.robotoStyleProvider(textFontSize),
          TextColorSets.Regular.LIGHT
        ).singleLine()
         .ignoreNewLines()
         .build();
      } else {
        displayPreview = null;
      }
    } else {
      // 3-line mode: separate sender and preview lines
      // Build sender Text with emoji support (4-parameter constructor for String)
      if (!StringUtils.isEmpty(senderText)) {
        displaySender = new Text.Builder(
          senderText,
          previewAvailWidth,  // Use preview-specific width (sender is on preview line area)
          Paints.robotoStyleProvider(textFontSize),
          showingDraft ? TextColorSets.Regular.NEGATIVE : TextColorSets.Regular.NORMAL
        ).singleLine()
         .ignoreNewLines()
         .build();
      } else {
        displaySender = null;
      }

      // Build preview Text with custom emoji support
      if (previewFormattedText != null && !StringUtils.isEmpty(previewFormattedText.text)) {
        displayPreview = new Text.Builder(
          tdlib,
          previewFormattedText,
          null, // urlOpenParameters
          previewAvailWidth,  // Use preview-specific width
          Paints.robotoStyleProvider(textFontSize),
          TextColorSets.Regular.LIGHT,
          this // textMediaListener for custom emoji loading
        ).singleLine()
         .ignoreNewLines()
         .noClickable() // Disable link highlighting in chat list preview (consistent with TGChat)
         .build();
      } else if (!StringUtils.isEmpty(previewText)) {
        displayPreview = new Text.Builder(
          previewText,
          previewAvailWidth,  // Use preview-specific width
          Paints.robotoStyleProvider(textFontSize),
          TextColorSets.Regular.LIGHT
        ).singleLine()
         .ignoreNewLines()
         .build();
      } else {
        displayPreview = null;
      }
    }

    // Request text media for custom emoji
    requestTextMedia();
  }

  /**
   * Sets the topic view to display a message search result.
   */
  public void setMessageSearchResult (Tdlib tdlib, TdApi.ForumTopic topic, TdApi.Message foundMessage, String highlightQuery) {
    setTopic(tdlib, topic, highlightQuery);
    if (foundMessage == null || ContentPreview.isMessageHiddenByFilter(foundMessage)) {
      return;
    }

    // Search rows represent the matched message, not whichever message happens to be last in the topic.
    bindGeneration++;
    isLookingUpVisibleMessage = false;
    showingDraft = false;
    setMessagePreview(foundMessage);
    if (lastMeasuredWidth > 0) {
      buildTextLayouts();
    }
    invalidate();
  }

  /**
   * Find a visible (non-hidden) message asynchronously.
   * When a visible message is found, setTopic() is called again to update the UI.
   * 
   * @param fromMessage The message to start searching from (typically the hidden last message)
   */
  private void findVisibleMessage (@Nullable TdApi.Message fromMessage) {
    if (fromMessage == null || topic == null || isLookingUpVisibleMessage) {
      return;
    }

    isLookingUpVisibleMessage = true;
    final long searchTopicId = currentTopicId;
    final long searchChatId = currentChatId;
    final long searchGeneration = bindGeneration;
    final long hiddenMessageId = fromMessage.id;
    final Tdlib requestTdlib = tdlib;

    // Request up to 50 messages from this topic.
    // For forum topics, we must use GetForumTopicHistory to get messages from this specific topic
    // Using GetChatHistory would return messages from the entire chat, not just this topic
    requestTdlib.client().send(
      new TdApi.GetForumTopicHistory(topic.info.chatId, topic.info.forumTopicId, fromMessage.id, 0, 50),
      result -> {
        TdApi.Message visibleMessage = null;
        if (result.getConstructor() == TdApi.Messages.CONSTRUCTOR) {
          TdApi.Message[] messages = ((TdApi.Messages) result).messages;

          // Find first non-hidden message (messages are in reverse chronological order)
          for (TdApi.Message message : messages) {
            // Skip the original message we're searching from
            if (message.id == hiddenMessageId) {
              continue;
            }

            // Check if this message would be hidden by filter
            if (!ContentPreview.isMessageHiddenByFilter(message)) {
              visibleMessage = message;
              break;
            }
          }
        }

        final TdApi.Message resultMessage = visibleMessage;
        requestTdlib.ui().post(() -> {
          // RecyclerView may have rebound this instance while TDLib was doing the lookup.
          if (tdlib != requestTdlib || bindGeneration != searchGeneration ||
              currentTopicId != searchTopicId ||
              currentChatId != searchChatId || topic == null || topic.lastMessage == null ||
              topic.lastMessage.id != hiddenMessageId) {
            return;
          }

          isLookingUpVisibleMessage = false;
          if (resultMessage != null) {
            cachedVisibleLastMessage = resultMessage;
            cachedForHiddenMessageId = hiddenMessageId;
            putCachedVisibleMessage(requestTdlib.id(), searchChatId, searchTopicId,
              hiddenMessageId, resultMessage);
            setTopic(requestTdlib, topic, highlightQuery);
          }
        });
      }
    );
  }

  /**
   * Get cached visible message from global cache
   */
  private static TdApi.Message getCachedVisibleMessage (int accountId, long chatId, long topicId,
                                                        long hiddenMessageId) {
    synchronized (globalVisibleMessageCache) {
      return globalVisibleMessageCache.get(
        new VisibleMessageCacheKey(accountId, chatId, topicId, hiddenMessageId));
    }
  }

  private static void removeCachedVisibleMessage (int accountId, long chatId, long topicId,
                                                  long hiddenMessageId) {
    synchronized (globalVisibleMessageCache) {
      globalVisibleMessageCache.remove(
        new VisibleMessageCacheKey(accountId, chatId, topicId, hiddenMessageId));
    }
  }

  /**
   * Store visible message in global cache
   */
  private static void putCachedVisibleMessage (int accountId, long chatId, long topicId,
                                               long hiddenMessageId, TdApi.Message visibleMessage) {
    synchronized (globalVisibleMessageCache) {
      VisibleMessageCacheKey key = new VisibleMessageCacheKey(
        accountId, chatId, topicId, hiddenMessageId);
      globalVisibleMessageCache.put(key, visibleMessage);
      if (globalVisibleMessageCache.size() > MAX_CACHE_SIZE) {
        globalVisibleMessageCache.remove(globalVisibleMessageCache.keySet().iterator().next());
      }
    }
  }

  private void loadTopicIcon () {
    // Clear previous files
    customEmojiId = 0;
    customEmoji = null;
    imageFile = null;
    gifFile = null;
    thumbnail = null;
    iconReceiver.clear();

    TdApi.ForumTopicIcon icon = topic.info.icon;
    if (icon == null) {
      return;
    }

    if (icon.customEmojiId != 0) {
      // Custom emoji icon - request loading
      this.customEmojiId = icon.customEmojiId;
      this.customEmoji = tdlib.emoji().findOrPostponeRequest(customEmojiId, this);
      if (customEmoji != null && !customEmoji.isNotFound()) {
        buildCustomEmojiIcon(customEmoji);
      } else {
        // Trigger loading of postponed emoji requests
        tdlib.emoji().performPostponedRequests();
      }
    } else {
      this.customEmojiId = 0;
      this.customEmoji = null;
    }

    requestIconFiles();
  }

  private void buildCustomEmojiIcon (TdlibEmojiManager.Entry entry) {
    TdApi.Sticker sticker = entry.value;
    if (sticker == null) return;

    int size = getIconSize(listMode);

    // Thumbnail
    thumbnail = TD.toImageFile(tdlib, sticker.thumbnail);
    if (thumbnail != null) {
      thumbnail.setSize(size);
      thumbnail.setScaleType(ImageFile.FIT_CENTER);
      thumbnail.setNoBlur();
    }

    // Main image/animation
    switch (sticker.format.getConstructor()) {
      case TdApi.StickerFormatTgs.CONSTRUCTOR:
      case TdApi.StickerFormatWebm.CONSTRUCTOR: {
        this.gifFile = new GifFile(tdlib, sticker);
        this.gifFile.setScaleType(GifFile.FIT_CENTER);
        this.gifFile.setOptimizationMode(GifFile.OptimizationMode.EMOJI);
        this.gifFile.setRequestedSize(size);
        break;
      }
      case TdApi.StickerFormatWebp.CONSTRUCTOR: {
        this.imageFile = new ImageFile(tdlib, sticker.sticker);
        this.imageFile.setSize(size);
        this.imageFile.setScaleType(ImageFile.FIT_CENTER);
        this.imageFile.setNoBlur();
        break;
      }
    }
  }

  private void requestIconFiles () {
    DoubleImageReceiver preview = iconReceiver.getPreviewReceiver(0);
    preview.requestFile(null, thumbnail);
    if (imageFile != null) {
      iconReceiver.getImageReceiver(0).requestFile(imageFile);
    } else if (gifFile != null) {
      iconReceiver.getGifReceiver(0).requestFile(gifFile);
    }
  }

  @Override
  public void onCustomEmojiLoaded (TdlibEmojiManager context, TdlibEmojiManager.Entry entry) {
    // Emoji-manager callbacks run on the TDLib thread. Validate and mutate the
    // current binding together on the UI thread, including file construction.
    UI.post(() -> {
      if (topic == null || tdlib == null || tdlib.emoji() != context ||
          entry.customEmojiId != customEmojiId || customEmoji == entry) {
        return;
      }
      customEmoji = entry;
      if (!entry.isNotFound()) {
        buildCustomEmojiIcon(entry);
      }
      requestIconFiles();
      invalidate();
    });
  }

  @Override
  protected void onDraw (Canvas canvas) {
    if (topic == null) return;
    
    // Validate that we're drawing the correct topic
    // This prevents drawing stale data from recycled views
    if (topic.info.forumTopicId != currentTopicId || topic.info.chatId != currentChatId) {
      return;
    }

    int width = getMeasuredWidth();
    int height = getMeasuredHeight();

    // Draw highlight outline if preview is highlighted by filter
    if (isPreviewHighlighted) {
      Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      outlinePaint.setStyle(Paint.Style.STROKE);
      outlinePaint.setStrokeWidth(Screen.dp(4f));
      outlinePaint.setColor(Theme.getColor(ColorId.bubbleOut_textLink)); // Use bubbleOut_textLink color for highlight
      
      RectF rect = Paints.getRectF();
      float inset = Screen.dp(2f);
      rect.set(inset, inset, width - inset, height - inset);
      canvas.drawRoundRect(rect, Screen.dp(12f), Screen.dp(12f), outlinePaint);
    }

    // Draw topic icon
    int iconLeft = getIconLeft(listMode);
    int iconSize = getIconSize(listMode);
    int iconTop = getIconTop(listMode);
    int iconCenterX = iconLeft + iconSize / 2;
    int iconCenterY = iconTop + iconSize / 2;
    int iconRadius = iconSize / 2;

    TdApi.ForumTopicIcon icon = topic.info.icon;
    if (icon != null) {
      boolean hasLoadedEmoji = customEmojiId != 0 && customEmoji != null && !customEmoji.isNotFound();

      // Draw colored circle background only if no custom emoji loaded
      if (!hasLoadedEmoji) {
        iconPaint.setColor(getTopicColor(icon.color));
        canvas.drawCircle(iconCenterX, iconCenterY, iconRadius, iconPaint);
      }

      // Draw custom emoji icon if available
      if (hasLoadedEmoji) {
        drawCustomEmojiIcon(canvas, iconLeft, iconCenterY - iconRadius, iconLeft + iconSize, iconCenterY + iconRadius);
      } else if (customEmojiId == 0) {
        // No custom emoji - draw first letter
        drawLetterIcon(canvas, iconCenterX, iconCenterY);
      }
      // If customEmojiId != 0 but not loaded yet, just show colored circle
    }

    // Calculate text bounds
    int textLeft = getPaddingLeft(listMode);
    int textRight = width - Screen.dp(PADDING_RIGHT);

    // Draw time on the right
    float timeWidth = 0;
    float statusIconWidth = 0;
    if (!StringUtils.isEmpty(timeText)) {
      timeWidth = timePaint.measureText(timeText);
      canvas.drawText(timeText, textRight - timeWidth, Screen.dp(28f), timePaint);

      // Draw status icon for outgoing messages (to the left of time)
      if (isOutgoing) {
        float iconY = Screen.dp(28f);
        if (isSending) {
          // Clock icon for sending messages
          int iconX = (int) (textRight - timeWidth - Screen.dp(4f) - Screen.dp(Icons.CLOCK_SHIFT_X) - Screen.dp(10f));
          Drawables.draw(canvas, Icons.getClockIcon(ColorId.iconLight), iconX, iconY - Screen.dp(Icons.CLOCK_SHIFT_Y) - Screen.dp(10f), Paints.getIconLightPorterDuffPaint());
          statusIconWidth = Screen.dp(14f);
        } else {
          // Single tick for sent, double tick for read
          int iconX = (int) (textRight - timeWidth - Screen.dp(4f) - Screen.dp(Icons.TICKS_SHIFT_X) - Screen.dp(14f));
          Drawable tickIcon = isMessageUnread ? Icons.getSingleTick(ColorId.ticks) : Icons.getDoubleTick(ColorId.ticks);
          Paint tickPaint = isMessageUnread ? Paints.getTicksPaint() : Paints.getTicksReadPaint();
          Drawables.draw(canvas, tickIcon, iconX, iconY - Screen.dp(Icons.TICKS_SHIFT_Y) - Screen.dp(10f), tickPaint);
          statusIconWidth = Screen.dp(18f);
        }
      }
    }

    // Calculate right offset for icons that appear after title
    float rightOffset = timeWidth + statusIconWidth + Screen.dp(8f);
    if (isMuted) {
      rightOffset += Screen.dp(18f); // Space for mute icon
    }

    // Calculate Y positions based on chat list mode (matching ChatView exactly)
    int titleTop, textTop;
    if (listMode == Settings.CHAT_MODE_2LINE) {
      // 2-line mode
      titleTop = Screen.dp(12f);  // getTitleTop2 for 2-line
      textTop = Screen.dp(39.5f); // getTextTop for 2-line (baseline position)
    } else if (listMode == Settings.CHAT_MODE_3LINE_BIG) {
      // 3-line big mode
      titleTop = Screen.dp(10f);  // getTitleTop2 for 3-line modes
      textTop = Screen.dp(33f);   // getTextTop for 3-line big
    } else {
      // 3-line normal mode (default)
      titleTop = Screen.dp(10f);  // getTitleTop2 for 3-line modes
      textTop = Screen.dp(32f);   // getTextTop for 3-line
    }

    // Draw title with emoji support
    int titleRight = (int) (textRight - rightOffset);
    if (displayTitle != null) {
      displayTitle.draw(canvas, textLeft, titleTop);

      // Draw mute icon right after title text
      if (isMuted && displayTitle.getWidth() > 0) {
        int muteIconX = textLeft + displayTitle.getWidth() + Screen.dp(4f);
        int muteIconY = titleTop - Screen.dp(1f);
        Drawable muteIcon = org.thunderdog.challegram.tool.Icons.getChatMuteDrawable(ColorId.chatListMute);
        if (muteIcon != null) {
          org.thunderdog.challegram.tool.Drawables.drawRtl(canvas, muteIcon, muteIconX, muteIconY, org.thunderdog.challegram.tool.Paints.getChatsMutePaint(), width, false);
        }
      }
    }

    // Draw counters on the right side
    int previewRight = textRight;
    float counterCenterY = height / 2 + Screen.dp(12f);

    // Draw lock icon if topic is closed and has no unread messages
    boolean showLockIcon = topic.info.isClosed && (unreadCounter == null || topic.unreadCount == 0);
    if (showLockIcon) {
      int lockIconSize = Screen.dp(18f);
      int lockIconX = textRight - lockIconSize;
      int lockIconY = (int) counterCenterY - lockIconSize / 2;
      Drawable lockIcon = Drawables.get(getResources(), R.drawable.deproko_baseline_lock_24);
      if (lockIcon != null) {
        lockIcon.setBounds(lockIconX, lockIconY, lockIconX + lockIconSize, lockIconY + lockIconSize);
        lockIcon.setColorFilter(Theme.getColor(ColorId.iconLight), android.graphics.PorterDuff.Mode.SRC_IN);
        lockIcon.draw(canvas);
      }
      previewRight -= lockIconSize + Screen.dp(4f);
    }

    // Draw unread counter (rightmost)
    if (unreadCounter != null) {
      float counterWidth = unreadCounter.getWidth();
      previewRight -= (int) (counterWidth + Screen.dp(8f));
      unreadCounter.draw(canvas, textRight - counterWidth / 2, counterCenterY, Gravity.CENTER, 1f);
      textRight -= (int) (counterWidth + Screen.dp(4f));
    }

    // Draw reactions counter (to the left of unread counter)
    if (reactionsCounter != null) {
      float counterWidth = reactionsCounter.getWidth();
      previewRight -= (int) (counterWidth + Screen.dp(4f));
      int textColorId = isMuted ? ColorId.badgeMutedText : ColorId.badgeText;
      reactionsCounter.draw(canvas, textRight - counterWidth / 2, counterCenterY, Gravity.CENTER, 1f, this, textColorId);
      textRight -= (int) (counterWidth + Screen.dp(4f));
    }

    // Check if we should show typing status instead of sender/preview text
    TdlibStatusManager.ChatState typingState = statusHelper != null ? statusHelper.drawingState() : null;

    if (typingState != null) {
      // Draw typing status (matching ChatView's typing display)
      String typingText = statusHelper.fullText();
      if (!StringUtils.isEmpty(typingText)) {
        float top = textTop;
        if (listMode != Settings.CHAT_MODE_2LINE) {
          top += Screen.dp(2f); // single line offset
        }
        
        float textLineHeight = previewPaint.descent() - previewPaint.ascent();
        float centerY = top + textLineHeight / 2f;
        
        // Draw typing animation icon
        int iconWidth = DrawAlgorithms.drawStatus(canvas, typingState, textLeft, centerY, Theme.getColor(ColorId.textLight), this, ColorId.textLight);
        // Draw typing text
        String ellipsizedTyping = TextUtils.ellipsize(typingText, previewPaint, previewRight - textLeft - iconWidth, TextUtils.TruncateAt.END).toString();
        canvas.drawText(ellipsizedTyping, textLeft + iconWidth, top - previewPaint.ascent(), previewPaint);
      }
    } else if (listMode == Settings.CHAT_MODE_2LINE) {
      // 2-line mode: draw the colored prefix and light preview as separate text layouts.
      int previewLeft = textLeft;
      if (displaySender != null) {
        displaySender.draw(canvas, previewLeft, textTop);
        previewLeft += displaySender.getWidth();
      }
      if (displayPreview != null) {
        displayPreview.draw(canvas, previewLeft, textTop, null, 1f, textMediaReceiver);
      }
    } else {
      // 3-line mode: Draw sender and preview on separate lines (matching ChatView logic)
      int currentTextTop = textTop;
      
      // Draw sender (prefix in ChatView terminology)
      if (displaySender != null) {
        displaySender.draw(canvas, textLeft, currentTextTop, null, 1f);
        // Move to next line position
        currentTextTop += displaySender.getNextLineHeight();
      } else if (!StringUtils.isEmpty(senderText)) {
        // Fallback: draw simple text and estimate line height
        if (showingDraft) {
          int savedColor = senderPaint.getColor();
          senderPaint.setColor(Theme.textRedColor());
          String ellipsizedSender = TextUtils.ellipsize(senderText, senderPaint, previewRight - textLeft, TextUtils.TruncateAt.END).toString();
          canvas.drawText(ellipsizedSender, textLeft, currentTextTop + Screen.dp(14f), senderPaint);
          senderPaint.setColor(savedColor);
        } else {
          String ellipsizedSender = TextUtils.ellipsize(senderText, senderPaint, previewRight - textLeft, TextUtils.TruncateAt.END).toString();
          canvas.drawText(ellipsizedSender, textLeft, currentTextTop + Screen.dp(14f), senderPaint);
        }
        // Estimate line height for simple text (approximately 18dp for 15sp font)
        currentTextTop += Screen.dp(18f);
      }

      // Draw message preview on the next line
      if (displayPreview != null) {
        displayPreview.draw(canvas, textLeft, currentTextTop, null, 1f, textMediaReceiver);
      }
    }

    // Draw separator line at bottom
    canvas.drawLine(textLeft, height - 1, width, height - 1, Paints.strokeSeparatorPaint(ColorId.separator));
  }

  private void drawCustomEmojiIcon (Canvas canvas, int left, int top, int right, int bottom) {
    // Check if we need to apply color filter for themed stickers
    boolean needRepainting = customEmoji != null && TD.needThemedColorFilter(customEmoji.value);

    Receiver content;
    if (imageFile != null) {
      ImageReceiver image = iconReceiver.getImageReceiver(0);
      image.setBounds(left, top, right, bottom);
      content = image;
    } else if (gifFile != null) {
      GifReceiver gif = iconReceiver.getGifReceiver(0);
      gif.setBounds(left, top, right, bottom);
      content = gif;
    } else {
      content = null;
    }

    DoubleImageReceiver preview = content == null || content.needPlaceholder() ? iconReceiver.getPreviewReceiver(0) : null;
    if (preview != null) {
      if (needRepainting) {
        preview.setThemedPorterDuffColorId(ColorId.icon);
      } else {
        preview.disablePorterDuffColorFilter();
      }
      preview.setBounds(left, top, right, bottom);
      preview.draw(canvas);
    }
    if (content != null) {
      if (needRepainting) {
        content.setThemedPorterDuffColorId(ColorId.icon);
      } else {
        content.disablePorterDuffColorFilter();
      }
      content.draw(canvas);
    }
  }

  private void drawLetterIcon (Canvas canvas, int centerX, int centerY) {
    // For General topic (id = 1), show hash symbol "#" instead of letter
    // This matches Telegram for Android (TGA) behavior
    String displayChar;
    if (topic.info.forumTopicId == 1) {
      displayChar = "#";
    } else if (!StringUtils.isEmpty(topic.info.name)) {
      displayChar = topic.info.name.substring(0, 1).toUpperCase();
    } else {
      return;
    }

    TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    letterPaint.setColor(0xFFFFFFFF);
    letterPaint.setTextSize(Screen.dp(20f));
    letterPaint.setTypeface(Fonts.getRobotoMedium());
    letterPaint.setTextAlign(Paint.Align.CENTER);
    Paint.FontMetrics fm = letterPaint.getFontMetrics();
    float textY = centerY - (fm.ascent + fm.descent) / 2;
    canvas.drawText(displayChar, centerX, textY, letterPaint);
  }

  private int getTopicColor (int colorValue) {
    // Telegram topic colors are passed as actual color values like 0x6FB9F0
    // If color is 0 or very small, it's likely a color index (old format)
    if (colorValue > 0x00FFFFFF) {
      // It's already an actual color value with alpha
      return colorValue;
    } else if (colorValue >= 0x100000) {
      // It's a color without alpha - add full opacity
      return 0xFF000000 | colorValue;
    }

    // Fallback: treat as color index for backwards compatibility
    int[] colors = {
      0xFF6FB9F0, // Blue
      0xFFFFD67E, // Yellow
      0xFFCB86DB, // Purple
      0xFF8EEE98, // Green
      0xFFFF93B2, // Pink
      0xFFFB6F5F  // Red
    };
    if (colorValue >= 0 && colorValue < colors.length) {
      return colors[colorValue];
    }
    return colors[0];
  }

  private void drawHighlightedText (Canvas canvas, String text, float x, float y, TextPaint paint, String query) {
    if (StringUtils.isEmpty(query)) {
      canvas.drawText(text, x, y, paint);
      return;
    }

    String lowerText = text.toLowerCase();
    String lowerQuery = query.toLowerCase();
    int matchStart = lowerText.indexOf(lowerQuery);

    if (matchStart < 0) {
      // No match found - draw normal text
      canvas.drawText(text, x, y, paint);
      return;
    }

    int matchEnd = matchStart + query.length();

    // Draw text before highlight
    if (matchStart > 0) {
      String beforeMatch = text.substring(0, matchStart);
      canvas.drawText(beforeMatch, x, y, paint);
      x += paint.measureText(beforeMatch);
    }

    // Draw highlighted part with background
    String matchedText = text.substring(matchStart, matchEnd);
    float matchWidth = paint.measureText(matchedText);

    // Draw highlight background
    Paint.FontMetrics fm = paint.getFontMetrics();
    RectF highlightRect = new RectF(
      x - Screen.dp(1f),
      y + fm.ascent - Screen.dp(1f),
      x + matchWidth + Screen.dp(1f),
      y + fm.descent + Screen.dp(1f)
    );
    Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    highlightPaint.setColor(Theme.getColor(ColorId.textSearchQueryHighlight));
    canvas.drawRoundRect(highlightRect, Screen.dp(2f), Screen.dp(2f), highlightPaint);

    // Draw highlighted text
    canvas.drawText(matchedText, x, y, paint);
    x += matchWidth;

    // Draw text after highlight
    if (matchEnd < text.length()) {
      String afterMatch = text.substring(matchEnd);
      canvas.drawText(afterMatch, x, y, paint);
    }
  }

  // Text.TextMediaListener implementation
  @Override
  public void onInvalidateTextMedia (Text text, @Nullable TextMedia specificMedia) {
    // Validate that this callback is for the current topic's text
    // If the view was recycled, the displayPreview reference will be different
    if (text == displayPreview && topic != null) {
      invalidate();
    }
  }

  private void requestTextMedia () {
    if (displayPreview != null && displayPreview.hasMedia()) {
      textMediaReceiver.clear();
      displayPreview.requestMedia(textMediaReceiver);
    } else {
      textMediaReceiver.clear();
    }
  }
}
