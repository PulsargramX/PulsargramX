"""Exercise folder picker navigation and filters without an Android build."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
UI = "app/src/main/java/org/thunderdog/challegram/ui/"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ChatPickerBehaviorTest(unittest.TestCase):
  def test_folder_order_updates_and_destination_filtering(self):
    source = read(UI + "ChatPickerController.java")
    methods = "\n".join(java_method(source, signature) for signature in (
      "public void setArguments (", "private void updateFolders (",
      "public CharSequence getName ()", "protected int getPagerItemCount ()",
      "protected long getPagerItemId (", "protected CharSequence[] getPagerSections ()",
      "protected List<ViewPagerTopView.Item> getPagerSectionItems ()",
      "protected ViewController<?> onCreatePagerItemForPosition (",
      "protected boolean filterChatSearchResult (", "protected boolean onFoundChatClick (",
      "public void onChatFoldersChanged (",
      "private void updateCounters ()", "private void onCounterChanged (",
      "public void onChatCounterChanged (", "public void onMessageCounterChanged (",
      "public void onBadgeFlagsChanged (", "public void onBadgeSettingsChanged ()",
      "public void onChatFolderStyleChanged (int style)",
      "public void onChatListStateChanged (",
    ))
    tab = java_method(read(UI + "ChatFolderTab.java"), "final class ChatFolderTab").replace(
      "final class ChatFolderTab", "static final class ChatFolderTab", 1
    )
    update_counter = java_method(read(UI + "MainController.java"), "public static void updateCounter (")
    harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.ChatFolderStyle;
public class ChatPickerTest {
  interface Filter {boolean accept(TdApi.Chat chat);}
  static class Context {}
  static class View {}
  static class TGFoundChat {}
  static class ViewController<T> {}
  interface FutureBool {boolean getBoolValue();}
  static class ResourcesCompat {static final int ID_NULL = 0;}
  static class TextUtils {
    static boolean isEmpty(CharSequence value) {return value == null || value.length() == 0;}
  }
  static class Screen {static int dp(float value) {return Math.round(value);}}
  static class BitwiseUtils {
    static boolean hasFlag(int flags, int flag) {return (flags & flag) == flag;}
  }
  static class Settings {
    static final int BADGE_FLAG_MESSAGES = 1, BADGE_FLAG_MUTED = 2, BADGE_FLAG_ARCHIVED = 4;
    static final Settings INSTANCE = new Settings();
    boolean reduceMotion;
    static Settings instance() {return INSTANCE;}
    boolean needReduceMotion() {return reduceMotion;}
  }
  static class AccountSettings {
    int style = ChatFolderStyle.LABEL_ONLY, flags;
    Set<Integer> hidden = new HashSet<>();
    int chatFolderStyle() {return style;}
    int getChatFolderBadgeFlags() {return flags;}
    boolean isChatFolderEnabled(int id) {return !hidden.contains(id);}
  }
  static class Counter {
    interface Callback {void onCounterAppearanceChanged(Counter counter, boolean sizeChanged);}
    int count, updates;
    float textSize, padding;
    boolean muted, outline, animated;
    Callback callback;
    Object colors;
    boolean isMuted() {return muted;}
    void setCount(int count, boolean muted, boolean animated) {
      this.count = count; this.muted = muted; this.animated = animated; updates++;
    }
    static class Builder {
      final Counter value = new Counter();
      Builder textSize(float size) {value.textSize = size; return this;}
      Builder backgroundPadding(float padding) {value.padding = padding; return this;}
      Builder outlineAffectsBackgroundSize(boolean enabled) {value.outline = enabled; return this;}
      Builder colorSet(Object colors) {value.colors = colors; return this;}
      Builder callback(Callback callback) {value.callback = callback; return this;}
      Counter build() {return value;}
    }
  }
  static class MainController {
    static class UnreadCounterColorSet {
      Counter counter;
      final FutureBool bottom;
      UnreadCounterColorSet(FutureBool bottom) {this.bottom = bottom;}
      void setCounter(Counter counter) {this.counter = counter;}
    }
    UPDATE_COUNTER
  }
  static class ViewPagerTopView {
    static final int COMPACT_ITEM_PADDING = 7, DEFAULT_ITEM_PADDING = 19;
    static final int COMPACT_ITEM_SPACING = 11, DEFAULT_ITEM_SPACING = 23;
    static final int SLIDE_OFF_DIRECTION_TOP = 1, SLIDE_OFF_DIRECTION_BOTTOM = 2;
    boolean animate, activeLabelOnly, dark, selectionAtTop;
    int direction, padding, spacing, layoutPosition = -1, invalidations;
    void setAnimateItemChanges(boolean value) {animate = value;}
    void setShowLabelOnActiveOnly(boolean value) {activeLabelOnly = value;}
    void setUseDarkBackground(boolean value) {dark = value;}
    void setDrawSelectionAtTop(boolean value) {selectionAtTop = value;}
    void setSlideOffDirection(int value) {direction = value;}
    void setItemPadding(int value) {padding = value;}
    void setItemSpacing(int value) {spacing = value;}
    void requestItemLayoutAt(int position) {layoutPosition = position;}
    void invalidate() {invalidations++;}
    static class Item {
      final CharSequence string;
      final int iconRes;
      final Counter counter;
      int minWidth;
      Item(CharSequence label, int icon, Counter counter) {
        string = label; iconRes = icon; this.counter = counter;
      }
      void setMinWidth(int value) {minWidth = value;}
    }
  }
  static class ViewPagerHeaderViewCompact {
    final ViewPagerTopView top = new ViewPagerTopView();
    float fading;
    ViewPagerTopView getTopView() {return top;}
    void setFadingEdgeLength(float value) {fading = value;}
  }
  TAB_CLASS
  static class TdlibCounter {
    int chatCount, chatUnmutedCount, messageCount, messageUnmutedCount;
  }
  static class ChatList {boolean hasUnreadMentions() {return false;}}
  static class ChatsController extends ViewController<Object> {
    interface PickerDelegate {int getTitleStringRes();}
    static class Arguments {
      final Filter filter;
      final PickerDelegate pickerDelegate;
      TdApi.ChatList chatList;
      Arguments(Filter filter, PickerDelegate delegate) {
        this.filter = filter; pickerDelegate = delegate;
      }
      Arguments setChatList(TdApi.ChatList list) {chatList = list; return this;}
    }
    Arguments args;
    static ChatsController clicked;
    ChatsController(Context context, Tdlib tdlib) {}
    void setArguments(Arguments args) {this.args = args;}
    boolean onFoundChatClick(View view, TGFoundChat chat) {clicked = this; return true;}
  }
  static class Tdlib {
    TdApi.ChatFolderInfo[] folders = new TdApi.ChatFolderInfo[0];
    int mainPosition;
    final AccountSettings settings = new AccountSettings();
    final Map<Long, TdlibCounter> counters = new HashMap<>();
    TdApi.ChatFolderInfo[] chatFolders() {return folders;}
    int mainChatListPosition() {return mainPosition;}
    AccountSettings settings() {return settings;}
    TdlibCounter getCounter(TdApi.ChatList list) {
      return counters.computeIfAbsent(Td.key(list), key -> new TdlibCounter());
    }
    ChatList chatList(TdApi.ChatList list) {return new ChatList();}
  }
  static class Td {
    static long key(TdApi.ChatList list) {
      return list instanceof TdApi.ChatListFolder ? ((TdApi.ChatListFolder) list).chatFolderId :
        list instanceof TdApi.ChatListArchive ? -1 : 0;
    }
    static boolean equalsTo(TdApi.ChatList a, TdApi.ChatList b) {return key(a) == key(b);}
  }
  static class ChatPosition {
    static final TdApi.ChatListMain CHAT_LIST_MAIN = new TdApi.ChatListMain();
    static final TdApi.ChatListArchive CHAT_LIST_ARCHIVE = new TdApi.ChatListArchive();
    static final Map<Long, Set<Integer>> memberships = new HashMap<>();
    static Object findPosition(TdApi.Chat chat, TdApi.ChatList list) {
      return memberships.getOrDefault(chat.id, Collections.emptySet())
        .contains(((TdApi.ChatListFolder) list).chatFolderId) ? new Object() : null;
    }
  }
  static class TD {
    static CharSequence toCharSequence(TdApi.ChatFolderName name) {return name.text.text;}
    static boolean isChatListMain(TdApi.ChatList list) {return list instanceof TdApi.ChatListMain;}
    static int findFolderIcon(TdApi.ChatFolderIcon icon, int fallback) {
      return icon == null ? fallback : 10;
    }
  }
  static class Emoji {
    static Emoji instance() {return new Emoji();}
    CharSequence replaceEmoji(CharSequence value) {return value;}
  }
  static class R {
    static class string {static final int CategoryMain = 1;}
    static class drawable {static final int baseline_folder_24 = 1, baseline_forum_24 = 2;}
  }
  static class Lang {
    static String getString(int resource) {
      return resource == 1 ? "All Chats" : resource == 2 ? "Quote to…" : "Add to group";
    }
  }
  static class PagerBase {
    ChatsController.Arguments args;
    void setArguments(ChatsController.Arguments args) {this.args = args;}
  }
  static class Picker extends PagerBase {
    final Tdlib tdlib;
    final List<TdApi.ChatList> chatLists = new ArrayList<>();
    List<ViewPagerTopView.Item> sections = new ArrayList<>();
    ViewPagerHeaderViewCompact headerCell = new ViewPagerHeaderViewCompact();
    static final int NO_POSITION = -1;
    int current;
    Picker(Tdlib tdlib, ChatsController.Arguments args) {this.tdlib = tdlib; setArguments(args);}
    ChatsController.Arguments getArgumentsStrict() {return args;}
    int getCurrentPagerItemPosition() {return current;}
    long getCurrentPagerItemId() {return getPagerItemId(current);}
    int getPagerItemPosition(long id) {
      for (int i = 0; i < getPagerItemCount(); i++) if (getPagerItemId(i) == id) return i;
      return NO_POSITION;
    }
    void setCurrentPagerPosition(int position, boolean animated) {current = position;}
    void notifyPagerItemPositionsChanged() {}
    void configureTabs() {
      ChatFolderTab.configure(headerCell, tdlib.settings().chatFolderStyle(), true, false);
    }
    boolean isFocused() {return true;}
    void runOnUiThreadOptional(Runnable action) {action.run();}
    ViewController<?> getPreparedControllerForPosition(int position) {
      return onCreatePagerItemForPosition(new Context(), position);
    }
    METHODS
  }
  static TdApi.ChatFolderInfo folder(int id, String name) {
    TdApi.ChatFolderInfo folder = new TdApi.ChatFolderInfo(); folder.id = id;
    folder.name = new TdApi.ChatFolderName(new TdApi.FormattedText(name, null), false);
    folder.icon = new TdApi.ChatFolderIcon("Custom");
    return folder;
  }
  static void check(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    Tdlib tdlib = new Tdlib();
    tdlib.folders = new TdApi.ChatFolderInfo[]{folder(10, "Friends"), folder(20, "Work")};
    tdlib.mainPosition = 1;
    Filter filter = chat -> chat.id == 100;
    ChatsController.PickerDelegate quote = () -> 2;
    Picker picker = new Picker(tdlib, new ChatsController.Arguments(filter, quote));
    check(Arrays.equals(picker.sections.stream().map(item -> item.string).toArray(),
                       new String[]{"Friends", "All Chats", "Work"}),
          "Show configured folders in their configured order");
    List<ViewPagerTopView.Item> originalSections = picker.getPagerSectionItems();
    ChatsController work = (ChatsController) picker.onCreatePagerItemForPosition(new Context(), 2);
    check(work.args.filter == filter && work.args.pickerDelegate == quote &&
          ((TdApi.ChatListFolder) work.args.chatList).chatFolderId == 20,
          "Preserve the selection delegate and eligibility filter on every folder page");
    TdApi.Chat allowed = new TdApi.Chat(); allowed.id = 100;
    TdApi.Chat forbidden = new TdApi.Chat(); forbidden.id = 200;
    ChatPosition.memberships.put(allowed.id, new HashSet<>(Arrays.asList(10)));
    picker.current = 0;
    check(picker.filterChatSearchResult(allowed) && !picker.filterChatSearchResult(forbidden),
          "Apply destination permissions while searching a folder");
    picker.current = 2;
    check(!picker.filterChatSearchResult(allowed), "Restrict folder search to the chosen folder");
    picker.current = 1;
    check(picker.filterChatSearchResult(allowed) && !picker.filterChatSearchResult(forbidden) &&
          !picker.filterChatSearchResult(null), "Search across chats from All Chats with the same filter");
    picker.current = 2;
    picker.onFoundChatClick(new View(), new TGFoundChat());
    check(ChatsController.clicked.args.pickerDelegate == quote &&
          ((TdApi.ChatListFolder) ChatsController.clicked.args.chatList).chatFolderId == 20,
          "Select search results through the active folder's picker delegate");
    picker.onChatFoldersChanged(new TdApi.ChatFolderInfo[]{folder(20, "Office"), folder(10, "Friends")}, 2);
    check(picker.getCurrentPagerItemId() == 20 && picker.current == 0 &&
          picker.sections.get(0).string.toString().equals("Office"),
          "Keep the selected folder when folders are renamed and reordered");
    check(originalSections != picker.getPagerSectionItems() &&
          originalSections.get(0).string.toString().equals("Friends"),
          "Replace the item list so the tab view detects renames, reorders and style changes");
    check(picker.getName().toString().equals("Quote to…"), "Keep the operation title across folder changes");
    picker.onChatFoldersChanged(new TdApi.ChatFolderInfo[]{folder(10, "Friends")}, 1);
    check(picker.getCurrentPagerItemId() == 0 && picker.current == 1,
          "Return to All Chats if the selected folder is removed");
    picker.onChatFoldersChanged(new TdApi.ChatFolderInfo[0], 99);
    check(picker.getPagerItemCount() == 1 && picker.getCurrentPagerItemId() == 0,
          "Keep an All Chats destination when there are no folders");
    Picker groups = new Picker(tdlib, new ChatsController.Arguments(filter, () -> 3));
    check(groups.getName().toString().equals("Add to group") && !groups.filterChatSearchResult(forbidden),
          "Use the same folder picker for Add to group with its own title and group filter");

    tdlib.mainPosition = 0;
    TdlibCounter friends = tdlib.getCounter(new TdApi.ChatListFolder(10));
    friends.chatCount = 8; friends.chatUnmutedCount = 3;
    friends.messageCount = 90; friends.messageUnmutedCount = 45;
    for (int style = 0; style <= 3; style++) {
      tdlib.settings.style = style;
      picker.onChatFolderStyleChanged(style);
      ViewPagerTopView.Item main = picker.sections.get(0), item = picker.sections.get(1);
      check(main.iconRes == R.drawable.baseline_forum_24 && main.string == null,
            "Use the main chat list icon for the first tab in every style");
      check((item.string != null) == (style != ChatFolderStyle.ICON_ONLY) &&
            (item.iconRes != ResourcesCompat.ID_NULL) == (style != ChatFolderStyle.LABEL_ONLY),
            "Honor the same folder icon and label visibility as the main chat list");
      check(item.iconRes == ResourcesCompat.ID_NULL || item.iconRes == 10,
            "Use the configured folder icon instead of its emoji name");
      check(item.minWidth == 56 - ViewPagerTopView.COMPACT_ITEM_PADDING * 2 &&
            picker.headerCell.top.padding == ViewPagerTopView.COMPACT_ITEM_PADDING &&
            picker.headerCell.top.spacing == ViewPagerTopView.COMPACT_ITEM_SPACING,
            "Use the same minimum width, padding and spacing as the main folder strip");
      check(picker.headerCell.top.activeLabelOnly ==
            (style == ChatFolderStyle.ICON_WITH_LABEL_ON_ACTIVE_FOLDER),
            "Honor the preference to show a label only on the active folder");
      Counter badge = item.counter;
      check(badge.count == 3 && badge.textSize == 12f && badge.padding == 4f && !badge.outline,
            "Include unread badges with the main folder strip's sizing");
      MainController.UnreadCounterColorSet colors = (MainController.UnreadCounterColorSet) badge.colors;
      check(colors.counter == badge && !colors.bottom.getBoolValue(),
            "Use the main folder strip's linked counter color set");
    }
    Counter badge = picker.sections.get(1).counter;
    friends.chatUnmutedCount = 5;
    picker.onChatCounterChanged(new TdApi.ChatListFolder(10), friends, false, 8, 8, 5);
    check(badge.count == 5 && badge.animated, "Refresh unread chat counts while the picker is open");
    tdlib.settings.flags = Settings.BADGE_FLAG_MESSAGES | Settings.BADGE_FLAG_MUTED;
    picker.onBadgeSettingsChanged();
    check(badge.count == 90 && !badge.muted, "Apply global message and muted badge preferences");
    friends.messageCount = 99; friends.messageUnmutedCount = 0;
    int updates = badge.updates;
    picker.onChatCounterChanged(new TdApi.ChatListFolder(10), friends, false, 8, 8, 5);
    check(badge.updates == updates, "Ignore chat count notifications when counting messages");
    picker.onMessageCounterChanged(new TdApi.ChatListFolder(10), friends, 99, 0);
    check(badge.count == 99 && badge.muted, "Refresh message counts and muted badge appearance");
    tdlib.settings.flags = Settings.BADGE_FLAG_ARCHIVED | Settings.BADGE_FLAG_MUTED;
    tdlib.getCounter(ChatPosition.CHAT_LIST_MAIN).chatCount = 4;
    TdlibCounter archive = tdlib.getCounter(ChatPosition.CHAT_LIST_ARCHIVE);
    archive.chatCount = 2;
    picker.onBadgeFlagsChanged(tdlib, tdlib.settings.flags);
    check(picker.sections.get(0).counter.count == 6, "Include archived chats when configured");
    archive.chatCount = 7;
    picker.onChatCounterChanged(ChatPosition.CHAT_LIST_ARCHIVE, archive, false, 7, 7, 0);
    check(picker.sections.get(0).counter.count == 11,
          "Refresh the main badge when included archive counts change");
    badge.callback.onCounterAppearanceChanged(badge, true);
    check(picker.headerCell.top.layoutPosition == 1 && picker.headerCell.top.invalidations > 0,
          "Remeasure the tab when the badge width changes");
    tdlib.settings.hidden.add(10);
    picker.current = 1;
    picker.onChatListStateChanged(tdlib, new TdApi.ChatListFolder(10), false);
    check(picker.chatLists.size() == 2 && picker.getCurrentPagerItemId() == 0,
          "Respect hidden folders and return to main if the selected folder is hidden");
    Settings.instance().reduceMotion = true;
    picker.configureTabs();
    check(!picker.headerCell.top.animate && !picker.headerCell.top.dark &&
          !picker.headerCell.top.selectionAtTop && picker.headerCell.fading == 16f,
          "Honor reduced motion and the main folder strip's appearance at the top");
  }
}
'''.replace("METHODS", methods).replace("TAB_CLASS", tab).replace("UPDATE_COUNTER", update_counter)
    harness = harness.replace("@Nullable ", "").replace("@NonNull ", "")
    with tempfile.TemporaryDirectory() as directory:
      work = Path(directory)
      annotations = []
      for name, body in (
        ("Nullable", "public @interface Nullable {}"),
        ("IntDef", "public @interface IntDef {long[] value() default {}; "
         "boolean flag() default false;}"),
      ):
        path = work / "androidx/annotation" / (name + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package androidx.annotation; " + body)
        annotations.append(path)
      test = work / "ChatPickerTest.java"
      test.write_text(harness)
      subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(work), *map(str, annotations), str(test),
         str(ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"),
         str(ROOT / "app/src/main/java/org/thunderdog/challegram/telegram/ChatFolderStyle.java")],
        check=True
      )
      subprocess.run(["java", "-cp", str(work), "ChatPickerTest"], check=True)

  def test_add_to_group_and_quote_use_folder_picker(self):
    source = read("app/src/main/java/org/thunderdog/challegram/telegram/TdlibUi.java")
    for signature in (
      "private void addToGroup (final TdlibDelegate context, long userId)",
      "public void addToGroup (TdlibDelegate context, final TGBotStart start, boolean isGame)",
    ):
      self.assertIn("new ChatPickerController(", java_method(source, signature))
    self.assertIn("new ChatPickerController(", java_method(
      read(UI + "MessagesController.java"), "public void quoteMessage ("
    ))
    picker = read(UI + "ChatPickerController.java")
    self.assertIn("TITLE_STYLE_COMPACT_BIG", java_method(picker, "protected int getTitleStyle ()"))
    self.assertIn("addSearchButton", java_method(picker, "public void fillMenuItems ("))
    self.assertIn("!isPicker()", java_method(
      read(UI + "ChatsController.java"), "public void onChatCounterChanged ("
    ))
    main = read(UI + "MainController.java")
    for shared in ("ChatFolderTab.build(", "ChatFolderTab.buildCounter(", "ChatFolderTab.configure("):
      self.assertIn(shared, picker)
      self.assertIn(shared, main)
    create = java_method(picker, "protected void onCreateView (Context context,")
    destroy = java_method(picker, "public void destroy ()")
    for subscribe, unsubscribe in (
      ("subscribeToChatFoldersUpdates(this)", "unsubscribeFromChatFoldersUpdates(this)"),
      ("addTotalChatCounterListener(this)", "removeTotalChatCounterListener(this)"),
      ("addCountersListener(this)", "removeCountersListener(this)"),
      ("addChatListPositionListener(this)", "removeChatListPositionListener(this)"),
      ("addChatFolderSettingsListener(this)", "removeChatFolderSettingsListener(this)"),
    ):
      self.assertIn(subscribe, create)
      self.assertIn(unsubscribe, destroy)


if __name__ == "__main__":
  unittest.main()
