"""Exercise the production audio-engine reset behind profile playlist reopening."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.profile_music_backend_test import ROOT, STUBS as BACKEND_STUBS
from scripts.tests.profile_music_player_test import extract_block

AUDIO = ROOT / 'app/src/main/java/org/thunderdog/challegram/player/AudioController.java'
BASE = ROOT / 'app/src/main/java/org/thunderdog/challegram/player/BasePlaybackController.java'
PLAYER = ROOT / 'app/src/main/java/org/thunderdog/challegram/player/TGPlayerController.java'

HARNESS = r'''package org.thunderdog.challegram.player;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibProfileMusic;
import org.thunderdog.challegram.tool.UI;
import java.util.*;
public class AudioController {
 private static final int PLAYBACK_MODE_EXOPLAYER_LIST=3;
 private int playbackMode=3,playFlags,playIndex;
 private Tdlib tdlib; private TdApi.Message object;
 private boolean isPlaying=true; private float volume=.7f;
 private ExoPlayer exoPlayer; private ArrayList<TdApi.Message> playList; private Tdlib playListTdlib;
 private void setExoPlayerParameters() {}
 private void setExoPlayerSpeed() {}
 static void expect(boolean ok,String text) { if(!ok) throw new AssertionError(text); }
 static TdApi.Message song(int id) {
  TdlibProfileMusic.Message m=new TdlibProfileMusic.Message();
  m.profileUserId=100; m.id=-id;
  TdApi.Audio a=new TdApi.Audio(); a.audio=new TdApi.File(); a.audio.id=id;
  m.content=new TdApi.MessageAudio(a,null); return m;
 }
 static void reopeningAndReordering() {
  Tdlib t=new Tdlib(); AudioController a=new AudioController(); a.tdlib=t;
  a.object=song(2);
  a.onTrackListReset(t,a.object,1,Arrays.asList(song(1),song(2),song(3)),0,0,1);
  ExoPlayer engine=a.exoPlayer; engine.position=42000;
  expect(engine.sources==1 && engine.prepares==1,"Initial playback prepares the engine once");
  for(int i=0;i<3;i++) {
   a.onTrackListReset(t,song(2),1,Arrays.asList(song(1),song(2),song(3)),0,0,1);
   expect(a.exoPlayer==engine && engine.sources==1 && engine.prepares==1,
       "Reopening an unchanged profile playlist must not rebuild the engine");
   expect(engine.position==42000 && engine.releases==0,"Reopening retains elapsed playback");
  }
  a.isPlaying=false; engine.ready=false;
  a.onTrackListReset(t,song(2),2,Arrays.asList(song(3),song(1),song(2)),0,0,0);
  expect(a.exoPlayer==engine && engine.releases==0,"Reordering retains the active engine");
  expect(engine.position==42000 && engine.window==2 && !engine.ready,
      "Reordering preserves position and pause state at the new song index");
  a.onTrackListReset(t,song(2),2,Arrays.asList(song(3),song(1),song(2)),0,8,0);
  expect(engine.position==42000 && engine.window==0 && !engine.ready,
      "Reverse playback maps the retained song index without restarting");
  a.object=song(3); // Global track notification runs before the list reset.
  a.onTrackListReset(t,a.object,0,Arrays.asList(song(3),song(1)),0,0,1);
  expect(a.exoPlayer!=engine && engine.releases==1 && a.exoPlayer.position==0,
      "A replacement for a removed current song starts at its own beginning");
 }
 static void accountAndChatBoundaries() {
  Tdlib t=new Tdlib(); AudioController a=new AudioController(); a.tdlib=t; a.object=song(1);
  a.onTrackListReset(t,a.object,0,Arrays.asList(song(1)),0,0,1);
  ExoPlayer engine=a.exoPlayer; engine.position=19000;
  a.tdlib=new Tdlib(); // The base controller already holds the new account.
  a.onTrackListReset(a.tdlib,song(1),0,Arrays.asList(song(1)),0,0,1);
  expect(engine.releases==1 && a.exoPlayer.position==0,
      "Another account must not inherit a synthetic track's playback position");
  TdApi.Message chatSong=new TdApi.Message(); chatSong.chatId=10; chatSong.id=11;
  chatSong.content=song(1).content; a.object=chatSong;
  a.onTrackListReset(t,chatSong,0,Arrays.asList(chatSong),10,0,1);
  engine=a.exoPlayer; engine.position=9000;
  a.onTrackListReset(t,chatSong,0,Arrays.asList(chatSong),10,0,1);
  expect(engine.releases==1 && a.exoPlayer.position==0,
      "Retaining profile playback does not change ordinary chat reset semantics");
 }
 public static void main(String[] args) { reopeningAndReordering(); accountAndChatBoundaries(); }
'''

STUBS = {
  'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
  'androidx/media3/common/C.java': '''package androidx.media3.common;
public class C { public static final long TIME_UNSET=-9223372036854775807L; }''',
  'androidx/media3/common/Player.java': '''package androidx.media3.common;
public interface Player { int REPEAT_MODE_ALL=2,REPEAT_MODE_ONE=1,REPEAT_MODE_OFF=0; }''',
  'androidx/media3/exoplayer/source/MediaSource.java': '''package androidx.media3.exoplayer.source;
public class MediaSource {}''',
  'androidx/media3/exoplayer/ExoPlayer.java': '''package androidx.media3.exoplayer;
import java.util.List;
import androidx.media3.exoplayer.source.MediaSource;
public class ExoPlayer {
 public long position; public int window,sources,prepares,releases; public boolean ready;
 public long getCurrentPosition() { return position; }
 public void setMediaSources(List<MediaSource> list,int index,long start) {
  sources++; window=index; position=start<0 ? 0 : start;
 }
 public void prepare() { prepares++; }
 public void release() { releases++; }
 public void removeListener(Object listener) {}
 public void addListener(Object listener) {}
 public void setVolume(float volume) {}
 public void setRepeatMode(int mode) {}
 public void setShuffleModeEnabled(boolean shuffle) {}
 public void setPlayWhenReady(boolean playing) { ready=playing; }
}''',
  'org/thunderdog/challegram/U.java': '''package org.thunderdog.challegram;
import org.drinkless.tdlib.TdApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
public class U {
 public static MediaSource newAudioMediaSource(int account,TdApi.Message track) { return new MediaSource(); }
 public static ExoPlayer newExoPlayer(Object context,boolean audio) { return new ExoPlayer(); }
}''',
  'org/thunderdog/challegram/Log.java': '''package org.thunderdog.challegram;
public class Log { public static final int TAG_PLAYER=1; public static void i(int tag,String fmt,Object... args) {} }''',
  'org/thunderdog/challegram/tool/UI.java': '''package org.thunderdog.challegram.tool;
public class UI { public static Object getAppContext() { return null; } }''',
  'org/thunderdog/challegram/data/TD.java': '''package org.thunderdog.challegram.data;
import org.drinkless.tdlib.TdApi;
public class TD {
 public static TdApi.File getFile(TdApi.Message m) { return ((TdApi.MessageAudio)m.content).audio.audio; }
 public static int getFileId(TdApi.Message m) { return getFile(m).id; }
}''',
  'org/thunderdog/challegram/telegram/Tdlib.java': '''package org.thunderdog.challegram.telegram;
public class Tdlib { public int id() { return 1; } }''',
  'org/thunderdog/challegram/telegram/TdlibProfileMusic.java': '''package org.thunderdog.challegram.telegram;
import org.drinkless.tdlib.TdApi;
public class TdlibProfileMusic {
 public static class Message extends TdApi.Message { public long profileUserId; }
 public static long userId(TdApi.Message message) { return message instanceof Message ? ((Message)message).profileUserId : 0; }
}''',
  'me/vkryl/core/StringUtils.java': '''package me.vkryl.core;
public class StringUtils {
 public static boolean equalsOrBothEmpty(String a,String b) { return java.util.Objects.equals(a,b); }
}''',
}


OPENING = r'''package org.thunderdog.challegram.widget;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.player.TGPlayerController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.PlaybackController;
import java.util.*;
public class ProfileMusicView {
 private ViewController<?> controller; private long userId; private TdApi.Audio audio;
 private boolean loading;
 static TdApi.Audio song(int id) {
  TdApi.Audio a=new TdApi.Audio(); a.audio=new TdApi.File(); a.audio.id=id; return a;
 }
 static void expect(boolean ok,String text) { if(!ok) throw new AssertionError(text); }
 static ProfileMusicView view(Tdlib t,long user) {
  ProfileMusicView v=new ProfileMusicView(); v.controller=new ViewController<>(t);
  v.userId=user; v.audio=song(1); return v;
 }
 public static void main(String[] args) {
  Tdlib t=new Tdlib(); ProfileMusicView v=view(t,100); TGPlayerController p=t.player;
  p.current=t.profileMusic().message(100,song(2)); p.user=100; p.state=1; p.position=42000;
  v.openPlaylist(); v.openPlaylist();
  expect(v.controller.navigations==2 && t.requests==0 && p.starts==0 && p.position==42000,
      "The own-profile pill opens the player without restarting or requesting a new playlist");
  p.state=0; v.openPlaylist();
  expect(p.state==0 && p.starts==0 && p.position==42000,"Reopening a paused playlist retains pause and position");
  t=new Tdlib(); v=view(t,200); v.openPlaylist(); v.openPlaylist();
  expect(t.requests==1,"Repeated pill taps share the pending native request");
  t.reply(3,song(1),song(2));
  expect(t.player.starts==1 && t.player.user==200 && v.controller.navigations==1,
      "An unopened profile starts its native playlist and opens the player");
  expect(t.player.offset==2 && !t.player.end,"Preserve native pagination for further songs");
  t=new Tdlib(); v=view(t,100); v.openPlaylist(); v.controller.destroyed=true; t.reply(1,song(1));
  expect(t.player.starts==0 && v.controller.navigations==0,"A closed profile cannot start playback or navigate");
  t=new Tdlib(); v=view(t,100); v.openPlaylist(); v.userId=200; t.reply(1,song(1));
  expect(t.player.starts==0 && v.controller.navigations==0,"A stale request cannot open another user's playlist");
  t=new Tdlib(); v=view(t,100); v.openPlaylist();
  t.player.current=t.profileMusic().message(100,song(2)); t.player.user=100;
  t.player.state=0; t.player.position=30000; t.reply(2,song(1),song(2));
  expect(t.player.starts==0 && t.player.state==0 && t.player.position==30000 && v.controller.navigations==1,
      "Playback started during loading takes precedence over the pending first-song request");
 }
'''

class ProfileMusicPlaybackTest(unittest.TestCase):
  def test_reopen_and_refresh_preserve_the_audio_engine(self):
    javac = shutil.which('javac')
    self.assertIsNotNone(javac, 'A JDK is required for audio playback regression checks')
    source = AUDIO.read_text()
    harness = HARNESS + '\n'.join([
      extract_block(source, 'public void onTrackListReset ('),
      extract_block(source, 'private boolean inReverseMode ()'),
      extract_block(BASE.read_text(), 'public final boolean comparePlayingObject ('),
    ]) + '\n}'
    player = PLAYER.read_text()
    player_fixture = '''package org.thunderdog.challegram.player;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.TD;
import me.vkryl.core.StringUtils;
public class TGPlayerController {
 public static final int PLAYLIST_FLAG_REVERSE=8,PLAY_FLAG_SHUFFLE=1,PLAY_FLAG_REPEAT=2,PLAY_FLAG_REPEAT_ONE=4;
 public static int getPlayRepeatFlag(int flags) { return flags & 6; }
'''
    player_fixture += '\n'.join(extract_block(player, signature) for signature in [
      'public static boolean compareTracks (TdApi.Message a, TdApi.Message b)',
      'public static boolean compareTrackFiles (TdApi.File a, TdApi.File b)',
    ]) + '\n}'
    fixtures = dict(STUBS)
    fixtures.update({name: content for name, content in BACKEND_STUBS.items()
      if name.startswith('androidx/annotation/')})
    fixtures['org/thunderdog/challegram/player/AudioController.java'] = harness
    fixtures['org/thunderdog/challegram/player/TGPlayerController.java'] = player_fixture
    with tempfile.TemporaryDirectory(prefix='profile-music-playback-tests-') as directory:
      work = Path(directory)
      sources = []
      for name, content in fixtures.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
      sources.append(str(ROOT / 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java'))
      classes = work / 'classes'
      result = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *sources],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([str(Path(javac).with_name('java')), '-cp', str(classes),
        'org.thunderdog.challegram.player.AudioController'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)


  def test_profile_pill_opens_the_player_without_restarting(self):
    javac = shutil.which('javac')
    self.assertIsNotNone(javac)
    widget = ROOT / 'app/src/main/java/org/thunderdog/challegram/widget/ProfileMusicView.java'
    harness = OPENING + '\n'.join(extract_block(widget.read_text(), signature) for signature in [
      'private void showPlayer ()', 'public void openPlaylist ()',
    ]) + '\n}'
    # Android navigation and TDLib transport are doubled; entry-point logic is production code.
    fixtures = {
      'androidx/annotation/Nullable.java': BACKEND_STUBS['androidx/annotation/Nullable.java'],
      'androidx/annotation/IntDef.java': BACKEND_STUBS['androidx/annotation/IntDef.java'],
      'org/thunderdog/challegram/widget/ProfileMusicView.java': harness,
      'org/thunderdog/challegram/telegram/Tdlib.java': r'''package org.thunderdog.challegram.telegram;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.player.TGPlayerController;
public class Tdlib {
 public interface Result<T> { void call(T value,TdApi.Error error); }
 public final TGPlayerController player=new TGPlayerController();
 public int requests; private Result<TdApi.Audios> pending;
 public void send(TdApi.GetUserProfileAudios request,Result<TdApi.Audios> done) { requests++; pending=done; }
 public void reply(int count,TdApi.Audio... audios) { pending.call(new TdApi.Audios(count,audios),null); }
 public void uiExecute(Runnable task) { task.run(); }
 public Tdlib context() { return this; }
 public TGPlayerController player() { return player; }
 public Tdlib profileMusic() { return this; }
 public TdApi.Message message(long user,TdApi.Audio audio) {
  TdApi.Message m=new TdApi.Message(); m.id=-audio.audio.id; m.content=new TdApi.MessageAudio(audio,null); return m;
 }
}''',
      'org/thunderdog/challegram/navigation/ViewController.java': r'''package org.thunderdog.challegram.navigation;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.ui.PlaybackController;
public class ViewController<T> {
 private Tdlib tdlib; public int navigations; public boolean destroyed;
 public ViewController(Tdlib t) { tdlib=t; }
 public boolean isDestroyed() { return destroyed; }
 public Tdlib tdlib() { return tdlib; }
 public Object context() { return null; }
 public void navigateTo(PlaybackController c) { navigations++; }
}''',
      'org/thunderdog/challegram/ui/PlaybackController.java': r'''package org.thunderdog.challegram.ui;
import org.thunderdog.challegram.telegram.Tdlib;
public class PlaybackController {
 private Tdlib t;
 public PlaybackController(Object context,Tdlib tdlib) { t=tdlib; }
 public int prepare() { return t.player.current!=null ? 0 : -1; }
}''',
      'org/thunderdog/challegram/tool/UI.java': STUBS['org/thunderdog/challegram/tool/UI.java'].replace(
        ' public static Object', ' public static void showError(Object error) {} public static Object'),
      'org/thunderdog/challegram/data/TD.java': STUBS['org/thunderdog/challegram/data/TD.java'].replace(
        ' public static int getFileId', ' public static boolean isSelfDestructTypeImmediately(TdApi.Message m) { return false; } public static int getFileId'),
      'me/vkryl/core/StringUtils.java': STUBS['me/vkryl/core/StringUtils.java'].replace(
        ' public static boolean', ' public static boolean isEmpty(String s) { return s==null || s.isEmpty(); } public static boolean'),
    }
    player = r'''package org.thunderdog.challegram.player;
import androidx.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.data.TD;
import me.vkryl.core.StringUtils;
import java.util.*;
public class TGPlayerController {
 public static final int STATE_NONE=-1;
 public TdApi.Message current; public long user,position; public int starts,state,offset; public boolean end;
 public TdApi.Message getCurrentTrack() { return current; }
 public long getProfileMusicUserId(Tdlib t) { return current!=null ? user : 0; }
 public int getPlayState(Tdlib t,TdApi.Message m) { return current!=null ? state : STATE_NONE; }
 public boolean isProfileMusicSource(Tdlib t,long id,String query) { return current!=null && user==id; }
 public void playPauseMessage(Tdlib t,TdApi.Message m,PlayListBuilder builder) {
  starts++; current=m; state=1; PlayList list=builder.buildPlayList(m);
  user=list.profileMusicUserId; offset=list.profileMusicOffset; end=list.newEndReached;
 }
'''
    player += '\n'.join(extract_block(PLAYER.read_text(), signature) for signature in [
      'public static class PlayList {', 'public interface PlayListBuilder {',
    ]) + '\n}'
    fixtures['org/thunderdog/challegram/player/TGPlayerController.java'] = player
    with tempfile.TemporaryDirectory(prefix='profile-music-opening-tests-') as directory:
      work = Path(directory)
      sources = []
      for name, content in fixtures.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
      sources.append(str(ROOT / 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java'))
      classes = work / 'classes'
      result = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *sources],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([str(Path(javac).with_name('java')), '-cp', str(classes),
        'org.thunderdog.challegram.widget.ProfileMusicView'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == '__main__':
  unittest.main()
