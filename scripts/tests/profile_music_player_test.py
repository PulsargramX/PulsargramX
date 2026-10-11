"""Exercise production profile playlist loading with isolated playback/transport doubles."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.profile_music_backend_test import ROOT, BACKEND, STUBS

PLAYER = ROOT / 'app/src/main/java/org/thunderdog/challegram/player/TGPlayerController.java'


def extract_block(source, signature):
  start = source.index(signature)
  opening = source.index('{', start)
  depth = 1
  end = opening + 1
  while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
  return source[start:end]


# Only the audio engine, UI listeners and message-list primitives are doubled. The native
# pagination, cancellation, source matching, refresh and PlayList logic comes from production.
HARNESS = r'''package org.thunderdog.challegram.player;
import androidx.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibProfileMusic;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.tool.UI;
import me.vkryl.core.StringUtils;
import java.util.*;
public class TGPlayerController {
 Tdlib tdlib; TdApi.Message message;
 ArrayList<TdApi.Message> messageList=new ArrayList<>();
 int playState, playListFlags, messageListContextId, messageListStateFlags, profileMusicOffset;
 long profileMusicUserId, playlistChatId; String playlistSearchQuery;
 Object trackListChangeListeners=new Object(); int resets, starts, stops, lastForce;
 int indexOfMessage(TdApi.Message track) {
  for(int i=0;i<messageList.size();i++) if(compareTracks(messageList.get(i),track)) return i;
  return -1;
 }
 int indexOfCurrentMessage() { return indexOfMessage(message); }
 boolean canRestoreImpl(TdApi.Message track) { return false; }
 void ensureStackAvailability(int reason) { lastForce=reason; prepareProfileMusic(false,reason); }
 void clearMessagesImpl() { messageList.clear(); }
 void addMessagesImpl(List<TdApi.Message> tracks) { messageList.addAll(tracks); }
 void addMessages(int context,List<TdApi.Message> tracks,boolean areNew) {
  if(context!=messageListContextId) return;
  messageListStateFlags &= ~LIST_STATE_LOADING_NEWER;
  messageList.addAll(tracks);
  if(tracks.isEmpty()) messageListStateFlags |= LIST_STATE_LOADED_NEW;
 }
 int getPlaybackSessionFlags() { return playListFlags; }
 void notifyTrackListLoadStateChanged(Object listeners) {}
 void notifyTrackListReset(Object listeners,Tdlib source,TdApi.Message current,int index,
     List<TdApi.Message> tracks,long chat,int flags,int state) { resets++; }
 void stopPlaybackImpl(boolean byUser) { stops++; message=null; messageList.clear(); playState=STATE_NONE; cancelStackLoader(); }
 void playPauseCurrent(boolean byUser) { playState=playState==STATE_PAUSED ? STATE_PLAYING : STATE_PAUSED; }
 void playPauseMessageImpl(TdApi.Message track,boolean byUser,boolean next,Tdlib source,PlayListBuilder builder) {
  starts++; PlayList playlist=builder.buildPlayList(track); tdlib=source; message=track;
  messageList=new ArrayList<>(playlist.messages); profileMusicUserId=playlist.profileMusicUserId;
  profileMusicOffset=playlist.profileMusicOffset; playlistSearchQuery=playlist.searchQuery;
  messageListStateFlags=LIST_STATE_LOADED_OLD | (playlist.newEndReached ? LIST_STATE_LOADED_NEW : 0);
  playListFlags=playlist.playListFlags; playState=STATE_PLAYING;
 }
 static void expect(boolean value,String text) { if(!value) throw new AssertionError(text); }
 static TdApi.Audio audio(int id) {
  TdApi.Audio a=new TdApi.Audio(); a.title="Song "+id; a.performer="Artist";
  a.audio=new TdApi.File(); a.audio.id=id; return a;
 }
 static TGPlayerController player(Tdlib t,long userId,int... ids) {
  TGPlayerController p=new TGPlayerController(); p.tdlib=t; p.profileMusicUserId=userId;
  for(int id:ids) p.messageList.add(t.profileMusic().message(userId,audio(id)));
  p.message=p.messageList.get(0); p.profileMusicOffset=ids.length;
  p.playState=STATE_PLAYING; p.messageListStateFlags=LIST_STATE_LOADED_OLD; return p;
 }
 static void reply(Tdlib t,int total,int... ids) {
  TdApi.Audio[] songs=new TdApi.Audio[ids.length];
  for(int i=0;i<ids.length;i++) songs[i]=audio(ids[i]);
  t.reply(t.take(TdApi.GetUserProfileAudios.class),new TdApi.Audios(total,songs),null);
 }
 static void paging() {
  Tdlib t=new Tdlib(); TGPlayerController p=player(t,200,1,2);
  p.prepareProfileMusic(false,FORCE_REASON_NONE);
  expect(t.requests.isEmpty(),"Do not load the native end when only the beginning is near");
  p.playListFlags=PLAYLIST_FLAG_REVERSE; p.requestMoreTracks();
  expect(p.lastForce==FORCE_REASON_NEWER,"Reverse playback still uses forward native offsets");
  expect(((TdApi.GetUserProfileAudios)t.requests.get(0).function).offset==2,"Continue at the raw offset");
  expect(!p.canRequestMoreTrackReverse(),"Prevent overlapping page requests");
  p.prepareProfileMusic(true,FORCE_REASON_NEWER);
  expect(t.requests.size()==1,"One page request at a time");
  reply(t,6,2,2); // A changing playlist may return an entirely overlapping page.
  expect(((TdApi.GetUserProfileAudios)t.requests.get(0).function).offset==4,"Skip duplicates without prematurely ending the playlist");
  reply(t,6,3,3);
  expect(p.messageList.size()==3 && p.profileMusicOffset==6,"Deduplicate both existing songs and repeated songs in a page");
  expect(p.isTrackListEndReached() && !p.canRequestMoreTrackReverse(),"Native end is independent of reverse playback");
 }
 static void pagingErrorsAndCancellation() {
  Tdlib t=new Tdlib(); TGPlayerController p=player(t,200,1);
  p.prepareProfileMusic(true,FORCE_REASON_NONE);
  t.reply(t.take(TdApi.GetUserProfileAudios.class),null,new TdApi.Error(500,"OFFLINE"));
  expect(p.canRequestMoreTrackReverse() && !p.isTrackListEndReached(),"A failed page stays retryable");
  p.prepareProfileMusic(true,FORCE_REASON_NONE); Tdlib.Request stale=t.take(TdApi.GetUserProfileAudios.class);
  p.cancelStackLoader(); t.reply(stale,new TdApi.Audios(2,new TdApi.Audio[]{audio(2)}),null);
  expect(p.messageList.size()==1 && p.profileMusicOffset==1,"Cancelled page cannot modify the new queue");
  p.prepareProfileMusic(true,FORCE_REASON_NONE); stale=t.take(TdApi.GetUserProfileAudios.class);
  p.tdlib=new Tdlib(); t.reply(stale,new TdApi.Audios(2,new TdApi.Audio[]{audio(2)}),null);
  expect(p.messageList.size()==1,"Another account cannot receive stale profile songs");
 }
 static void reorderAndDelete() {
  Tdlib t=new Tdlib(); TGPlayerController p=player(t,100,1,2,3);
  p.message=p.messageList.get(1); p.playState=STATE_PAUSED; p.playListFlags=PLAYLIST_FLAG_REVERSE;
  p.refreshProfileMusic(t); reply(t,3,3,1,2);
  expect(p.starts==0 && p.resets==1 && p.indexOfCurrentMessage()==2 && p.playState==STATE_PAUSED,"Reorder preserves the current song and pause state");
  expect(p.message==p.messageList.get(2),"The playing track uses the refreshed file metadata");
  p.refreshProfileMusic(t); reply(t,2,3,1);
  expect(p.starts==1 && TD.getFileId(p.message)==1 && p.playState==STATE_PAUSED,"Deleting the playing song selects a surviving neighbor without resuming");
  expect(p.playListFlags==PLAYLIST_FLAG_REVERSE,"Deletion retains reverse playback");
  p.refreshProfileMusic(t); reply(t,0);
  expect(p.stops==1 && p.message==null,"Removing the last song stops playback");
 }
 static void refreshBeyondLoadedPage() {
  Tdlib t=new Tdlib(); TGPlayerController p=player(t,100,1);
  p.refreshProfileMusic(t); int[] first=new int[100];
  for(int i=0;i<first.length;i++) first[i]=i+2;
  reply(t,101,first);
  expect(p.starts==0 && t.count(TdApi.GetUserProfileAudios.class)==1,"An unloaded playing song may have moved, not been deleted");
  expect(((TdApi.GetUserProfileAudios)t.requests.get(0).function).offset==100,"Refresh follows native pagination");
  reply(t,101,1);
  expect(p.starts==0 && p.indexOfCurrentMessage()==100 && p.messageList.size()==101,"Preserve playback when reordered beyond the previously loaded page");
  p.refreshProfileMusic(t);
  t.reply(t.take(TdApi.GetUserProfileAudios.class),null,new TdApi.Error(500,"OFFLINE"));
  expect(p.messageList.size()==101 && p.canRequestMoreTrackReverse(),"Refresh failure keeps the playing queue and permits retry");
 }
 static void queueBoundaries() {
  Tdlib t=new Tdlib(); TGPlayerController p=player(t,100,1);
  TdApi.Message chatSong=new TdApi.Message(); chatSong.chatId=50; chatSong.id=200;
  chatSong.content=new TdApi.MessageAudio(audio(2),null);
  expect(p.canAddToPlayListImpl(t,chatSong,0,-1)==ADD_MODE_NONE,"Chat queue actions cannot insert unsaved songs into a server profile playlist");
  p.profileMusicUserId=0;
  expect(p.canAddToPlayListImpl(t,chatSong,0,-1)==ADD_MODE_INSERT,"Ordinary Telegram X custom queues remain editable");
 }
 static void sourceAndFilteredRefresh() {
  Tdlib t=new Tdlib(); TGPlayerController p=player(t,200,1,2);
  p.playlistSearchQuery="SONG";
  expect(p.isProfileMusicSource(t,200,"SONG") && !p.isProfileMusicSource(t,200,null),"Filtered and full profile playlists are different sources");
  expect(p.getProfileMusicUserId(new Tdlib())==0,"Playback source belongs to its account");
  p.refreshProfileMusic(t); expect(t.requests.isEmpty(),"Own-profile mutation must not replace a foreign profile queue");
  p.refreshProfileMusic(t,200); reply(t,2,2,1);
  expect(p.resets==1 && p.starts==0 && p.isTrackListEndReached(),"Refreshing filtered or foreign profile songs preserves playback");
  p.refreshProfileMusic(t,200); Tdlib.Request stale=t.take(TdApi.GetUserProfileAudios.class);
  p.cancelStackLoader(); t.reply(stale,new TdApi.Audios(0,new TdApi.Audio[0]),null);
  expect(p.stops==0,"Obsolete refresh cannot stop the new session");
 }
 public static void main(String[] args) {
  paging(); pagingErrorsAndCancellation(); reorderAndDelete();
  refreshBeyondLoadedPage(); sourceAndFilteredRefresh(); queueBoundaries();
 }
'''


class ProfileMusicPlayerTest(unittest.TestCase):
  def test_profile_playlist_contracts(self):
    javac = shutil.which('javac')
    self.assertIsNotNone(javac, 'A JDK is required for playlist contract tests')
    source = PLAYER.read_text()
    signatures = [
      'public static boolean compareTracks (TdApi.Message a, TdApi.Message b)',
      'public static boolean compareTrackFiles (TdApi.File a, TdApi.File b)',
      'private void cancelStackLoader ()',
      'private int canAddToPlayListImpl (',
      'public void requestMoreTracks ()',
      'public boolean canRequestMoreTrackReverse ()',
      'public synchronized long getProfileMusicUserId (Tdlib source)',
      'public synchronized boolean isProfileMusicSource (Tdlib source, long userId, String query)',
      'public synchronized void refreshProfileMusic (Tdlib source)',
      'public synchronized void refreshProfileMusic (Tdlib source, long userId)',
      'private void loadProfileMusicRefresh (',
      'private synchronized void finishProfileMusicRefreshError (',
      'private synchronized void applyProfileMusicRefresh (',
      'private void prepareProfileMusic (',
      'public boolean isTrackListEndReached ()',
      'public static class PlayList {',
      'public interface PlayListBuilder {',
    ]
    constants = re.findall(r'(?:public|private) static final int (?:STATE_(?:NONE|PAUSED|PLAYING)|'
      r'LIST_STATE_\w+|FORCE_REASON_\w+|ADD_MODE_\w+|PLAYLIST_FLAG_\w+|PLAYLIST_FLAGS_MASK) = [^;]+;', source)
    harness = HARNESS + '\n'.join(constants + [extract_block(source, s) for s in signatures]) + '\n}'
    fixtures = dict(STUBS)
    fixtures['org/thunderdog/challegram/player/TGPlayerController.java'] = harness
    fixtures['me/vkryl/core/StringUtils.java'] = '''package me.vkryl.core;
public class StringUtils {
 public static boolean isEmpty(String s) { return s==null || s.isEmpty(); }
 public static boolean equalsOrBothEmpty(String a,String b) { return isEmpty(a) && isEmpty(b) || java.util.Objects.equals(a,b); }
}'''
    fixtures['org/thunderdog/challegram/telegram/Tdlib.java'] = fixtures[
      'org/thunderdog/challegram/telegram/Tdlib.java'].replace(
        ' public int refreshes;', ' public int refreshes; private TdlibProfileMusic music;\n'
        ' public TdlibProfileMusic profileMusic() { if(music==null) music=new TdlibProfileMusic(this); return music; }')
    fixtures['org/thunderdog/challegram/data/TD.java'] = fixtures[
      'org/thunderdog/challegram/data/TD.java'].replace(' public static int saves;',
        ' public static int saves;\n public static TdApi.File getFile(TdApi.Message m) { return ((TdApi.MessageAudio)m.content).audio.audio; }\n'
        ' public static boolean isSelfDestructTypeImmediately(TdApi.Message m) { return m.selfDestructType instanceof TdApi.MessageSelfDestructTypeImmediately; }')
    r = 'package org.thunderdog.challegram; public class R {\n'
    for category in ['string', 'id', 'drawable']:
      names = sorted(set(re.findall(r'R\.' + category + r'\.(\w+)', BACKEND.read_text())))
      r += 'public static class ' + category + ' {\n'
      r += ''.join(f'public static final int {name}={i};\n' for i, name in enumerate(names)) + '}\n'
    fixtures['org/thunderdog/challegram/R.java'] = r + '}\n'
    with tempfile.TemporaryDirectory(prefix='profile-music-player-tests-') as directory:
      work = Path(directory)
      sources = []
      for name, content in fixtures.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
      sources += [str(BACKEND), str(ROOT / 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java'),
        str(ROOT / 'app/src/main/java/org/thunderdog/challegram/telegram/CleanupStartupDelegate.java')]
      classes = work / 'classes'
      result = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *sources],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([str(Path(javac).with_name('java')), '-cp', str(classes),
        'org.thunderdog.challegram.player.TGPlayerController'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == '__main__':
  unittest.main()
