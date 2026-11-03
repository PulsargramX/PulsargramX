"""Exercise the share sheet's folder navigation without assembling an APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ShareFolderBehaviorTest(unittest.TestCase):
  def test_folder_updates_navigation_and_recipient_selection(self):
    source = read("app/src/main/java/org/thunderdog/challegram/ui/ShareController.java")
    methods = "\n".join(java_method(source, signature) for signature in (
      "private int getFolderTabsHeight ()", "private void rebuildFolderTabs (",
      "private void updateFolderSelection ()", "private void prepareFolderPaging ()",
      "private void refreshChatPage (", "private ShareChatPage getOrCreateChatPage (",
      "private void activateChatPage (", "private void ensureChatPageLoaded (",
      "private void displayChatList (", "private boolean isDisplayingChatList (",
      "private final class SharePagerAdapter",
    ))
    harness = r'''
import java.util.*;
public class ShareFolderTest {
  @interface Nullable {}
  @interface NonNull {}
  static class TdApi {
    static class ChatList {int id; ChatList(int id) {this.id=id;}}
    static class ChatListArchive extends ChatList {ChatListArchive() {super(-1);}}
    static class ChatListFolder extends ChatList {ChatListFolder(int id) {super(id);}}
    static class ChatFolderInfo {
      int id; String name, icon;
      ChatFolderInfo(int id, String name) {this.id=id; this.name=name;}
    }
  }
  static class ChatPosition {static final TdApi.ChatList CHAT_LIST_MAIN = new TdApi.ChatList(0);}
  static class Td {static boolean equalsTo(TdApi.ChatList a,TdApi.ChatList b) {return a.id==b.id;}}
  static class Settings {
    static Settings instance = new Settings(); boolean enabled = true;
    static Settings instance() {return instance;}
    boolean chatFoldersEnabled() {return enabled;}
  }
  static class AccountSettings {
    Set<Integer> hidden = new HashSet<>(); int style;
    int chatFolderStyle() {return style;}
    boolean isChatFolderEnabled(int id) {return !hidden.contains(id);}
  }
  static class Tdlib {
    AccountSettings settings = new AccountSettings();
    AccountSettings settings() {return settings;}
  }
  static class Emoji {
    static Emoji instance() {return new Emoji();}
    CharSequence replaceEmoji(CharSequence text) {return text;}
  }
  static class TD {
    static String makeChatListKey(TdApi.ChatList list) {return "list"+list.id;}
    static CharSequence toCharSequence(String text) {return text;}
    static int findFolderIcon(String icon,int fallback) {return fallback;}
  }
  static class Lang {
    static String getString(int id) {return "All chats";}
    static String getMarkdownString(Object parent,int id) {return "No chats available";}
  }
  static class R {
    static class string {static int CategoryMain=1, CategoryArchive=2, ShareFolderNoChats=3;}
    static class drawable {static int baseline_folder_24=2, baseline_forum_24=3, baseline_archive_24=4, baseline_folder_open_96=5;}
  }
  interface FutureBool {boolean get();}
  interface Callback {void changed(Object counter,boolean sizeChanged);}
  static class Counter {}
  static class ChatFolderTab {
    static boolean hasLabel(int style,boolean firstMain) {return style!=1 && !firstMain;}
    static Counter buildCounter(FutureBool f,Callback c) {return new Counter();}
    static ViewPagerTopView.Item build(CharSequence label,int icon,int style,boolean main,Counter c) {
      return new ViewPagerTopView.Item(label);
    }
    static void configure(FolderTabs tabs,int style,boolean folders,boolean bottom) {}
  }
  static class ViewPagerTopView {
    static class Item {CharSequence label; Item(CharSequence label) {this.label=label;}}
    List<Item> items = new ArrayList<>(); int selected, rebuilds;
    void setItems(List<Item> next) {
      if (!items.equals(next)) {rebuilds++;} items=next;
    }
    void setSelectionFactor(int position) {selected=position;}
    void requestItemLayoutAt(int i) {} void invalidate() {}
  }
  static class FolderTabs {
    ViewPagerTopView top = new ViewPagerTopView(); int visibility;
    ViewPagerTopView getTopView() {return top;}
    void setVisibility(int value) {visibility=value;}
  }
  static class Size {static int getHeaderPortraitSize() {return 56;}}
  static class View {static final int INVISIBLE=4,VISIBLE=0,GONE=8;}
  static class ViewGroup extends View {
    List<View> children=new ArrayList<>();
    void addView(View child) {children.add(child);}
    void removeView(View child) {children.remove(child);}
    static class MarginLayoutParams {int topMargin;}
  }
  static class HeaderView {static int getSize(boolean offset) {return 56;}}
  static class Screen {static int dp(float value) {return Math.round(value);}}
  static class TGFoundChat {int id; TGFoundChat(int id) {this.id=id;}}
  static class ListItem {
    static final int TYPE_PROGRESS=1, TYPE_ICONIZED_EMPTY=2, TYPE_CHAT=3;
    int type, id; String text;
    ListItem(int type,int id) {this.type=type; this.id=id;}
    ListItem(int type,int id,int icon,String text) {this(type,id); this.text=text;}
  }
  static class android {static class R {static class id {static final int empty=-1;}}}
  static class TdlibChatListSlice {
    boolean loading=true;
    boolean needProgressPlaceholder() {return loading;}
  }
  static class GridLayoutManager {
    int position=-1, offset=-1;
    void scrollToPositionWithOffset(int position,int offset) {this.position=position; this.offset=offset;}
  }
  static class Recycler extends View {
    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams();
    GridLayoutManager manager=new GridLayoutManager();
    Object getLayoutParams() {return params;}
    Object getLayoutManager() {return manager;}
    void requestLayout() {} void invalidateItemDecorations() {} void stopScroll() {}
    void setScrollDisabled(boolean value) {}
  }
  static class Adapter {
    List<ListItem> items;
    void setItems(List<ListItem> items,boolean animated) {this.items=items;}
  }
  final class Pager {
    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(); int selected;
    Object getLayoutParams() {return params;}
    void requestLayout() {}
    void setCurrentItem(int index,boolean animate) {
      selected=index;
      if (!rebuildingPager) displayChatList(pagerChatLists.get(index));
    }
  }
  static class PagerAdapter {
    static final int POSITION_NONE=-2;
    void notifyDataSetChanged() {}
    public int getCount() {return 0;}
    public boolean isViewFromObject(View view,Object object) {return false;}
    public int getItemPosition(Object object) {return POSITION_NONE;}
    public Object instantiateItem(ViewGroup container,int position) {return null;}
    public void destroyItem(ViewGroup container,int position,Object object) {}
  }
  static class Context {}
  static class ShareChatPage {
    TdApi.ChatList chatList; Adapter adapter=new Adapter(); Recycler recyclerView=new Recycler();
    Object decoration=new Object();
    ShareChatPage(Context context,TdApi.ChatList list) {chatList=list;}
  }
  Context context = new Context(); Object decoration,lastFirstView; Object headerView=new Object();
  Map<String,ShareChatPage> chatPages=new HashMap<>();
  boolean rebuildingPager, autoScrollFinished=true;
  List<TdApi.ChatList> pagerChatLists = new ArrayList<>();
  Pager folderPager = new Pager(); SharePagerAdapter folderPagerAdapter=new SharePagerAdapter();
  FolderTabs folderTabs = new FolderTabs(); Tdlib tdlib = new Tdlib();
  List<TdApi.ChatList> folderChatLists = new ArrayList<>();
  List<ViewPagerTopView.Item> folderItems = new ArrayList<>();
  TdApi.ChatList displayingChatList=ChatPosition.CHAT_LIST_MAIN;
  Adapter adapter=new Adapter(); Recycler recyclerView=new Recycler();
  Map<Integer,List<TGFoundChat>> chats=new HashMap<>();
  Map<Integer,TdlibChatListSlice> loaded=new HashMap<>();
  Set<Integer> selectedRecipients=new HashSet<>();
  boolean search;
  static final float VERTICAL_PADDING_SIZE=4;
  boolean inSearchMode() {return search;}
  int getTopEdge() {return 100;}
  int getContentOffset() {return 100;}
  void updateFolderCounters() {}
  TdlibChatListSlice getListByChatList(TdApi.ChatList list) {return loaded.get(list.id);}
  List<TGFoundChat> getChatsByChatList(TdApi.ChatList list) {return chats.get(list.id);}
  void initializeChatList(TdApi.ChatList list) {loaded.put(list.id,new TdlibChatListSlice());}
  void addCells(List<TGFoundChat> chats,List<ListItem> items) {
    for (TGFoundChat chat:chats) items.add(new ListItem(ListItem.TYPE_CHAT,chat.id));
  }
  static void check(boolean value,String message) {if (!value) throw new AssertionError(message);}
  public static void main(String[] args) {
    ShareFolderTest s=new ShareFolderTest();
    TdApi.ChatFolderInfo a=new TdApi.ChatFolderInfo(1,"Work");
    TdApi.ChatFolderInfo b=new TdApi.ChatFolderInfo(2,"Friends");
    TdApi.ChatFolderInfo[] folders={a,b};
    s.rebuildFolderTabs(folders,1);
    check(s.folderChatLists.get(0).id==1 && s.folderChatLists.get(1).id==0,"Main tab order");
    check(s.folderTabs.top.selected==1 && s.getFolderTabsHeight()==56,"Initial selection and height");
    s.loaded.put(1,new TdlibChatListSlice()); s.loaded.get(1).loading=false; s.chats.put(1,Arrays.asList(new TGFoundChat(42)));
    s.selectedRecipients.add(99); s.folderPager.setCurrentItem(0,true);
    check(s.displayingChatList.id==1 && s.folderPager.selected==0,"Page selection");
    check(s.adapter.items.size()==1 && s.adapter.items.get(0).id==42,"Folder destinations");
    check(s.recyclerView.manager.offset==4,"Keep sheet expansion when switching");
    ShareChatPage work=s.getOrCreateChatPage(new TdApi.ChatListFolder(1));
    check(work==s.getOrCreateChatPage(new TdApi.ChatListFolder(1)),"Reuse the actual page grid");
    ViewGroup host=new ViewGroup();
    Object attached=s.folderPagerAdapter.instantiateItem(host,0);
    check(s.folderPagerAdapter.isViewFromObject(work.recyclerView,attached),"Pager hosts real grid");
    check(host.children.contains(work.recyclerView),"Page attached");
    s.folderPagerAdapter.destroyItem(host,0,attached);
    check(host.children.isEmpty() && s.chatPages.containsValue(work),"Detach keeps reusable grid");
    s.prepareFolderPaging();
    s.folderPager.setCurrentItem(1,true);
    check(s.recyclerView!=work.recyclerView,"Each folder owns its grid");
    s.folderPager.setCurrentItem(0,true);
    check(s.recyclerView==work.recyclerView,"Returning folder reuses its grid");
    a.name="Renamed"; int before=s.folderTabs.top.rebuilds;
    s.rebuildFolderTabs(folders,1);
    check(s.folderTabs.top.rebuilds>before,"Rebuild the tab views after rename");
    check("Renamed".contentEquals(s.folderTabs.top.items.get(0).label),"New title");
    s.tdlib.settings.hidden.add(1); s.rebuildFolderTabs(folders,1);
    check(s.displayingChatList.id==0,"Hidden selected folder falls back to main");
    check(s.selectedRecipients.contains(99),"Selections survive folder changes");
    s.folderPager.setCurrentItem(1,true); check(s.displayingChatList.id==2,"Next visible folder");
    check(s.loaded.containsKey(2) && s.adapter.items.get(0).type==ListItem.TYPE_PROGRESS,
      "Uncached folder displays loading");
    ShareChatPage friends=s.getOrCreateChatPage(new TdApi.ChatListFolder(2));
    s.loaded.get(2).loading=false; s.refreshChatPage(friends);
    check(s.adapter.items.size()==1 && s.adapter.items.get(0).type==ListItem.TYPE_ICONIZED_EMPTY &&
      s.adapter.items.get(0).text.contains("No chats"),"Loaded folder with no eligible chats has feedback");
    s.chats.put(2,new ArrayList<>(Arrays.asList(new TGFoundChat(50)))); s.refreshChatPage(friends);
    check(s.adapter.items.size()==1 && s.adapter.items.get(0).id==50,"New chat replaces empty state");
    s.chats.get(2).clear(); s.refreshChatPage(friends);
    check(s.adapter.items.get(0).type==ListItem.TYPE_ICONIZED_EMPTY,"Removing last chat restores feedback");
    s.folderPager.setCurrentItem(0,true);
    s.chats.get(2).add(new TGFoundChat(51)); s.refreshChatPage(friends);
    check(friends.adapter.items.get(0).id==51,"Offscreen page receives chat updates");
    s.rebuildFolderTabs(new TdApi.ChatFolderInfo[]{b,a},0);
    check(s.displayingChatList.id==0 && s.folderPager.selected==0,"Reorder preserves selected list");
    check(s.folderPagerAdapter.getItemPosition(work)==PagerAdapter.POSITION_NONE,
      "Hidden pages leave the pager dataset");
    check(s.folderPagerAdapter.getItemPosition(friends)==1,"Stable page follows folder order");
    s.search=true; s.rebuildFolderTabs(folders,1);
    check(s.folderTabs.visibility==View.INVISIBLE,"Hide tabs during search");
    Settings.instance.enabled=false; s.rebuildFolderTabs(folders,1);
    check(s.displayingChatList.id==0 && s.folderTabs.visibility==View.GONE &&
      s.getFolderTabsHeight()==0,"Disable folder feature");
    check(s.folderPager.params.topMargin==56,"Restore grid margin");
  }
  METHODS
}
'''.replace("METHODS", methods)
    with tempfile.TemporaryDirectory() as directory:
      path = Path(directory) / "ShareFolderTest.java"
      path.write_text(harness)
      compiled = subprocess.run(["javac", str(path)], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stderr)
      executed = subprocess.run(["java", "-cp", directory, "ShareFolderTest"],
                                capture_output=True, text=True)
      self.assertEqual(executed.returncode, 0, executed.stderr)


  def test_popup_opens_before_page_layout_or_chat_loading(self):
    source = read("app/src/main/java/org/thunderdog/challegram/ui/ShareController.java")
    methods = "\n".join(java_method(source, signature) for signature in (
      "public void show (boolean overlayStatusBar)", "private void launchOpenAnimation ()",
    ))
    harness = r'''
public class ShareOpenTest {
  static class View {}
  static class Context {
    boolean registered;
    void addFullScreenView(Object controller,boolean flag) {registered=true;}
  }
  static class Recycler {
    int invalidations;
    void invalidateItemDecorations() {invalidations++;}
  }
  static class WindowManager {
    static class LayoutParams {static final int SOFT_INPUT_ADJUST_RESIZE=1;}
  }
  static class Settings {
    static Settings instance() {return new Settings();}
    boolean useEdgeToEdge() {return true;}
  }
  static class PopupLayout {
    final Context context;
    boolean initialized, overlay;
    int shown;
    PopupLayout(Context context) {this.context=context;}
    public void onCustomShowComplete() {}
    void setSoftInputMode(int mode) {}
    void setBoundController(Object controller) {}
    void setPopupHeightProvider(Object controller) {}
    void setOverlayStatusBar(boolean overlay) {this.overlay=overlay;}
    void init(boolean edgeToEdge) {initialized=true;}
    void setAlwaysApplyIme(boolean value) {}
    void setHideKeyboard() {}
    void setNeedRootInsets() {}
    void setTouchProvider(Object controller) {}
    void setIgnoreHorizontal() {}
    void showSimplePopupView(View root,int height) {
      check(initialized && context.registered && root!=null && height==320,
        "Popup opens only after initialization, root creation, and fullscreen registration");
      shown++;
    }
  }
  Object tdlib=new Object();
  Context context=new Context();
  Recycler recyclerView=new Recycler();
  View root;
  PopupLayout popupLayout;
  boolean openLaunched;
  static final int EXPORT_AVAILABLE=1;
  Context context() {return context;}
  int getExportContentState() {return 0;}
  void exportContent() {} void destroy() {}
  boolean isDestroyed() {return false;}
  View getValue() {
    // Construct the unattached pager, without delivering layout or data callbacks.
    if (root==null) root=new View();
    return root;
  }
  int calculateTotalHeight() {return 320;}
  static void check(boolean valid,String message) {if (!valid) throw new AssertionError(message);}
  public static void main(String[] args) {
    for (boolean precreated:new boolean[]{false,true}) {
      ShareOpenTest controller=new ShareOpenTest();
      if (precreated) controller.getValue();
      controller.show(true);
      check(controller.popupLayout.shown==1,"Show cannot depend on page layout or chat callbacks");
      check(controller.popupLayout.overlay,"Preserve requested overlay mode");
      controller.popupLayout.onCustomShowComplete();
      check(controller.recyclerView.invalidations==1,"Refresh decorations after opening");
      controller.launchOpenAnimation();
      check(controller.popupLayout.shown==1,"Later callbacks cannot reopen the popup");
    }
  }
  METHODS
}
'''.replace("METHODS", methods)
    with tempfile.TemporaryDirectory() as directory:
      path = Path(directory) / "ShareOpenTest.java"
      path.write_text(harness)
      compiled = subprocess.run(["javac", str(path)], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stderr)
      executed = subprocess.run(["java", "-cp", directory, "ShareOpenTest"],
                                capture_output=True, text=True)
      self.assertEqual(executed.returncode, 0, executed.stderr)


  def test_pager_leaves_outside_touches_for_dismissal(self):
    source = read("app/src/main/java/org/thunderdog/challegram/ui/ShareController.java")
    pager = java_method(source, "folderPager = new RtlViewPager(context)")
    harness = r'''
public class ShareTouchTest {
  static class Context {}
  static class MotionEvent {
    static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2;
    int action; float y;
    MotionEvent(int action,float y) {this.action=action; this.y=y;}
    int getActionMasked() {return action;}
    float getY() {return y;}
  }
  static class Header {float y; float getY() {return y;}}
  static class RtlViewPager {
    float y; int delivered;
    RtlViewPager(Context context) {}
    float getY() {return y;}
    boolean dispatchTouchEvent(MotionEvent e) {delivered++; return true;}
  }
  Context context=new Context(); Header headerView=new Header(); RtlViewPager folderPager;
  void create() { PAGER; }
  static void check(boolean value,String message) {if (!value) throw new AssertionError(message);}
  public static void main(String[] args) {
    ShareTouchTest c=new ShareTouchTest(); c.create();
    c.headerView.y=400; c.folderPager.y=112;
    check(!c.folderPager.dispatchTouchEvent(new MotionEvent(0,100)),
      "Tap above collapsed panel must reach popup dismissal");
    check(c.folderPager.delivered==0,"Pager must not claim the outside gesture");
    check(!c.folderPager.dispatchTouchEvent(new MotionEvent(0,287)),"Pixel above header is outside");
    check(c.folderPager.dispatchTouchEvent(new MotionEvent(0,288)),"Header boundary is inside");
    check(c.folderPager.dispatchTouchEvent(new MotionEvent(0,450)),"Chat grid swipe starts normally");
    check(c.folderPager.dispatchTouchEvent(new MotionEvent(2,-50)),
      "An inside gesture may continue above panel");
    check(c.folderPager.dispatchTouchEvent(new MotionEvent(1,-50)),"Inside gesture completes");
    c.folderPager.y=52;
    check(!c.folderPager.dispatchTouchEvent(new MotionEvent(0,347)),"Include pager translation");
    c.headerView.y=0;
    check(c.folderPager.dispatchTouchEvent(new MotionEvent(0,0)),"Expanded panel supports paging");
    c.headerView=null;
    check(c.folderPager.dispatchTouchEvent(new MotionEvent(0,0)),"Creation before header is safe");
  }
}
'''.replace("PAGER", pager)
    with tempfile.TemporaryDirectory() as directory:
      path = Path(directory) / "ShareTouchTest.java"
      path.write_text(harness)
      compiled = subprocess.run(["javac", str(path)], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stderr)
      executed = subprocess.run(["java", "-cp", directory, "ShareTouchTest"],
                                capture_output=True, text=True)
      self.assertEqual(executed.returncode, 0, executed.stderr)


if __name__ == "__main__":
  unittest.main()
