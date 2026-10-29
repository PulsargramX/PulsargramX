#!/usr/bin/env python3
"""Exercise sticker header indexing when the trending shortcut is toggled."""

import shutil
import unittest

from ime_animation_behavior_test import run_java
from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class TrendingStickersButtonTest(unittest.TestCase):
    def test_toggle_preserves_sections_and_pack_indices(self) -> None:
        source = read(
            "app/src/main/java/org/thunderdog/challegram/widget/emoji/header/MediaHeaderView.java"
        )
        methods = "\n".join(java_method(source, signature) for signature in (
            "public MediaAdapter (", "private void checkTrendingStickersButton ()",
            "public int getAddItemCount (", "private Object getObject (",
            "public void addStickerSet (", "public void moveStickerSet (",
            "public void removeStickerSet (",
        ))
        harness = r"""
import java.util.*;
public class ImeAnimationTest {
  @interface Nullable {}
  static class Context {}
  static class ViewController<T> {}
  interface OnClickListener {}
  static class R {
    static class drawable {
      static int baseline_emoticon_outline_24, deproko_baseline_gif_24,
        deproko_baseline_gif_filled_24, outline_whatshot_24, baseline_whatshot_24,
        baseline_access_time_24, baseline_watch_later_24;
    }
  }
  static class Settings {
    static final Settings INSTANCE = new Settings();
    boolean show = true;
    static Settings instance() { return INSTANCE; }
    boolean getTrendingStickersButton() { return show; }
  }
  static class EmojiLayout {
    final boolean animatedEmojiOnly;
    EmojiLayout(boolean only) { animatedEmojiOnly = only; }
    boolean isAnimatedEmojiOnly() { return animatedEmojiOnly; }
  }
  static class EmojiSection {
    final int index;
    EmojiSection(EmojiLayout parent, int index, int icon, int activeIcon) { this.index = index; }
    EmojiSection setMakeFirstTransparent() { return this; }
    EmojiSection setActiveDisabled() { return this; }
    void setIsTrending() {}
    void setFactor(float factor, boolean animated) {}
  }
  static class TGStickerSetInfo {}
  static class MediaAdapter {
    final Context context;
    final EmojiLayout parent;
    final OnClickListener onClickListener;
    final ViewController<?> themeProvider;
    final int sectionItemCount;
    final ArrayList<EmojiSection> headerItems;
    final ArrayList<TGStickerSetInfo> stickerSets;
    final EmojiSection trendingStickersSection, recentSection, trendingSection;
    Object selectedObject;
    boolean hasRecents, hasFavorite, hasTrending, showingRecentSection, showingTrendingSection;
    int changes;
    void notifyItemInserted(int index) { changes++; }
    void notifyItemRemoved(int index) { changes++; }
    void notifyItemMoved(int from, int to) { changes++; }
    __ADAPTER__
  }
  static class MediaHeaderView {
    final MediaAdapter mediaAdapter;
    Object selected;
    MediaHeaderView(MediaAdapter adapter) { mediaAdapter = adapter; }
    void setCurrentStickerSection(Object section, boolean animated) { selected = section; }
    __SELECT__
  }
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    for (boolean initiallyShown : new boolean[] {true, false}) {
      Settings.instance().show = initiallyShown;
      MediaAdapter adapter = new MediaAdapter(null, new EmojiLayout(false), null, 8, true, null, false);
      MediaHeaderView header = new MediaHeaderView(adapter);
      check(adapter.headerItems.contains(adapter.trendingStickersSection) == initiallyShown,
        "Apply the saved preference at creation");
      check(((EmojiSection) adapter.selectedObject).index == -2, "Preserve the selected GIF tab");
      adapter.hasRecents = adapter.showingRecentSection = true;
      adapter.headerItems.add(adapter.recentSection);
      TGStickerSetInfo first = new TGStickerSetInfo(), second = new TGStickerSetInfo();
      adapter.addStickerSet(0, first);
      adapter.addStickerSet(1, second);
      for (boolean show : new boolean[] {false, true, false, true}) {
        Settings.instance().show = show;
        adapter.checkTrendingStickersButton();
        int changes = adapter.changes;
        adapter.checkTrendingStickersButton();
        check(adapter.changes == changes, "Repeated visibility callbacks must be idempotent");
        check(adapter.headerItems.size() == (show ? 4 : 3), "Remove the button and its space");
        header.setCurrentStickerSectionByPosition(0, false, false);
        check(((EmojiSection) header.selected).index == -1, "Keep the emoji switch at the start");
        header.setCurrentStickerSectionByPosition(1, false, false);
        check(((EmojiSection) header.selected).index == -2, "Keep the GIF section mapping");
        header.setCurrentStickerSectionByPosition(2, false, false);
        check(header.selected == adapter.trendingStickersSection,
          "A trending controller callback must never highlight recents or an installed pack");
        header.setCurrentStickerSectionByPosition(0, true, false);
        check(header.selected == adapter.recentSection, "Keep recents selected correctly");
        header.setCurrentStickerSectionByPosition(1, true, false);
        check(header.selected == first, "Keep the first installed pack selected correctly");
        adapter.moveStickerSet(0, 1);
        header.setCurrentStickerSectionByPosition(1, true, false);
        check(header.selected == second, "Keep pack reordering independent of the shortcut");
        adapter.moveStickerSet(1, 0);
      }
      adapter.removeStickerSet(0);
      check(adapter.stickerSets.size() == 1 && adapter.stickerSets.get(0) == second,
        "Remove only the requested installed pack");
    }
    MediaAdapter statuses = new MediaAdapter(null, new EmojiLayout(true), null, 8, false, null, true);
    statuses.headerItems.add(statuses.trendingSection);
    for (boolean show : new boolean[] {false, true}) {
      Settings.instance().show = show;
      statuses.checkTrendingStickersButton();
      check(statuses.headerItems.size() == 1 && statuses.headerItems.get(0) == statuses.trendingSection,
        "Leave emoji status suggestions unchanged");
    }
  }
}
"""
        harness = harness.replace("__ADAPTER__", methods).replace(
            "__SELECT__", java_method(source, "public void setCurrentStickerSectionByPosition (")
        )
        run_java(harness)


if __name__ == "__main__":
    unittest.main()
