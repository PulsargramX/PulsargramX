package org.thunderdog.challegram.util;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import me.vkryl.core.StringUtils;

/**
 * Utility class for filtering messages based on user-defined patterns
 */
public class MessageFilter {

  /**
   * Result of message filtering check
   */
  public static class FilterResult {
    public final boolean isFiltered;
    public final @FilterRule.Action int action;
    public final boolean hasHideAction;
    public final boolean hasSpoilerAction;
    public final boolean hasHighlightAction;
    
    public FilterResult(boolean isFiltered, @FilterRule.Action int action) {
      this.isFiltered = isFiltered;
      this.action = action;
      this.hasHideAction = (action == FilterRule.ACTION_HIDE);
      this.hasSpoilerAction = (action == FilterRule.ACTION_SPOILER);
      this.hasHighlightAction = (action == FilterRule.ACTION_HIGHLIGHT);
    }
    
    private FilterResult(boolean isFiltered, boolean hasHide, boolean hasSpoiler, boolean hasHighlight) {
      this.isFiltered = isFiltered;
      this.hasHideAction = hasHide;
      this.hasSpoilerAction = hasSpoiler;
      this.hasHighlightAction = hasHighlight;
      
      // Primary action for backward compatibility (priority: HIDE > SPOILER > HIGHLIGHT)
      if (hasHide) {
        this.action = FilterRule.ACTION_HIDE;
      } else if (hasSpoiler) {
        this.action = FilterRule.ACTION_SPOILER;
      } else if (hasHighlight) {
        this.action = FilterRule.ACTION_HIGHLIGHT;
      } else {
        this.action = FilterRule.ACTION_HIDE;
      }
    }
    
    public static final FilterResult NOT_FILTERED = new FilterResult(false, FilterRule.ACTION_HIDE);
  }

  /**
   * Check if message should be filtered and return the action to take
   * @param text The message text or caption to check
   * @return FilterResult with isFiltered flag and action type
   */
  public static FilterResult checkMessageFilter(@Nullable TdApi.FormattedText text) {
    if (text == null || StringUtils.isEmpty(text.text)) {
      return FilterResult.NOT_FILTERED;
    }

    if (!Settings.instance().isWordFilterEnabled()) {
      return FilterResult.NOT_FILTERED;
    }

    FilterRule[] rules = Settings.instance().getWordFilterRules();
    if (rules == null || rules.length == 0) {
      return FilterResult.NOT_FILTERED;
    }

    String messageText = text.text.toLowerCase();

    // Collect all matching actions
    boolean hasHide = false;
    boolean hasSpoiler = false;
    boolean hasHighlight = false;

    // Check each rule - collect all matches
    for (FilterRule rule : rules) {
      String pattern = rule.getPattern();
      if (StringUtils.isEmpty(pattern)) continue;

      boolean matches = false;
      try {
        // Convert glob pattern to regex
        String regexPattern = globToRegex(pattern.toLowerCase());
        if (Pattern.compile(regexPattern).matcher(messageText).find()) {
          matches = true;
        }
      } catch (PatternSyntaxException e) {
        // If pattern is invalid, try simple contains match
        if (messageText.contains(pattern.toLowerCase())) {
          matches = true;
        }
      }

      if (matches) {
        int action = rule.getAction();
        if (action == FilterRule.ACTION_HIDE) {
          hasHide = true;
        } else if (action == FilterRule.ACTION_SPOILER) {
          hasSpoiler = true;
        } else if (action == FilterRule.ACTION_HIGHLIGHT) {
          hasHighlight = true;
        }
      }
    }

    // Return result with all matched actions
    if (hasHide || hasSpoiler || hasHighlight) {
      return new FilterResult(true, hasHide, hasSpoiler, hasHighlight);
    }

    return FilterResult.NOT_FILTERED;
  }

  /**
   * Legacy method for backward compatibility
   * @deprecated Use checkMessageFilter() instead
   */
  @Deprecated
  public static boolean shouldFilterMessage(@Nullable TdApi.FormattedText text) {
    return checkMessageFilter(text).isFiltered;
  }

  /**
   * Convert glob pattern to regex with word boundaries
   * Supports: * (any chars)
   * Patterns:
   *   ad   -> matches exact word "ad"
   *   *ad  -> matches words ending with "ad"
   *   ad*  -> matches words starting with "ad"
   *   *ad* -> matches "ad" anywhere
   */
  private static String globToRegex(String glob) {
    boolean startsWithWildcard = glob.startsWith("*");
    boolean endsWithWildcard = glob.endsWith("*");

    // Remove wildcards for processing
    String core = glob.replaceAll("\\*", "");

    // Escape special regex characters in core
    String escaped = escapeRegex(core);

    if (!startsWithWildcard && !endsWithWildcard) {
      // "ad" -> exact word match
      return "\\b" + escaped + "\\b";
    } else if (startsWithWildcard && !endsWithWildcard) {
      // "*ad" -> contains word ending with "ad"
      return escaped + "\\b";
    } else if (!startsWithWildcard && endsWithWildcard) {
      // "ad*" -> contains word starting with "ad"
      return "\\b" + escaped;
    } else {
      // "*ad*" -> contains "ad" anywhere
      return escaped;
    }
  }

  /**
   * Escape special regex characters
   */
  private static String escapeRegex(String str) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '.':
        case '(':
        case ')':
        case '+':
        case '|':
        case '^':
        case '$':
        case '@':
        case '%':
        case '[':
        case ']':
        case '{':
        case '}':
        case '\\':
        case '?':
          result.append('\\').append(c);
          break;
        default:
          result.append(c);
      }
    }
    return result.toString();
  }

  /**
   * Extract text from message content (handles text and captions)
   */
  @Nullable
  public static TdApi.FormattedText extractTextFromMessage(TdApi.MessageContent content) {
    if (content == null) return null;

    switch (content.getConstructor()) {
      case TdApi.MessageText.CONSTRUCTOR:
        return ((TdApi.MessageText) content).text;
      case TdApi.MessagePhoto.CONSTRUCTOR:
        return ((TdApi.MessagePhoto) content).caption;
      case TdApi.MessageVideo.CONSTRUCTOR:
        return ((TdApi.MessageVideo) content).caption;
      case TdApi.MessageAnimation.CONSTRUCTOR:
        return ((TdApi.MessageAnimation) content).caption;
      case TdApi.MessageDocument.CONSTRUCTOR:
        return ((TdApi.MessageDocument) content).caption;
      case TdApi.MessageAudio.CONSTRUCTOR:
        return ((TdApi.MessageAudio) content).caption;
      case TdApi.MessageVoiceNote.CONSTRUCTOR:
        return ((TdApi.MessageVoiceNote) content).caption;
      default:
        return null;
    }
  }

  /**
   * Apply spoiler entity to entire text, respecting entity nesting rules.
   * 
   * Rules:
   * - Spoilers can overlap with: Bold, Italic, Underline, Strikethrough
   * - Spoilers CANNOT overlap with: Code, Pre, PreCode, other Spoilers
   * - When conflicts occur, split the spoiler around the conflicting entity
   * 
   * Example:
   * Input: "Hello **bold** `code` world"
   * Output: ||Hello **bold**|| `code` ||world||
   *        (spoiler wraps around code, but not over it)
   */
  public static TdApi.FormattedText applySpoilerToEntireText(TdApi.FormattedText text) {
    if (text == null || StringUtils.isEmpty(text.text)) {
      return text;
    }

    // Collect all existing entities
    List<TdApi.TextEntity> existingEntities = new ArrayList<>();
    if (text.entities != null && text.entities.length > 0) {
      existingEntities.addAll(Arrays.asList(text.entities));
    }

    // Find all "blocking" entities (Code, Pre, PreCode, existing Spoilers)
    List<TdApi.TextEntity> blockingEntities = new ArrayList<>();
    for (TdApi.TextEntity entity : existingEntities) {
      if (isBlockingSpoiler(entity.type)) {
        blockingEntities.add(entity);
      }
    }

    // Sort blocking entities by offset
    Collections.sort(blockingEntities, (a, b) -> Integer.compare(a.offset, b.offset));

    // Create spoiler entities in the gaps between blocking entities
    List<TdApi.TextEntity> newSpoilers = new ArrayList<>();
    int currentOffset = 0;
    int textLength = text.text.length();

    for (TdApi.TextEntity blocking : blockingEntities) {
      // Add spoiler before this blocking entity
      if (currentOffset < blocking.offset) {
        newSpoilers.add(new TdApi.TextEntity(
          currentOffset,
          blocking.offset - currentOffset,
          new TdApi.TextEntityTypeSpoiler()
        ));
      }
      // Skip past the blocking entity
      currentOffset = blocking.offset + blocking.length;
    }

    // Add final spoiler after last blocking entity
    if (currentOffset < textLength) {
      newSpoilers.add(new TdApi.TextEntity(
        currentOffset,
        textLength - currentOffset,
        new TdApi.TextEntityTypeSpoiler()
      ));
    }

    // If no blocking entities, just wrap the entire text
    if (newSpoilers.isEmpty() && blockingEntities.isEmpty()) {
      newSpoilers.add(new TdApi.TextEntity(
        0,
        textLength,
        new TdApi.TextEntityTypeSpoiler()
      ));
    }

    // Merge new spoilers with existing entities
    List<TdApi.TextEntity> allEntities = new ArrayList<>(existingEntities);
    allEntities.addAll(newSpoilers);

    // Sort entities by offset (TDLib requires sorted entities)
    Collections.sort(allEntities, (a, b) -> {
      int offsetCompare = Integer.compare(a.offset, b.offset);
      if (offsetCompare != 0) return offsetCompare;
      // If same offset, put longer entities first (parent before child)
      return Integer.compare(b.length, a.length);
    });

    return new TdApi.FormattedText(
      text.text,
      allEntities.toArray(new TdApi.TextEntity[0])
    );
  }

  /**
   * Check if an entity type blocks spoiler nesting
   */
  private static boolean isBlockingSpoiler(TdApi.TextEntityType type) {
    switch (type.getConstructor()) {
      case TdApi.TextEntityTypeCode.CONSTRUCTOR:
      case TdApi.TextEntityTypePre.CONSTRUCTOR:
      case TdApi.TextEntityTypePreCode.CONSTRUCTOR:
      case TdApi.TextEntityTypeSpoiler.CONSTRUCTOR:
        return true;
      default:
        return false;
    }
  }
}
