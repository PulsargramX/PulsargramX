#!/usr/bin/env python3
"""Check channel menu callbacks with delayed language and reaction details."""

import shutil
import unittest

from ime_animation_behavior_test import run_java
from message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class MessageMenuLoadingBehaviorTest(unittest.TestCase):
    def test_menu_opens_before_optional_details(self) -> None:
        source = read("app/src/main/java/org/thunderdog/challegram/data/TGMessage.java")
        methods = "\n".join(java_method(source, signature) for signature in (
            "public final void loadAvailableReactions (Runnable after)",
            "public final void loadAvailableReactions (Runnable after,",
            "public void checkTranslatableText (Runnable after)",
            "public void checkTranslatableText (Runnable after,",
        )).replace("@Nullable ", "")
        harness = r"""
import java.util.*;
import java.util.function.BiConsumer;
public class ImeAnimationTest {
  interface RunnableData<T> { void runWithData(T value); }
  interface RunnableBool { void runWithBool(boolean value); }
  static class TdApi {
    static class Message { long chatId = 1; }
    static class AvailableReactions {}
    static class FormattedText {
      final String text;
      FormattedText(String text) { this.text = text; }
    }
    static class GetMessageAvailableReactions {
      GetMessageAvailableReactions(long chatId, long id, int limit) {}
    }
  }
  static class Tdlib {
    BiConsumer<TdApi.AvailableReactions, Object> reply;
    RunnableBool details;
    void send(TdApi.GetMessageAvailableReactions query,
              BiConsumer<TdApi.AvailableReactions, Object> reply) { this.reply = reply; }
    void ensureReactionsAvailable(TdApi.AvailableReactions list, RunnableBool after) {
      details = after;
    }
  }
  static class Translations {
    final Map<String, String> languages = new HashMap<>();
    String getCachedTextLanguage(String text) { return languages.get(text); }
    void saveCachedTextLanguage(String text, String language) { languages.put(text, language); }
  }
  static class Background {
    static final Background instance = new Background();
    final List<Runnable> tasks = new ArrayList<>();
    static Background instance() { return instance; }
    void post(Runnable task) { tasks.add(task); }
    void flush() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
  }
  static class LanguageDetector {
    static RunnableData<String> success;
    static RunnableData<Throwable> failure;
    static void detectLanguage(Object context, String text, RunnableData<String> success,
                               RunnableData<Throwable> failure) {
      LanguageDetector.success = success;
      LanguageDetector.failure = failure;
    }
  }
  static class Settings { static final int TRANSLATE_MODE_NONE = 0; }
  final TdApi.Message msg = new TdApi.Message();
  final Tdlib tdlib = new Tdlib();
  final Translations mTranslationsManager = new Translations();
  TdApi.AvailableReactions messageAvailableReactions;
  TdApi.FormattedText input = new TdApi.FormattedText("post");
  TdApi.FormattedText textToTranslate;
  String textToTranslateOriginalLanguage;
  boolean pending;
  int quickButtonUpdates;
  final List<Runnable> ui = new ArrayList<>();
  boolean isPendingMessage() { return pending; }
  long getSmallestId() { return 1; }
  void computeQuickButtons() { quickButtonUpdates++; }
  Object context() { return null; }
  int translationStyleMode() { return 1; }
  TdApi.FormattedText getTextToTranslateImpl() { return input; }
  void runOnUiThreadOptional(Runnable action) { ui.add(action); }
  void flush() { while (!ui.isEmpty()) ui.remove(0).run(); }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    ImeAnimationTest message = new ImeAnimationTest();
    List<String> events = new ArrayList<>();
    message.loadAvailableReactions(() -> events.add("open"), () -> events.add("details"));
    message.flush();
    check(events.isEmpty(), "Allowed reaction types must arrive before opening");
    TdApi.AvailableReactions list = new TdApi.AvailableReactions();
    message.tdlib.reply.accept(list, null);
    message.flush();
    check(events.equals(List.of("open")), "Open while reaction stickers are still pending");
    check(message.messageAvailableReactions == list, "Publish types before creating the panel");
    message.tdlib.details.runWithBool(true);
    message.flush();
    check(events.equals(List.of("open", "details")), "Refresh details without reopening the panel");

    events.clear();
    message.loadAvailableReactions(() -> events.add("legacy"));
    message.tdlib.reply.accept(list, null);
    message.flush();
    check(events.isEmpty(), "Existing callers still wait for reaction details");
    message.tdlib.details.runWithBool(false);
    message.flush();
    check(events.equals(List.of("legacy")), "Existing callers run once when details are ready");
    events.clear();
    message.loadAvailableReactions(() -> events.add("error"), () -> events.add("details"));
    message.tdlib.reply.accept(null, new Object());
    message.flush();
    check(events.equals(List.of("error")), "A failed list query must release menu opening");

    events.clear();
    message.checkTranslatableText(() -> events.add("open"), () -> events.add("language"));
    check(events.equals(List.of("open")), "Unknown language must not delay channel menus");
    check(LanguageDetector.success == null, "Initialize language detection off the menu's thread");
    Background.instance().flush();
    LanguageDetector.success.runWithData("en");
    check(events.equals(List.of("open")), "Language updates are dispatched to the UI");
    message.flush();
    check(events.equals(List.of("open", "language")), "Language results update the existing menu");
    check("en".equals(message.textToTranslateOriginalLanguage), "Keep the detected language");
    events.clear();
    message.checkTranslatableText(() -> events.add("cached"), () -> events.add("language"));
    check(events.equals(List.of("cached")), "Cached languages need no asynchronous refresh");

    message = new ImeAnimationTest();
    events.clear();
    message.checkTranslatableText(() -> events.add("legacy"));
    check(events.isEmpty(), "Existing translation callers still wait for detection");
    LanguageDetector.failure.runWithData(new IllegalStateException());
    message.flush();
    check(events.equals(List.of("legacy")), "Detection failure must release existing callers");

    message = new ImeAnimationTest();
    events.clear();
    message.checkTranslatableText(() -> events.add("open"), () -> events.add("stale"));
    Background.instance().flush();
    message.textToTranslate = new TdApi.FormattedText("edited post");
    LanguageDetector.success.runWithData("en");
    message.flush();
    check(events.equals(List.of("open")), "An old result must not change an edited post's menu");
  }
"""
        run_java(harness + methods + "\n}\n")

    def test_loading_reactions_preserves_other_picker_items(self) -> None:
        source = read("app/src/main/java/org/thunderdog/challegram/ui/ReactionsPickerController.java")
        method = java_method(source, "public void updateReactionDetails ()")
        harness = r"""
import java.util.*;
public class ImeAnimationTest {
  static class TdApi { static class ReactionType {} }
  static class TGReaction {}
  static class MediaStickersAdapter {
    static class StickerHolder { static final int TYPE_STICKER = 1; }
    static class StickerItem {
      final Object sticker;
      StickerItem(int type, Object sticker) { this.sticker = sticker; }
    }
    final List<StickerItem> items = new ArrayList<>();
    List<StickerItem> getItems() { return items; }
    int getItemCount() { return items.size(); }
    StickerItem getItem(int index) { return items.get(index); }
    void replaceItem(int index, StickerItem item) { items.set(index, item); }
  }
  static class Tdlib {
    final Map<TdApi.ReactionType, TGReaction> reactions = new HashMap<>();
    TGReaction getReaction(TdApi.ReactionType type) { return reactions.get(type); }
  }
  final Tdlib tdlib = new Tdlib();
  final MediaStickersAdapter adapter = new MediaStickersAdapter();
  List<MediaStickersAdapter.StickerItem> emojiItemsSaved;
  final Map<MediaStickersAdapter.StickerItem, TdApi.ReactionType> pendingReactions = new HashMap<>();
  Object newReactionSticker(TGReaction reaction) { return reaction; }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    ImeAnimationTest picker = new ImeAnimationTest();
    MediaStickersAdapter.StickerItem loading = new MediaStickersAdapter.StickerItem(1, null);
    MediaStickersAdapter.StickerItem installed = new MediaStickersAdapter.StickerItem(1, new Object());
    MediaStickersAdapter.StickerItem header = new MediaStickersAdapter.StickerItem(2, new Object());
    picker.adapter.items.addAll(List.of(header, loading, installed));
    TdApi.ReactionType type = new TdApi.ReactionType();
    TGReaction reaction = new TGReaction();
    picker.pendingReactions.put(loading, type);
    picker.tdlib.reactions.put(type, reaction);
    picker.updateReactionDetails();
    check(picker.adapter.getItem(1).sticker == reaction, "Replace the pending reaction in place");
    check(picker.adapter.getItem(0) == header, "Keep section headers in place");
    check(picker.adapter.getItem(2) == installed, "Keep installed emoji loaded in the meantime");
    check(picker.adapter.getItemCount() == 3, "Keep picker geometry stable");
    Object loaded = picker.adapter.getItem(1);
    picker.updateReactionDetails();
    check(picker.adapter.getItem(1) == loaded, "Repeated updates must not restart sticker animations");
    MediaStickersAdapter.StickerItem saved = new MediaStickersAdapter.StickerItem(1, null);
    picker.emojiItemsSaved = new ArrayList<>(List.of(header, saved, installed));
    picker.pendingReactions.put(saved, type);
    picker.updateReactionDetails();
    check(picker.emojiItemsSaved.get(1).sticker == reaction,
      "Update reactions saved while emoji search is open");
    check(picker.adapter.getItem(1) == loaded, "Loading details must preserve active search results");
  }
"""
        run_java(harness + method + "\n}\n")

    def test_language_result_updates_only_its_menu(self) -> None:
        source = read("app/src/main/java/org/thunderdog/challegram/ui/MessageOptionsPagerController.java")
        method = java_method(source, "public void updateTranslationOption (")
        harness = r"""
import java.util.*;
public class ImeAnimationTest {
  static class R { static class id { static final int btn_chatTranslate = 1; } }
  static class Settings { static final int TRANSLATE_MODE_NONE = 0; }
  static class TGMessage {
    boolean translatable;
    boolean isTranslatable() { return translatable; }
    int translationStyleMode() { return 1; }
  }
  static class OptionItem {
    final int id;
    OptionItem(int id) { this.id = id; }
  }
  static class Options {
    final Object info, title, subtitle;
    final OptionItem[] items;
    final int maxLineCount;
    Options(Object info, Object title, Object subtitle, OptionItem[] items, int lines) {
      this.info = info; this.title = title; this.subtitle = subtitle;
      this.items = items; this.maxLineCount = lines;
    }
  }
  static class State {
    final TGMessage message = new TGMessage();
    boolean needShowMessageOptions = true;
    Options options = new Options("views", "title", "read", new OptionItem[] {
      new OptionItem(2), new OptionItem(1), new OptionItem(3)
    }, 5);
  }
  static class MessageOptionsController {
    Options options;
    void updateOptions(Options options) { this.options = options; }
  }
  static class View { void requestLayout() {} }
  final State state = new State();
  final MessageOptionsController controller = new MessageOptionsController();
  final View wrapView = new View();
  int currentMediaPosition;
  float currentPositionOffset;
  int layouts;
  MessageOptionsController findOptionsController() { return controller; }
  void invalidateAllItemDecorations() { layouts++; }
  void onPageScrolled(int position, float offset, int pixels) {}
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    ImeAnimationTest menu = new ImeAnimationTest();
    Options original = menu.state.options;
    menu.updateTranslationOption(new TGMessage());
    check(menu.state.options == original, "Late results must not alter another post's menu");
    menu.state.message.translatable = true;
    menu.updateTranslationOption(menu.state.message);
    check(menu.state.options == original, "Keep Translate for allowed languages");
    menu.state.message.translatable = false;
    menu.updateTranslationOption(menu.state.message);
    Options updated = menu.state.options;
    check(updated.items.length == 2 && updated.items[0].id == 2 && updated.items[1].id == 3,
      "Remove only Translate for excluded languages");
    check(updated.info == original.info && updated.subtitle == original.subtitle,
      "Preserve views and read metadata");
    check(menu.controller.options == updated, "Update the visible options list");
    check(menu.layouts == 1, "Recompute panel geometry after changing the row count");
    menu.updateTranslationOption(menu.state.message);
    check(menu.layouts == 1, "Repeated callbacks must not restart layout changes");
  }
"""
        run_java(harness + method + "\n}\n")


if __name__ == "__main__":
    unittest.main()
