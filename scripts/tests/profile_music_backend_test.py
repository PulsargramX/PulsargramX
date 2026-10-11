"""Run profile music contracts against the actual backend and TDLib API, without Gradle."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / 'app/src/main/java/org/thunderdog/challegram/telegram/TdlibProfileMusic.java'

STUBS = {
  'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
  'androidx/annotation/IntDef.java': '''package androidx.annotation;
public @interface IntDef { long[] value() default {}; boolean flag() default false; }''',
  'android/widget/Toast.java': 'package android.widget; public class Toast { public static final int LENGTH_SHORT=0; }',
  'me/vkryl/core/lambda/RunnableData.java': '''package me.vkryl.core.lambda;
public interface RunnableData<T> { void runWithData(T data); }''',
  'tgx/td/ChatId.java': '''package tgx.td;
public class ChatId {
 public static boolean isSecret(long id) { return id < -1997852516352L && id > -2002147483648L; }
 public static long fromUserId(long id) { return id; }
}''',
  'org/drinkless/tdlib/Client.java': '''package org.drinkless.tdlib;
public class Client { public interface ResultHandler { void onResult(TdApi.Object result); } }''',
  'org/thunderdog/challegram/core/Lang.java': '''package org.thunderdog.challegram.core;
public class Lang { public static String getString(int id) { return Integer.toString(id); } }''',
  'org/thunderdog/challegram/tool/UI.java': '''package org.thunderdog.challegram.tool;
import org.drinkless.tdlib.TdApi;
public class UI {
 public static int errors, toasts;
 public static void showError(TdApi.Object error) { errors++; }
 public static void showToast(int id,int duration) { toasts++; }
}''',
  'org/thunderdog/challegram/data/TD.java': '''package org.thunderdog.challegram.data;
import org.drinkless.tdlib.TdApi; import org.thunderdog.challegram.telegram.Tdlib;
public class TD {
 public static class DownloadedFile {}
 public static int saves;
 public static int getFileId(TdApi.Message m) { return ((TdApi.MessageAudio)m.content).audio.audio.id; }
 public static String getTitle(TdApi.Audio a) { return a.title; }
 public static String getSubtitle(TdApi.Audio a) { return a.performer; }
 public static DownloadedFile getDownloadedFile(Tdlib t,TdApi.Message m) {
  return ((TdApi.MessageAudio)m.content).audio.audio.local.isDownloadingCompleted ? new DownloadedFile() : null;
 }
 public static void saveFile(Object context,DownloadedFile file) { saves++; }
}''',
  'org/thunderdog/challegram/navigation/ViewController.java': '''package org.thunderdog.challegram.navigation;
public class ViewController<T> {
 public static class OptionColor { public static final int RED=1,NORMAL=0; }
 public interface Options { boolean click(Object view,int id); }
 public Options options;
 public boolean destroyed;
 public boolean isDestroyed() { return destroyed; }
 public Object context() { return null; }
 public void showOptions(String title,int[] ids,String[] labels,int[] colors,int[] icons,Options callback) { options=callback; }
}''',
  'org/thunderdog/challegram/ui/ShareController.java': '''package org.thunderdog.challegram.ui;
import org.drinkless.tdlib.TdApi; import org.thunderdog.challegram.telegram.Tdlib;
public class ShareController {
 public static class Args { public Args(TdApi.InputMessageContent content) {} }
 public ShareController(Object context,Tdlib tdlib) {}
 public void setArguments(Args args) {} public void show() {}
}''',
  'org/thunderdog/challegram/widget/ProfileMusicUndoView.java': '''package org.thunderdog.challegram.widget;
import org.thunderdog.challegram.navigation.ViewController;
public class ProfileMusicUndoView {
 public static Runnable restore;
 public static void show(ViewController<?> c,Runnable undo) { restore=undo; }
}''',
  'org/thunderdog/challegram/telegram/Tdlib.java': '''package org.thunderdog.challegram.telegram;
import org.drinkless.tdlib.*; import java.util.*;
public class Tdlib {
 public interface ResultHandler<T extends TdApi.Object> { void onResult(T result,TdApi.Error error); }
 public static class Request {
  public final TdApi.Function<?> function; public final ResultHandler callback;
  Request(TdApi.Function<?> f,ResultHandler c) { function=f; callback=c; }
 }
 public final List<Request> requests=new ArrayList<>();
 public int refreshes;
 public long myUserId() { return 100; }
 public void privateChat(long userId,me.vkryl.core.lambda.RunnableData<TdApi.Chat> callback) {
  TdApi.Chat chat=new TdApi.Chat(); chat.id=userId; callback.runWithData(chat);
 }
 public void uiExecute(Runnable r) { r.run(); }
 public <T extends TdApi.Object> void send(TdApi.Function<T> f,ResultHandler<T> c) { requests.add(new Request(f,c)); }
 public class Transport {
  public void send(TdApi.Function<?> f,Client.ResultHandler c) {
   requests.add(new Request(f,(result,error)->c.onResult(error != null ? error : result)));
  }
 }
 public Transport client() { return new Transport(); }
 public Client.ResultHandler messageHandler() { return result->{}; }
 public class Context {
  public Player player() { return new Player(); }
 }
 public class Player {
  public void refreshProfileMusic(Tdlib source) { refreshes++; }
 }
 public Context context() { return new Context(); }
 public class Listeners { public void addCleanupListener(CleanupStartupDelegate listener) {} }
 public Listeners listeners() { return new Listeners(); }
 public Request take(Class<?> type) {
  for(int i=0;i<requests.size();i++) if(type.isInstance(requests.get(i).function)) return requests.remove(i);
  throw new AssertionError("No request of " + type.getSimpleName());
 }
 public int count(Class<?> type) {
  int n=0; for(Request r:requests) if(type.isInstance(r.function)) n++; return n;
 }
 @SuppressWarnings("unchecked") public void reply(Request r,TdApi.Object value,TdApi.Error error) { r.callback.onResult(value,error); }
}''',
}

CONTRACTS = r'''package org.thunderdog.challegram.telegram;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.widget.ProfileMusicUndoView;
import org.thunderdog.challegram.tool.UI;
import java.util.*;

public class ProfileMusicContracts {
 static void expect(boolean value,String message) { if(!value) throw new AssertionError(message); }
 static TdApi.Audio audio(int id,String title) {
  TdApi.Audio a=new TdApi.Audio(); a.title=title; a.performer="Artist"; a.duration=42;
  a.audio=new TdApi.File(); a.audio.id=id; a.audio.local=new TdApi.LocalFile();
  return a;
 }
 static TdApi.Message chatAudio(int id) {
  TdApi.Message m=new TdApi.Message(); m.chatId=50; m.id=1000+id; m.canBeSaved=true;
  m.content=new TdApi.MessageAudio(audio(id,"Song "+id),null); return m;
 }
 static int file(TdApi.AddProfileAudio f) { return ((TdApi.InputFileId)f.audio.audio).id; }
 static void success(Tdlib t,Tdlib.Request r) { t.reply(r,new TdApi.Ok(),null); }

 static void identitiesAndPermissions() {
  Tdlib t=new Tdlib(); TdlibProfileMusic music=new TdlibProfileMusic(t);
  TdApi.Audio song=audio(1,"One");
  TdApi.Message a=music.message(100,song), b=music.message(100,song), c=music.message(200,song);
  expect(a.id==b.id && a.id!=c.id,"Stable track identity must include profile owner");
  TdlibProfileMusic otherAccount=new TdlibProfileMusic(new Tdlib());
  expect(a.id!=otherAccount.message(100,song).id,"Playback keys must not collide across accounts");
  expect(a.id<0 && a.chatId==0,"Profile tracks must never be sent to chat APIs");
  expect(TdlibProfileMusic.userId(a)==100,"Keep the profile playback source");
  expect(TdlibProfileMusic.canSave(a),"A profile song can be added to another profile");
  TdApi.Message message=chatAudio(2);
  expect(TdlibProfileMusic.canSave(message),"Eligible chat audio");
  message.canBeSaved=false; expect(!TdlibProfileMusic.canSave(message),"Protected audio stays protected");
  message.canBeSaved=true; message.chatId=-1999999999999L;
  expect(!TdlibProfileMusic.canSave(message),"Secret chat audio cannot be exported to a profile");
  message.chatId=50; message.selfDestructType=new TdApi.MessageSelfDestructTypeTimer(10);
  expect(!TdlibProfileMusic.canSave(message),"Self-destruct audio cannot be exported");
  message.selfDestructType=null; message.id=-10;
  expect(!TdlibProfileMusic.canSave(message),"Unsent audio is not server audio");
  music.onPerformUserCleanup();
  expect(music.message(100,song).id!=a.id,"Session cleanup must not reuse active listener keys");
 }

 static void membershipAndSerialMutations() {
  Tdlib t=new Tdlib(); TdlibProfileMusic music=new TdlibProfileMusic(t);
  int[] checks={0}, changes={0}, done={0};
  music.check(1,saved->{checks[0]++; expect(!saved,"404 means absent");});
  t.reply(t.take(TdApi.IsProfileAudio.class),null,new TdApi.Error(404,"NOT_FOUND"));
  expect(checks[0]==1,"Missing song can be added");
  music.check(1,saved->checks[0]++);
  t.reply(t.take(TdApi.IsProfileAudio.class),null,new TdApi.Error(500,"OFFLINE"));
  expect(checks[0]==1,"Unknown membership must not toggle a song");
  music.addListener(()->changes[0]++);
  music.add(TdlibProfileMusic.input(audio(1,"One")),()->done[0]++);
  music.remove(audio(2,"Two"),()->done[0]++);
  music.move(3,0,()->done[0]++);
  expect(t.count(TdApi.AddProfileAudio.class)==1 && t.count(TdApi.RemoveProfileAudio.class)==0,"Serialize playlist edits");
  t.reply(t.take(TdApi.AddProfileAudio.class),null,new TdApi.Error(400,"AUDIO_INVALID"));
  expect(changes[0]==0 && done[0]==0,"Rejected additions do not update UI as successes");
  expect(t.count(TdApi.RemoveProfileAudio.class)==1,"Failure must not block subsequent edits");
  success(t,t.take(TdApi.RemoveProfileAudio.class));
  Tdlib.Request move=t.take(TdApi.SetProfileAudioPosition.class);
  expect(((TdApi.SetProfileAudioPosition)move.function).afterFileId==0,"Zero selects the first position");
  success(t,move);
  expect(changes[0]==2 && done[0]==2 && t.refreshes==3,"Successful edits notify observers and reconcile playback");
 }

 static void paginationAndCancellation() {
  Tdlib t=new Tdlib(); TdlibProfileMusic music=new TdlibProfileMusic(t);
  List<TdApi.Audios> results=new ArrayList<>();
  music.search(200,"sOnG",()->true,(audios,error)->{expect(error==null,"Search error");results.add(audios);});
  Tdlib.Request first=t.take(TdApi.GetUserProfileAudios.class);
  expect(((TdApi.GetUserProfileAudios)first.function).userId==200,"Search the requested profile");
  t.reply(first,new TdApi.Audios(3,new TdApi.Audio[]{audio(1,"Song One"),audio(2,"Other")}),null);
  expect(results.isEmpty(),"Search must include later pages before completing");
  Tdlib.Request next=t.take(TdApi.GetUserProfileAudios.class);
  expect(((TdApi.GetUserProfileAudios)next.function).offset==2,"Pagination counts raw songs, not matches");
  t.reply(next,new TdApi.Audios(3,new TdApi.Audio[]{audio(3,"SONG Three")}),null);
  expect(results.size()==1 && results.get(0).audios.length==2,"Case-insensitive search spans the entire playlist");
  music.search(200,"Song",()->true,(audios,error)->{throw new AssertionError("Stale account callback");});
  Tdlib.Request stale=t.take(TdApi.GetUserProfileAudios.class);
  music.onPerformUserCleanup();
  t.reply(stale,new TdApi.Audios(10,new TdApi.Audio[]{audio(4,"Song")}),null);
  expect(t.count(TdApi.GetUserProfileAudios.class)==0,"Cleanup cancels further search pages");
 }

 static void bulkAndStaleWrites() {
  Tdlib t=new Tdlib(); TdlibProfileMusic music=new TdlibProfileMusic(t);
  TdApi.Message a=chatAudio(1), b=chatAudio(2); int[] done={0};
  b.canBeSaved=false; music.addAll(new TdApi.Message[]{a,b},()->done[0]++);
  expect(t.count(TdApi.AddProfileAudio.class)==0,"Validate a complete selection before exporting any song");
  b.canBeSaved=true; music.addAll(new TdApi.Message[]{a,b},()->done[0]++);
  Tdlib.Request request=t.take(TdApi.AddProfileAudio.class);
  expect(file((TdApi.AddProfileAudio)request.function)==2,"Reverse additions preserve the selected order");
  success(t,request); success(t,t.take(TdApi.AddProfileAudio.class));
  expect(done[0]==1,"Bulk success runs exactly once");
  music.add(TdlibProfileMusic.input(audio(3,"Three")),()->{throw new AssertionError("Stale write success");});
  music.move(3,1,null); Tdlib.Request stale=t.take(TdApi.AddProfileAudio.class);
  music.onPerformUserCleanup(); success(t,stale);
  expect(t.count(TdApi.SetProfileAudioPosition.class)==0,"Cleanup drops queued writes from the old account");
 }

 static void sharingAndUndo() {
  Tdlib t=new Tdlib(); TdlibProfileMusic music=new TdlibProfileMusic(t);
  ViewController<Void> controller=new ViewController<>();
  TdApi.Audio song=audio(8,"Eight"); TdApi.Message track=music.message(200,song);
  music.showSaveOptions(controller,track);
  t.reply(t.take(TdApi.IsProfileAudio.class),null,new TdApi.Error(404,"NOT_FOUND"));
  controller.options.click(null,R.id.btn_saveToSavedMessages);
  TdApi.SendMessage send=(TdApi.SendMessage)t.take(TdApi.SendMessage.class).function;
  expect(send.chatId==100 && send.inputMessageContent instanceof TdApi.InputMessageAudio,"Share profile songs as audio, never phantom forwards");
  expect(((TdApi.InputFileId)((TdApi.InputMessageAudio)send.inputMessageContent).audio.audio).id==8,"Preserve the server audio file");
  music.remove(controller,song,7); success(t,t.take(TdApi.RemoveProfileAudio.class));
  expect(ProfileMusicUndoView.restore!=null,"Removal offers Undo after server success");
  ProfileMusicUndoView.restore.run(); success(t,t.take(TdApi.AddProfileAudio.class));
  Tdlib.Request move=t.take(TdApi.SetProfileAudioPosition.class);
  expect(((TdApi.SetProfileAudioPosition)move.function).afterFileId==7,"Undo restores the previous playlist position");
  success(t,move);
  music.toggle(controller,track); success(t,t.take(TdApi.IsProfileAudio.class));
  t.reply(t.take(TdApi.GetUserProfileAudios.class),new TdApi.Audios(3,new TdApi.Audio[]{audio(6,"Six"),audio(7,"Seven")}),null);
  expect(((TdApi.GetUserProfileAudios)t.requests.get(t.requests.size()-1).function).offset==2,"Find original position beyond the first native page");
  t.reply(t.take(TdApi.GetUserProfileAudios.class),new TdApi.Audios(3,new TdApi.Audio[]{song}),null);
  success(t,t.take(TdApi.RemoveProfileAudio.class));
  ProfileMusicUndoView.restore.run(); success(t,t.take(TdApi.AddProfileAudio.class));
  move=t.take(TdApi.SetProfileAudioPosition.class);
  expect(((TdApi.SetProfileAudioPosition)move.function).afterFileId==7,"One-tap removal also restores the native playlist position");
  success(t,move);
  music.remove(controller,song,7); success(t,t.take(TdApi.RemoveProfileAudio.class));
  music.onPerformUserCleanup(); ProfileMusicUndoView.restore.run();
  expect(t.count(TdApi.AddProfileAudio.class)==0,"Expired Undo cannot write to another account");
 }

 public static void main(String[] args) {
  identitiesAndPermissions(); membershipAndSerialMutations(); paginationAndCancellation();
  bulkAndStaleWrites(); sharingAndUndo();
  System.out.println("Profile music backend contracts passed");
 }
}'''


class ProfileMusicBackendTest(unittest.TestCase):
  def test_profile_music_contracts(self):
    javac = shutil.which('javac')
    self.assertIsNotNone(javac, 'A JDK is required for backend contract tests')
    java = str(Path(javac).with_name('java'))
    with tempfile.TemporaryDirectory(prefix='profile-music-tests-') as directory:
      work = Path(directory)
      stubs = []
      source = BACKEND.read_text()
      r = 'package org.thunderdog.challegram; public class R {\n'
      for category in ['string', 'id', 'drawable']:
        names = sorted(set(re.findall(r'R\.' + category + r'\.(\w+)', source)))
        r += 'public static class ' + category + ' {\n'
        r += ''.join(f'public static final int {name}={i};\n' for i, name in enumerate(names))
        r += '}\n'
      r += '}\n'
      fixtures = dict(STUBS)
      fixtures['org/thunderdog/challegram/R.java'] = r
      fixtures['org/thunderdog/challegram/telegram/ProfileMusicContracts.java'] = CONTRACTS
      for name, text in fixtures.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        stubs.append(str(path))
      classes = work / 'classes'
      sources = stubs + [str(BACKEND), str(ROOT / 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java'),
        str(ROOT / 'app/src/main/java/org/thunderdog/challegram/telegram/CleanupStartupDelegate.java')]
      result = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *sources],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([java, '-cp', str(classes),
        'org.thunderdog.challegram.telegram.ProfileMusicContracts'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == '__main__':
  unittest.main()
