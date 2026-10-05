#!/usr/bin/env python3
"""Portable real-backend JUnit runner; no Gradle, Android SDK or native TDLib.

Only Android/settings/UI and transport are inert fixtures. TdApi.java,
NotesBackup, TdlibNotes and TdlibContactNotes are compiled from this checkout.
JDK and Maven test jars live in temporary storage.
"""
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BASE = Path(os.environ.get('TMPDIR', '/tmp')) / 'client-note-tests'
WORK = BASE / 'cloud-backend'
JDK = Path(os.environ.get('NOTE_TEST_JDK', str(BASE / 'jdk-17.0.20.1+1')))
STUBS = {
'tgx/td/ChatId.java': '''package tgx.td;
// Focused Java translation of checked-in vkryl/td/src/main/kotlin/tgx/td/ChatId.kt.
public final class ChatId {
 public static final long MAX_CHANNEL_ID=1000000000000L-(1L<<31);
 public static final long MIN_MONOFORUM_CHANNEL_ID=1000000000000L+(1L<<31)+1;
 public static final long MAX_MONOFORUM_CHANNEL_ID=3000000000000L;
 public static boolean isPrivate(long id){return id>=1&&id<=(1L<<40)-1;}
 public static boolean isBasicGroup(long id){return id<0&&id>=-999999999999L;}
 public static long toSupergroupId(long id){
  if(id>=0||isBasicGroup(id))return 0;
  if(id>=-1000000000000L-MAX_CHANNEL_ID&&id!=-1000000000000L)return -1000000000000L-id;
  if(id>=-2000000000000L+Integer.MIN_VALUE&&id!=-2000000000000L)return 0;
  return id>=-1000000000000L-MAX_MONOFORUM_CHANNEL_ID?-1000000000000L-id:0;
 }
 public static long fromBasicGroupId(long id){return -id;}
 public static long fromSupergroupId(long id){return -1000000000000L-id;}
 public static long fromSecretChatId(int id){return -2000000000000L+id;}
}''',
'androidx/annotation/IntDef.java': 'package androidx.annotation; public @interface IntDef {long[] value() default {}; boolean flag() default false;}',
'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences { interface Editor { Editor remove(String k); Editor putString(String k,String v); boolean commit(); } }''',
'org/thunderdog/challegram/tool/UI.java': '''package org.thunderdog.challegram.tool; public final class UI { public static Context getAppContext(){return new Context();} public static class Context {public java.io.File getCacheDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}} }''',
'me/vkryl/leveldb/LevelDB.java': '''package me.vkryl.leveldb; public class LevelDB { public static class Entry {public String key(){return "";} public String asString(){return "";}} public java.util.List<Entry> find(String p){return java.util.Collections.emptyList();}}''',
'org/thunderdog/challegram/unsorted/Settings.java': '''package org.thunderdog.challegram.unsorted; public class Settings {public static Settings instance(){return new Settings();} public String getString(String k,String d){return d;} public boolean getBoolean(String k,boolean d){return d;} public void putBoolean(String k,boolean v){} public me.vkryl.leveldb.LevelDB pmc(){return new me.vkryl.leveldb.LevelDB();} public android.content.SharedPreferences.Editor edit(){throw new UnsupportedOperationException("Inert settings fixture");} public void removeByPrefix(String p,Object o){} }''',
'org/drinkless/tdlib/Client.java': '''package org.drinkless.tdlib; public class Client {public interface ResultHandler {void onResult(TdApi.Object o);}}''',
'org/thunderdog/challegram/telegram/Tdlib.java': '''package org.thunderdog.challegram.telegram; import org.drinkless.tdlib.*; public class Tdlib {public static class Status {public static final int READY=1;} public int id(){return 0;} public long myUserId(boolean c){return 100;} public long myUserId(){return 100;} public Account account(){return new Account();} public static class Account {public boolean isDebug(){return false;}} public TdlibNotes notes(){throw new UnsupportedOperationException();} public Cache cache(){return new Cache();} public static class Cache {public TdApi.User user(long id){return null;}} public int authorizationStatus(){return Status.READY;} public boolean isConnected(){return true;} public interface ResultHandler<T extends TdApi.Object> {void onResult(T result,TdApi.Error error);} public <T extends TdApi.Object> void send(TdApi.Function<T> f, ResultHandler<T> h){throw new UnsupportedOperationException("Inert transport fixture");}}''',
}

def verify_hooks():
    tdlib = (ROOT / 'app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java').read_text()
    cache = (ROOT / 'app/src/main/java/org/thunderdog/challegram/telegram/TdlibCache.java').read_text()
    assert 'public TdlibContactNotes contactNotes ()' in tdlib, 'Missing public cloud manager accessor'
    assert 'this.contactNotesManager = new TdlibContactNotes(this);' in tdlib
    assert 'contactNotes().onStateChanged();' in tdlib, 'Missing reconnect hook'
    assert 'contactNotes().onIdentityChanged();' in tdlib, 'Missing authorization guard'
    assert 'contactNotes().migratePending();' in tdlib, 'Missing startup migration'
    assert 'tdlib.contactNotes().onUserUpdated(newUser);' in cache, 'Missing contact-update migration'
    assert 'tdlib.contactNotes().onIdentityChanged();' in cache, 'Missing my-user identity guard'
    # No manager callback is allowed inside a cache lock (manager reads cache itself).
    position = 0
    while True:
        position = cache.find('synchronized (dataLock)', position)
        if position < 0: break
        start = cache.index('{', position)
        depth = 1
        end = start + 1
        while depth:
            depth += (cache[end] == '{') - (cache[end] == '}')
            end += 1
        assert 'contactNotes()' not in cache[start:end], 'Manager hook under cache lock'
        position = end
    print('Cloud manager lifecycle/accessor source checks passed')

def main():
    WORK.mkdir(parents=True, exist_ok=True)
    jars = []
    for name, coordinate in [('junit.jar','junit/junit/4.13.2/junit-4.13.2.jar'), ('hamcrest.jar','org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'), ('json.jar','org/json/json/20240303/json-20240303.jar')]:
        dest = WORK / name
        if not dest.exists():
            urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/' + coordinate, dest)
        jars.append(str(dest))
    stubs = []
    for name, text in STUBS.items():
        dest = WORK / 'stubs' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text)
        stubs.append(str(dest))
    sources = ['app/src/public/java/org/thunderdog/challegram/config/ClientIdentity.java', 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java', 'app/src/main/java/org/thunderdog/challegram/util/NotesBackup.java', 'app/src/main/java/org/thunderdog/challegram/telegram/TdlibNotes.java', 'app/src/test/java/org/thunderdog/challegram/telegram/TdlibNotesTest.java']
    for name in ['app/src/main/java/org/thunderdog/challegram/telegram/TdlibContactNotes.java', 'app/src/test/java/org/thunderdog/challegram/telegram/TdlibContactNotesTest.java']:
        if (ROOT / name).exists(): sources.append(name)
    classes = WORK / 'classes'
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(jars)
    subprocess.run([str(JDK/'bin/javac'), '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes), *stubs, *[str(ROOT / s) for s in sources]], check=True)
    tests = ['org.thunderdog.challegram.telegram.TdlibNotesTest']
    if (ROOT / sources[-1]).name == 'TdlibContactNotesTest.java': tests.append('org.thunderdog.challegram.telegram.TdlibContactNotesTest')
    subprocess.run([str(JDK/'bin/java'), '-Djava.io.tmpdir=' + str(WORK), '-cp', str(classes)+os.pathsep+cp, 'org.junit.runner.JUnitCore', *tests], check=True)

    verify_hooks()

if __name__ == '__main__': main()
