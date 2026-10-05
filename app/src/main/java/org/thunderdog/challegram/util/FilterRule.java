/*
 * This file is a part of Pulsargram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.util;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.unsorted.Settings;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import me.vkryl.core.StringUtils;

/**
 * Represents a single message filter rule with pattern and action
 */
public class FilterRule {
  
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    ACTION_HIDE,
    ACTION_SPOILER,
    ACTION_HIGHLIGHT
  })
  public @interface Action {}
  
  public static final int ACTION_HIDE = Settings.WORD_FILTER_MODE_HIDE;
  public static final int ACTION_SPOILER = Settings.WORD_FILTER_MODE_SPOILER;
  public static final int ACTION_HIGHLIGHT = Settings.WORD_FILTER_MODE_HIGHLIGHT;
  
  private final String pattern;
  private final @Action int action;
  
  public FilterRule(@NonNull String pattern, @Action int action) {
    this.pattern = pattern;
    this.action = action;
  }
  
  @NonNull
  public String getPattern() {
    return pattern;
  }
  
  @Action
  public int getAction() {
    return action;
  }
  
  /**
   * Serialize to string format: "pattern|action"
   */
  @NonNull
  public String serialize() {
    return pattern + "|" + action;
  }
  
  /**
   * Deserialize from string format: "pattern|action"
   */
  @Nullable
  public static FilterRule deserialize(@Nullable String serialized) {
    if (StringUtils.isEmpty(serialized)) {
      return null;
    }
    
    int separatorIndex = serialized.lastIndexOf('|');
    if (separatorIndex == -1) {
      // Legacy format: just pattern, default to HIDE
      return new FilterRule(serialized, ACTION_HIDE);
    }
    
    try {
      String pattern = serialized.substring(0, separatorIndex);
      int action = Integer.parseInt(serialized.substring(separatorIndex + 1));
      
      // Validate action
      if (action != ACTION_HIDE && action != ACTION_SPOILER && action != ACTION_HIGHLIGHT) {
        action = ACTION_HIDE;
      }
      
      return new FilterRule(pattern, action);
    } catch (Exception e) {
      // Fallback to legacy format
      return new FilterRule(serialized, ACTION_HIDE);
    }
  }
  
  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof FilterRule)) return false;
    FilterRule other = (FilterRule) obj;
    return pattern.equals(other.pattern) && action == other.action;
  }
  
  @Override
  public int hashCode() {
    return pattern.hashCode() * 31 + action;
  }
}
