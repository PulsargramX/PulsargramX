#!/usr/bin/env python3
"""Exercise sticker sheet resizing with cached and recycled offset rows."""

import shutil
import unittest

from ime_animation_behavior_test import run_java
from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class StickerPanelLayoutTest(unittest.TestCase):
    def test_keyboard_resize_keeps_stickers_and_header_aligned(self) -> None:
        wrap = read(
            "app/src/main/java/org/thunderdog/challegram/component/sticker/StickerSetWrap.java"
        )
        controller = read(
            "app/src/main/java/org/thunderdog/challegram/ui/StickersListController.java"
        )
        adapter = read(
            "app/src/main/java/org/thunderdog/challegram/component/emoji/MediaStickersAdapter.java"
        )
        wrap_methods = "\n".join(java_method(wrap, signature) for signature in (
            "protected void onMeasure (", "protected void onLayout (",
            "private int getContentHeight ()", "private int calculateTotalHeight ()",
            "public int provideOffset ()", "public int provideReverseOffset ()",
            "private int getHeaderTop ()",
        ))
        controller_methods = "\n".join(java_method(controller, signature) for signature in (
            "public void onViewportChanged ()", "public void updateScrollState ()",
            "public int getOffsetScroll ()",
        ))
        padding = adapter[adapter.index("case TYPE_PADDING_OFFSETABLE: {"):]
        progress = adapter[adapter.index("case TYPE_PROGRESS_OFFSETABLE: {"):]
        height_field = wrap[wrap.index("  private int contentHeight"):].splitlines()[0]
        harness = r"""
import java.util.*;
public class ImeAnimationTest {
  interface OffsetProvider {
    int provideOffset();
    int provideReverseOffset();
    void onContentScroll(float shadowFactor);
  }
  static class Screen {
    static int smallestSide() { return 360; }
    static int dp(float value) { return (int) value; }
  }
  static class Size { static int getHeaderPortraitSize() { return 56; } }
  static class UI {
    static int activityHeight = 700;
    static UI getContext(Object context) { return new UI(); }
    int getVisibleContentHeight() { return activityHeight; }
  }
  static class MeasureSpec {
    static final int EXACTLY = 0x40000000;
    static int getSize(int spec) { return spec & 0x3fffffff; }
    static int makeMeasureSpec(int size, int mode) { return size | mode; }
  }
  static class View {
    final int type;
    int top, height;
    boolean layoutRequested = true;
    View(int type) { this.type = type; }
    void forceLayout() { layoutRequested = true; }
    int getTop() { return top; }
    int getSuggestedMinimumWidth() { return 0; }
    int getDefaultSize(int minimum, int spec) { return MeasureSpec.getSize(spec); }
    void setMeasuredDimension(int width, int height) { this.height = MeasureSpec.getSize(height); }
    protected void onMeasure(int widthSpec, int heightSpec) {
      setMeasuredDimension(widthSpec, heightSpec);
    }
    void measure() {
      // RecyclerView can reuse a wrap-content row with unchanged child measure specs.
      if (layoutRequested) {
        onMeasure(400, 0);
        layoutRequested = false;
      }
    }
  }
  static class ViewGroup extends View {
    final List<View> children = new ArrayList<>();
    ViewGroup(int type) { super(type); }
    int getChildCount() { return children.size(); }
    View getChildAt(int index) { return children.get(index); }
  }
  static class StickerSmallView extends View {
    static final int PADDING = 4;
    StickerSmallView() { super(StickerHolder.TYPE_STICKER); height = 80; }
    void attach() {}
    @Override protected void onMeasure(int width, int height) { this.height = 80; }
  }
  static class ProgressComponentView extends View {
    boolean attached;
    ProgressComponentView() { super(StickerHolder.TYPE_PROGRESS); }
    void attach() { attached = true; }
  }
  static class StickerHolder {
    static final int TYPE_STICKER = 1, TYPE_EMOJI_STATUS_DEFAULT = 2,
      TYPE_PROGRESS = 3, TYPE_PADDING_OFFSETABLE = 4, TYPE_PROGRESS_OFFSETABLE = 5;
    final View itemView;
    StickerHolder(View view) { itemView = view; }
    int getItemViewType() { return itemView.type; }
  }
  static class MediaStickersAdapter {
    static class StickerHolder extends ImeAnimationTest.StickerHolder {
      StickerHolder(View view) { super(view); }
    }
    __ATTACH__
  }
  static class Padding extends View {
    final OffsetProvider offsetProvider;
    Padding(OffsetProvider provider) { super(StickerHolder.TYPE_PADDING_OFFSETABLE); offsetProvider = provider; }
    __PADDING_MEASURE__
  }
  static class Progress extends ViewGroup {
    final OffsetProvider offsetProvider;
    Progress(OffsetProvider provider) {
      super(StickerHolder.TYPE_PROGRESS_OFFSETABLE);
      offsetProvider = provider;
      children.add(new ProgressComponentView());
    }
    __PROGRESS_MEASURE__
  }
  static class RecyclerView extends ViewGroup {
    RecyclerView() { super(-1); }
  }
  static class Manager {
    final Map<Integer, View> positions = new HashMap<>();
    int first;
    int getItemViewType(View view) { return view.type; }
    int findFirstVisibleItemPosition() { return first; }
    View findViewByPosition(int position) { return positions.get(position); }
  }
  static class StickersListController {
    RecyclerView recyclerView;
    final Manager manager = new Manager();
    OffsetProvider offsetProvider;
    int offsetScroll;
    __CONTROLLER__
  }
  static class Frame {
    StickerSetWrap sheet;
    Object getContext() { return null; }
    protected void onMeasure(int width, int height) {
      for (View view : sheet.stickersController.recyclerView.children) view.measure();
    }
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      int y = -sheet.scroll;
      for (View view : sheet.stickersController.recyclerView.children) {
        view.top = y;
        y += view.height;
      }
    }
  }
  static class StickerSetWrap extends Frame implements OffsetProvider {
    final StickersListController stickersController = new StickersListController();
    __HEIGHT_FIELD__
    int scroll, headerY;
    StickerSetWrap() {
      sheet = this;
      stickersController.offsetProvider = this;
      stickersController.recyclerView = new RecyclerView();
    }
    void updateHeader() { headerY = getHeaderTop(); }
    public void onContentScroll(float shadowFactor) { updateHeader(); }
    void resize(int height) {
      onMeasure(400, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
      onLayout(true, 0, 0, 400, height);
    }
    __WRAP__
  }
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  static void add(StickerSetWrap sheet, int position, View view) {
    sheet.stickersController.recyclerView.children.add(view);
    sheet.stickersController.manager.positions.put(position, view);
    new MediaStickersAdapter().onViewAttachedToWindow(new MediaStickersAdapter.StickerHolder(view));
  }
  public static void main(String[] args) {
    for (boolean loading : new boolean[] {false, true}) {
      StickerSetWrap sheet = new StickerSetWrap();
      Padding padding = new Padding(sheet);
      View content = loading ? new Progress(sheet) : new StickerSmallView();
      add(sheet, 0, padding);
      add(sheet, 1, content);
      int[][] sizes = {{700, 340}, {740, 370}, {820, 440}, {940, 560}, {1000, 620},
        {940, 560}, {700, 340}, {420, 60}, {160, 0}};
      for (int[] size : sizes) {
        int height = size[0], expectedOffset = size[1];
        // The activity's previous measurement may lag behind the popup's current specs.
        sheet.resize(height);
        check(padding.height == sheet.headerY, "Cached spacer and header must move together");
        check(content.top == sheet.headerY, "Sticker rows must remain below the header");
        check(sheet.headerY == expectedOffset, "Use the sheet's current height");
        if (loading) {
          check(content.height == Math.max(0, height - expectedOffset - 112),
            "Loading space must use the sheet height and never become negative");
          check(((ProgressComponentView) ((ViewGroup) content).getChildAt(0)).attached,
            "Resizing must preserve the loading animation lifecycle");
        }
      }
      // Preserve a partially expanded sheet across a keyboard dismissal.
      sheet.scroll = 90;
      sheet.resize(1000);
      check(sheet.headerY == 530 && content.top == 530, "Preserve the sheet's scroll offset");
      sheet.resize(700);

      // A fully expanded sheet may have both offset rows in the RecyclerView cache.
      sheet.stickersController.recyclerView.children.clear();
      sheet.stickersController.manager.positions.clear();
      StickerSmallView laterSticker = new StickerSmallView();
      add(sheet, 8, laterSticker);
      sheet.stickersController.manager.first = 8;
      sheet.resize(700);
      check(sheet.headerY == 0, "Expanded header stays pinned without a scroll callback");
      check(!padding.layoutRequested, "The spacer is cached while offscreen");
      sheet.resize(1000);
      check(sheet.headerY == 0, "Expanded header remains pinned when the viewport grows");

      // Reattaching cached rows must measure them again with the new viewport height.
      sheet.stickersController.recyclerView.children.clear();
      sheet.stickersController.manager.positions.clear();
      sheet.stickersController.manager.first = 0;
      sheet.scroll = 0;
      add(sheet, 0, padding);
      add(sheet, 1, content);
      sheet.resize(1000);
      check(padding.height == 620 && sheet.headerY == 620 && content.top == 620,
        "Recycled offset rows must not retain the old keyboard height");
    }
    StickersListController uncreated = new StickersListController();
    uncreated.onViewportChanged();
  }
}
"""
        replacements = {
            "__WRAP__": wrap_methods,
            "__HEIGHT_FIELD__": height_field,
            "__CONTROLLER__": controller_methods,
            "__ATTACH__": java_method(adapter, "public void onViewAttachedToWindow ("),
            "__PADDING_MEASURE__": java_method(padding, "protected void onMeasure ("),
            "__PROGRESS_MEASURE__": java_method(progress, "protected void onMeasure ("),
        }
        for marker, production in replacements.items():
            harness = harness.replace(marker, production)
        run_java(harness)


if __name__ == "__main__":
    unittest.main()
