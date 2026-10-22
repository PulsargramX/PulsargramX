"""Exercise account cards and history lifecycle with the checked-in TDLib API, without Gradle."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]
DATA = 'app/src/main/java/org/thunderdog/challegram/data/'
CHAT = 'app/src/main/java/org/thunderdog/challegram/component/chat/'


def harness():
  card = read(DATA + 'TGMessageAccountInfo.java')
  card = card[card.index('public final class TGMessageAccountInfo'):]
  card = card.replace('public final class', 'static final class', 1)
  manager = read(CHAT + 'MessagesManager.java')
  manager_methods = '\n'.join(java_method(manager, signature) for signature in (
    'public void checkAccountInfo ()', 'private boolean shouldInsertHeaderMessage ()',
    'public void clearHeaderMessage ()', 'public void setHeaderMessage (',
    'private void checkTopEndReached (', 'private void insertHeaderMessageIfNeeded ()',
    'private void loadFromStart (MessageId fromId, TGMessage startMessage)',
    'private void loadFromMessage (MessageId messageId, int highlightMode, boolean force)',
  ))
  controller = read('app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java')
  callbacks = '\n'.join(java_method(controller, signature) for signature in (
    'public void onChatActionBarChanged (', 'public void onUserUpdated (',
  ))
  empty = java_method(read(CHAT + 'MessagesAdapter.java'), 'public boolean isEmpty ()')
  return r'''
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.ChatAccountInfo;

public class AccountInfoContracts {
  @interface Nullable {}
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static class View {}
  static class MessageView extends View {}
  static class MotionEvent {}
  static class RectF {
    float left, top, right, bottom;
    void set(float l, float t, float r, float b) {left=l; top=t; right=r; bottom=b;}
    boolean contains(float x, float y) {return x>=left && x<right && y>=top && y<bottom;}
  }
  record Draw(String value, int left, int top, int width, int height) {}
  static class Canvas {
    final List<Draw> texts = new ArrayList<>();
    void drawRoundRect(RectF bounds, int rx, int ry, Object paint) {}
  }
  static class Drawable {}
  static class Drawables {
    static Drawable get(Object resources, int id) {return new Drawable();}
    static void draw(Canvas c, Drawable d, int x, int y, Object paint) {}
  }
  static class Paints {
    static Object fillingPaint(int color) {return null;}
    static Object getPorterDuffPaint(int color) {return null;}
  }
  static class Screen {static int dp(float dp) {return Math.round(dp);}}
  static class Theme {
    static int fillingColor() {return 1;}
    static int textAccentColor() {return 2;}
  }
  interface TextColorSet {int defaultTextColor();}
  static class Text {
    static final int FLAG_ALL_BOLD=1, FLAG_ALIGN_CENTER=2, FLAG_ALIGN_RIGHT=4;
    int maxWidth;
    int getMaxWidth() {return maxWidth;}
  }
  static class TextWrapper {
    static int fontWidth=7;
    final String value;
    final Text text = new Text();
    int flags, width, height;
    TextWrapper(String value, Object style, TextColorSet colors) {this.value=value;}
    TextWrapper addTextFlags(int flags) {this.flags|=flags; return this;}
    void setViewProvider(Object views) {}
    void performDestroy() {}
    void prepare(int width) {
      text.maxWidth=width;
      this.width=Math.min(width, value.length()*fontWidth);
      height=Math.max(1, (int)Math.ceil((double)value.length()*fontWidth/width))*18;
    }
    int getWidth() {return width;}
    int getHeight() {return height;}
    Text getCurrent() {return text;}
    void draw(Canvas c, int x, int y, TextColorSet colors, float alpha) {
      if ((flags & Text.FLAG_ALIGN_CENTER)!=0) x+=(text.maxWidth-width)/2;
      c.texts.add(new Draw(value,x,y,width,height));
    }
    void draw(Canvas c, int x, int end, int pad, int y, TextColorSet colors, float alpha) {
      draw(c,x,y,colors,alpha);
    }
  }
  static class R {
    static class string {
      static final int ContactInfoIsNotContact=1, ContactInfoIsContact=2, ContactInfoPhone=3,
        ContactInfoPhoneFragment=4, ContactInfoRegistration=5, ContactInfoNotVerified=6;
    }
    static class drawable {static final int baseline_info_16=1;}
  }
  static class Lang {
    static boolean rtl;
    static Locale locale=Locale.US;
    static boolean rtl() {return rtl;}
    static Locale locale() {return locale;}
    static String getString(int id) {
      return new String[]{"", "Not a contact", "Contact", "Phone number", "Anonymous",
        "Registration", "Not an official account"}[id];
    }
    static String monthYearFull(long date, TimeUnit unit) {
      return new java.text.SimpleDateFormat("MMMM yyyy", locale).format(new Date(unit.toMillis(date)));
    }
  }
  static class Emoji {static String getEmojiFlagFromCountry(String code) {return "flag:"+code;}}
  static class ClickHelper {
    interface Delegate {
      boolean needClickAt(View view,float x,float y);
      void onClickAt(View view,float x,float y);
    }
    final Delegate delegate;
    ClickHelper(Delegate delegate) {this.delegate=delegate;}
    boolean onTouchEvent(View view,MotionEvent event) {return false;}
    void cancel(View view,float x,float y) {}
  }
  static class TD {
    static long getUserId(TdApi.Chat chat) {
      if(chat.type instanceof TdApi.ChatTypePrivate p) return p.userId;
      if(chat.type instanceof TdApi.ChatTypeSecret s) return s.userId;
      return 0;
    }
    static String getUserName(TdApi.User user) {return user.firstName;}
    static TdApi.Message newFakeMessage(long chatId,TdApi.MessageSender sender,TdApi.MessageContent text) {
      TdApi.Message result=new TdApi.Message(); result.chatId=chatId; return result;
    }
  }
  static class TGMessage {
    final MessagesManager manager;
    final Tdlib tdlib;
    final TdApi.Message message;
    final Object currentViews=new Object();
    int width, updates;
    boolean destroyed, thread;
    TGMessage(MessagesManager manager,TdApi.Message message) {
      this.manager=manager; this.tdlib=manager.tdlib; this.message=message;
    }
    Object getTextStyleProvider() {return null;}
    boolean useBubbles() {return true;}
    int getBubbleDateTextColor() {return 1;}
    int getBubbleDateBackgroundColor() {return 2;}
    long getChatId() {return message.chatId;}
    int getContentY() {return 10;}
    boolean isThreadHeader() {return thread;}
    boolean isDestroyed() {return destroyed;}
    boolean isDescendantOrSelf(long id) {return message.id==id;}
    Tdlib tdlib() {return tdlib;}
    Controller messagesController() {return manager.controller;}
    void onDestroy() {destroyed=true; onMessageContainerDestroyed();}
    protected void rebuildAndUpdateContent() {if(width>0) buildContent(width); updates++;}
    protected void buildContent(int maxWidth) {}
    protected void drawContent(MessageView view,Canvas c,int x,int y,int max) {}
    protected void onMessageContainerDestroyed() {}
    protected int getContentWidth() {return 0;}
    protected int getContentHeight() {return 0;}
    protected boolean headerDisabled() {return false;}
    protected boolean disableBubble() {return false;}
    public boolean isFakeMessage() {return false;}
    public boolean canSwipe() {return true;}
    public boolean canBeSelected() {return true;}
    public boolean canMarkAsViewed() {return true;}
    public boolean needRefreshViewCount() {return true;}
    public boolean onTouchEvent(MessageView view,MotionEvent event) {return false;}
    public boolean performLongPress(View view,float x,float y) {return true;}
  }
  CARD
  static class TGMessageBotInfo extends TGMessage {
    TGMessageBotInfo(MessagesManager m) {super(m,new TdApi.Message());}
    void setBoundAdapter(Adapter adapter) {}
  }
  static class Adapter {
    final ArrayList<TGMessage> items=new ArrayList<>();
    TGMessage getTopMessage() {return items.isEmpty()?null:items.get(items.size()-1);}
    int indexOfMessage(Predicate<TGMessage> filter) {
      for(int i=0;i<items.size();i++) if(filter.test(items.get(i))) return i;
      return -1;
    }
    void removeItem(int index) {items.remove(index).onDestroy();}
    void addMessage(TGMessage message,boolean top,boolean scroll) {items.add(message);}
    void reset(TGMessage start) {
      for(TGMessage message:items) message.onDestroy();
      items.clear();
      if(start!=null) items.add(start);
    }
    EMPTY
  }
  static class ThreadInfo {
    TGMessage buildHeaderMessage(MessagesManager manager) {
      TGMessage result=new TGMessage(manager,new TdApi.Message()); result.thread=true; return result;
    }
  }
  static class MessagesLoader {
    static final int SPECIAL_MODE_NONE=0;
    long chatId=10;
    int mode;
    boolean filter;
    ThreadInfo thread;
    TdApi.MessageTopic topic;
    long getChatId() {return chatId;}
    int getSpecialMode() {return mode;}
    boolean hasSearchFilter() {return filter;}
    ThreadInfo getMessageThread() {return thread;}
    TdApi.MessageTopic getMessageTopicId() {return topic;}
    void loadFromStart(MessageId id) {}
    void loadFromMessage(MessageId id,int highlight,boolean force) {}
  }
  static class MessageId {}
  static class Settings {
    static class SavedMessageId {long topEndMessageId;}
    static Settings instance() {return new Settings();}
    SavedMessageId getScrollMessageId(int account,long chat,TdApi.MessageTopic topic) {return null;}
  }
  static class Tdlib {
    TdApi.User user;
    long openedProfile;
    Tdlib cache() {return this;}
    Tdlib ui() {return this;}
    TdApi.User user(long id) {check(id!=0,"no user lookup for group chats"); return user;}
    int id() {return 1;}
    void openChatProfile(Controller c,long id,Object thread,Object params) {openedProfile=id;}
  }
  static class Header {void setEmojiStatus(TdApi.User user) {}}
  static class Controller {
    TdApi.Chat chat;
    MessagesManager manager;
    Header headerCell=new Header();
    boolean preview,forceTouch;
    final ArrayDeque<Runnable> queue=new ArrayDeque<>();
    Controller(MessagesManager manager) {this.manager=manager;}
    TdApi.Chat getChat() {return chat;}
    long getChatId() {return chat!=null?chat.id:0;}
    boolean inPreviewMode() {return preview;}
    boolean isInForceTouchMode() {return forceTouch;}
    Controller context() {return this;}
    Object getResources() {return null;}
    void checkCanSendMessagesToUser(boolean force) {}
    void checkActionBar() {}
    void runOnUiThreadOptional(Runnable callback) {queue.add(callback);}
    void flush() {while(!queue.isEmpty()) queue.remove().run();}
    CALLBACKS
  }
  static class MessagesManager {
    final Tdlib tdlib=new Tdlib();
    final Controller controller=new Controller(this);
    final Adapter adapter=new Adapter();
    final MessagesLoader loader=new MessagesLoader();
    TGMessage headerMessage;
    boolean insertMessageOnLoad;
    Controller controller() {return controller;}
    void checkBotStart() {}
    void scrollToPositionWithOffsetImpl(int position,int offset) {}
    void viewMessages(boolean force) {}
    MANAGER
  }

  static MessagesManager incoming(TdApi.AccountInfo info) {
    MessagesManager m=new MessagesManager();
    TdApi.User user=new TdApi.User(); user.id=10; user.firstName="Example";
    user.type=new TdApi.UserTypeRegular(); m.tdlib.user=user;
    TdApi.Chat chat=new TdApi.Chat(); chat.id=10; chat.type=new TdApi.ChatTypePrivate(10);
    chat.actionBar=new TdApi.ChatActionBarReportAddBlock(false,info); m.controller.chat=chat;
    return m;
  }
  static TGMessageAccountInfo card(MessagesManager m) {return (TGMessageAccountInfo)m.headerMessage;}
  static void draw(TGMessageAccountInfo card,int width) {
    card.width=width; card.buildContent(width);
    Canvas canvas=new Canvas(); card.drawContent(new MessageView(),canvas,0,0,width);
    check(card.cardWidth<=width-24,"card fits available width");
    for(Draw text:canvas.texts) {
      check(text.left>=card.bounds.left && text.left+text.width<=card.bounds.right,
        "text stays inside card: "+text);
      check(text.top>=card.bounds.top && text.top+text.height<=card.bounds.bottom,
        "text stays inside card vertically: "+text);
    }
    Draw title=canvas.texts.get(0);
    check(Math.abs(title.left+title.width/2.0-width/2.0)<=1,"title is centered");
  }
  public static void main(String[] args) {
    TdApi.AccountInfo info=new TdApi.AccountInfo(8,2024,"US",0,0);
    MessagesManager m=incoming(info);
    check(ChatAccountInfo.fromChat(m.controller.chat,m.tdlib.user)==info,"TDLib metadata");
    m.checkAccountInfo();
    check(m.adapter.items.isEmpty(),"wait for history start");
    m.checkTopEndReached(List.of(),false,false);
    TGMessageAccountInfo first=card(m);
    check(m.adapter.items.size()==1 && m.adapter.isEmpty(),"card is not chat history");
    m.insertHeaderMessageIfNeeded();
    check(m.adapter.items.size()==1,"no duplicate cards");
    draw(first,360);
    check(first.values.get(0).value.equals("flag:US United States"),"phone country, no full phone");
    check(first.values.get(1).value.equals("August 2024"),"registration month and year");
    check(first.footer!=null && !first.canMarkAsViewed() && !first.canBeSelected(),"notice, no message actions");
    first.clickHelper.delegate.onClickAt(new View(),0,0);
    check(m.tdlib.openedProfile==10,"card opens profile");
    for(int width:new int[]{240,360,800}) for(int font:new int[]{7,14,22}) {
      TextWrapper.fontWidth=font;
      draw(first,width);
      Lang.rtl=true; draw(first,width); Lang.rtl=false;
    }
    TextWrapper.fontWidth=7;
    m.tdlib.user.firstName="A very long display name that needs to wrap on a narrow screen";
    draw(first,240);
    m.tdlib.user.firstName="Example";
    m.tdlib.user.isContact=true;
    m.tdlib.user.verificationStatus=new TdApi.VerificationStatus(true,false,false,0);
    m.controller.onUserUpdated(m.tdlib.user); m.controller.flush();
    check(card(m)==first && first.subtitle.value.equals("Contact") && first.footer==null,
      "contact and official status update in place");
    m.tdlib.user.verificationStatus=new TdApi.VerificationStatus(false,false,false,42);
    check(!ChatAccountInfo.showUnverifiedNotice(m.tdlib.user),"third-party verification is not mislabelled");

    m.loadFromStart(new MessageId(),null);
    check(first.isDestroyed(),"history reload destroys old card");
    TGMessage middle=new TGMessage(m,new TdApi.Message());
    m.adapter.addMessage(middle,true,false);
    m.checkTopEndReached(List.of(middle),false,true);
    check(card(m)!=first && m.adapter.items.size()==1 && m.adapter.getTopMessage()==middle,
      "middle of history does not get a card");
    m.insertHeaderMessageIfNeeded();
    check(m.adapter.items.size()==2 && !card(m).isDestroyed(),"new card at reloaded history start");
    m.loadFromMessage(new MessageId(),0,true);
    m.controller.onChatActionBarChanged(10,m.controller.chat.actionBar); m.controller.flush();
    check(m.adapter.items.isEmpty(),"late updates during a jump wait for history start");
    m.insertHeaderMessageIfNeeded();
    m.controller.chat.actionBar=null;
    m.controller.onChatActionBarChanged(10,null); m.controller.flush();
    check(m.headerMessage==null && m.adapter.items.isEmpty(),"server removal removes card");
    m.controller.chat.actionBar=new TdApi.ChatActionBarReportAddBlock(false,info);
    m.controller.onChatActionBarChanged(10,m.controller.chat.actionBar); m.controller.flush();
    check(m.adapter.items.size()==1,"late metadata appears at known history start");

    for(int mode:new int[]{1,2,3,4}) {
      m.loader.mode=mode; m.checkAccountInfo();
      check(m.headerMessage==null,"no cards in special history modes");
    }
    m.loader.mode=0; m.loader.filter=true; m.checkAccountInfo();
    check(m.headerMessage==null,"no cards in filtered history");
    m.loader.filter=false; m.controller.preview=true; m.checkAccountInfo();
    check(m.headerMessage==null,"no cards in previews");
    m.controller.preview=false; m.controller.forceTouch=true; m.checkAccountInfo();
    check(m.headerMessage==null,"no cards in force-touch previews");
    m.controller.forceTouch=false; m.loader.thread=new ThreadInfo(); m.insertHeaderMessageIfNeeded();
    check(m.adapter.getTopMessage().isThreadHeader(),"thread headers remain intact");
    m.loader.thread=null; m.adapter.reset(null); m.clearHeaderMessage();
    TGMessageBotInfo bot=new TGMessageBotInfo(m); m.setHeaderMessage(bot); m.checkAccountInfo();
    check(m.headerMessage==bot,"account updates preserve bot headers");
    m.clearHeaderMessage();

    m.controller.chat.type=new TdApi.ChatTypeSecret(123,10);
    check(ChatAccountInfo.fromChat(m.controller.chat,m.tdlib.user)==info,"secret incoming chats");
    m.controller.chat.type=new TdApi.ChatTypeBasicGroup(1);
    m.checkAccountInfo();
    check(m.headerMessage==null,"group chats are excluded without looking up user zero");
    m.controller.chat.type=new TdApi.ChatTypePrivate(10);
    m.tdlib.user.type=new TdApi.UserTypeBot();
    check(ChatAccountInfo.fromChat(m.controller.chat,m.tdlib.user)==null,"bots excluded");
    m.tdlib.user.type=new TdApi.UserTypeRegular(); m.tdlib.user.isSupport=true;
    check(ChatAccountInfo.fromChat(m.controller.chat,m.tdlib.user)==null,"support excluded");
    m.tdlib.user.isSupport=false;
    m.controller.chat.actionBar=new TdApi.ChatActionBarAddContact();
    check(ChatAccountInfo.fromChat(m.controller.chat,m.tdlib.user)==null,"outgoing add-contact bar excluded");

    m=incoming(new TdApi.AccountInfo(0,0,"",0,0));
    m.checkAccountInfo(); check(m.headerMessage==null,"no fabricated registration or country");
    m.checkTopEndReached(List.of(),false,false);
    m.controller.chat.actionBar=new TdApi.ChatActionBarReportAddBlock(false,
      new TdApi.AccountInfo(8,2024,"",0,0));
    m.checkAccountInfo();
    check(m.adapter.items.size()==1,"first metadata can arrive after history is loaded");
    m=incoming(new TdApi.AccountInfo(0,2024,"ft",0,0));
    m.insertHeaderMessageIfNeeded(); draw(card(m),320);
    check(card(m).values.get(0).value.equals("Anonymous"),"anonymous Fragment number");
    check(card(m).values.get(1).value.equals("2024"),"unknown month remains year-only");
    m=incoming(new TdApi.AccountInfo(12,0,null,0,0));
    m.checkAccountInfo(); check(m.headerMessage==null,"unknown year is not invented");
    m=incoming(new TdApi.AccountInfo(0,0,"US",0,0));
    m.insertHeaderMessageIfNeeded(); draw(card(m),320);
    check(card(m).labels.size()==1,"country-only data does not invent a registration row");
    for(String code:new String[]{null,"","123","1x","ßa"}) {
      info.phoneNumberCountryCode=code;
      check(ChatAccountInfo.phoneCountry(info).isEmpty(),"invalid country: "+code);
    }
    info.registrationYear=10000;
    check(!ChatAccountInfo.hasRegistrationYear(info),"invalid year");

    m=incoming(new TdApi.AccountInfo(8,2024,"US",0,0));
    m.controller.onUserUpdated(m.tdlib.user);
    m.controller.chat=new TdApi.Chat(); m.controller.chat.id=20;
    m.controller.chat.type=new TdApi.ChatTypePrivate(20);
    m.controller.flush();
    check(m.headerMessage==null,"queued user update cannot affect a different chat");
  }
}
'''.replace('CARD', card).replace('MANAGER', manager_methods).replace('EMPTY', empty).replace(
    'CALLBACKS', callbacks)


class AccountInfoTest(unittest.TestCase):
  def test_server_data_rendering_and_history_lifecycle(self):
    javac = shutil.which('javac')
    self.assertIsNotNone(javac, 'A JDK is required for account info tests')
    java = str(Path(javac).with_name('java'))
    with tempfile.TemporaryDirectory(prefix='account-info-tests-') as directory:
      work = Path(directory)
      annotation = work / 'androidx/annotation/Nullable.java'
      annotation.parent.mkdir(parents=True)
      annotation.write_text('package androidx.annotation; public @interface Nullable {}')
      int_def = annotation.with_name('IntDef.java')
      int_def.write_text('package androidx.annotation; public @interface IntDef { '
        'long[] value() default {}; boolean flag() default false; }')
      contracts = work / 'AccountInfoContracts.java'
      contracts.write_text(harness())
      classes = work / 'classes'
      result = subprocess.run([
        javac, '-encoding', 'UTF-8', '-d', str(classes), str(annotation), str(int_def),
        str(ROOT / DATA / 'ChatAccountInfo.java'),
        str(ROOT / 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java'), str(contracts)
      ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([java, '-cp', str(classes), 'AccountInfoContracts'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == '__main__':
  unittest.main()
