package org.thunderdog.challegram.telegram;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.thunderdog.challegram.config.ClientIdentity;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.NotesBackup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import me.vkryl.leveldb.LevelDB;
import tgx.td.ChatId;

/** Private, persistent settings storage, deliberately independent of TDLib's media cache. */
public final class TdlibNotes {
  private static final String ENABLED_KEY = ClientIdentity.notesPrefix() + "enabled";
  private final LongSupplier userId;
  private final BooleanSupplier testDc;
  private final Storage storage;
  private final File exportDirectory;
  private final String accountPrefix;
  private int generation;

  TdlibNotes (Tdlib tdlib) {
    this(tdlib.id(), () -> tdlib.myUserId(true), () -> tdlib.account().isDebug(),
      new SettingsStorage(), UI.getAppContext().getCacheDir());
  }

  TdlibNotes (int accountId, LongSupplier userId, BooleanSupplier testDc, Storage storage, File cache) {
    this.userId = userId;
    this.testDc = testDc;
    this.storage = storage;
    accountPrefix = ClientIdentity.notesPrefix() + "account_" + accountId + "_";
    exportDirectory = new File(cache, "notes-export-" + accountId);
  }

  interface Storage {
    String get (String key);
    Map<String, String> entries (String prefix);
    void write (Map<String, String> changes);
    void clear (String prefix);
  }

  private static final class SettingsStorage implements Storage {
    @Override
    public String get (String key) {
      return Settings.instance().getString(key, "");
    }

    @Override
    public Map<String, String> entries (String prefix) {
      Map<String, String> entries = new LinkedHashMap<>();
      for (LevelDB.Entry entry : Settings.instance().pmc().find(prefix)) {
        entries.put(entry.key(), entry.asString());
      }
      return entries;
    }

    @Override
    public void write (Map<String, String> changes) {
      SharedPreferences.Editor editor = Settings.instance().edit();
      for (Map.Entry<String, String> entry : changes.entrySet()) {
        if (entry.getValue() == null) {
          editor.remove(entry.getKey());
        } else {
          editor.putString(entry.getKey(), entry.getValue());
        }
      }
      if (!editor.commit()) {
        throw new IllegalStateException("Cannot save notes");
      }
    }

    @Override
    public void clear (String prefix) {
      Settings.instance().removeByPrefix(prefix, null);
    }
  }

  public static boolean isEnabled () {
    return Settings.instance().getBoolean(ENABLED_KEY, false);
  }

  public static void setEnabled (boolean enabled) {
    Settings.instance().putBoolean(ENABLED_KEY, enabled);
  }

  // Bind asynchronous work to both the Telegram identity and this local account's lifetime.
  public static final class Session {
    public final long userId;
    public final boolean testDc;
    private final int generation;
    private final TdlibNotes owner;

    private Session (TdlibNotes owner, long userId, boolean testDc, int generation) {
      this.owner = owner;
      this.userId = userId;
      this.testDc = testDc;
      this.generation = generation;
    }
  }

  public synchronized Session session () {
    long userId = this.userId.getAsLong();
    if (userId == 0) {
      throw new IllegalStateException("Notes require an account");
    }
    return new Session(this, userId, testDc.getAsBoolean(), generation);
  }

  public synchronized boolean isCurrent (Session session) {
    return session.owner == this && session.generation == generation && session.userId == userId.getAsLong() &&
      session.testDc == testDc.getAsBoolean();
  }

  private String prefix (Session session) {
    if (!isCurrent(session)) {
      throw new IllegalStateException("Notes account changed");
    }
    return accountPrefix + (session.testDc ? "test_" : "live_") + session.userId + "_";
  }

  private long resolve (String prefix, long chatId) {
    if (!NotesBackup.isValidChatId(chatId)) {
      throw new IllegalArgumentException("Invalid note identity");
    }
    String migratedTo = ChatId.isBasicGroup(chatId) ? storage.get(prefix + "migration_" + chatId) : "";
    return migratedTo.isEmpty() ? chatId : Long.parseLong(migratedTo);
  }

  public synchronized String get (long chatId) {
    if (userId.getAsLong() == 0) {
      return "";
    }
    String prefix = prefix(session());
    return clippedRead(prefix + "note_" + resolve(prefix, chatId));
  }

  private String clippedRead (String key) {
    String original = storage.get(key);
    String clipped = NotesBackup.truncate(original);
    if (!clipped.equals(original)) {
      storage.write(Collections.singletonMap(key, clipped));
    }
    return clipped;
  }

  public synchronized void set (Session session, long chatId, String text) {
    String prefix = prefix(session);
    long resolvedId = resolve(prefix, chatId);
    String key = prefix + "note_" + resolvedId;
    text = NotesBackup.truncate(text);
    storage.write(Collections.singletonMap(key, text.trim().isEmpty() ? null : text));
  }

  /** Remove a migrated local snapshot only after the server read-back confirms it. */
  public synchronized boolean removeMigrated (Session session, long chatId, String expected) {
    if (!isCurrent(session)) {
      return false;
    }
    String key = prefix(session) + "note_" + resolve(prefix(session), chatId);
    if (!storage.get(key).equals(expected)) {
      return false;
    }
    storage.write(Collections.singletonMap(key, null));
    return true;
  }

  /** Invalidate pending work without deleting notes when authorization/identity changes. */
  synchronized void invalidateSession () {
    generation++;
  }

  public synchronized NotesBackup exportNotes (Session session) {
    String prefix = prefix(session) + "note_";
    Map<Long, String> notes = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : storage.entries(prefix).entrySet()) {
      notes.put(Long.parseLong(entry.getKey().substring(prefix.length())), clippedRead(entry.getKey()));
    }
    return new NotesBackup(session.userId, session.testDc, notes);
  }

  private void clearExports () {
    File[] files = exportDirectory.listFiles();
    if (files != null) {
      for (File file : files) {
        file.delete();
      }
    }
  }

  public synchronized File exportFile (Session session) throws IOException, JSONException {
    NotesBackup backup = exportNotes(session);
    if (backup.notes.isEmpty()) {
      clearExports();
      return null;
    }
    byte[] data = backup.encode();
    File directory = exportDirectory;
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException("Cannot create notes export directory");
    }
    clearExports();
    // A fresh URI prevents an earlier share grant from exposing later exports.
    File file = File.createTempFile("client-notes-", ".json", directory);
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(data);
    } catch (IOException e) {
      file.delete();
      throw e;
    }
    return file;
  }

  public synchronized int importNotes (Session session, NotesBackup backup) {
    String prefix = prefix(session);
    if (backup.userId != session.userId || backup.testDc != session.testDc) {
      throw new IllegalArgumentException("Notes belong to another account");
    }
    Map<String, String> additions = new LinkedHashMap<>();
    for (Map.Entry<Long, String> entry : backup.notes.entrySet()) {
      String key = prefix + "note_" + resolve(prefix, entry.getKey());
      if (storage.get(key).isEmpty() && !additions.containsKey(key)) {
        additions.put(key, NotesBackup.truncate(entry.getValue()));
      }
    }
    // Validate the entire file before starting this single atomic write batch.
    storage.write(additions);
    return additions.size();
  }

  public synchronized void migrateGroup (long basicGroupId, long supergroupId) {
    if (basicGroupId == 0 || supergroupId == 0 || userId.getAsLong() == 0) {
      return;
    }
    String prefix = prefix(session());
    long fromId = ChatId.fromBasicGroupId(basicGroupId);
    long toId = ChatId.fromSupergroupId(supergroupId);
    String fromKey = prefix + "note_" + fromId;
    String toKey = prefix + "note_" + toId;
    String from = clippedRead(fromKey);
    String to = clippedRead(toKey);
    String migrationKey = prefix + "migration_" + fromId;
    if (from.isEmpty() && storage.get(migrationKey).equals(Long.toString(toId))) {
      return;
    }
    Map<String, String> changes = new LinkedHashMap<>();
    changes.put(migrationKey, Long.toString(toId));
    if (!from.isEmpty()) {
      changes.put(toKey, NotesBackup.truncate(to.isEmpty() || to.equals(from) ? from : from + "\n\n" + to));
      changes.put(fromKey, null);
    }
    storage.write(changes);
  }

  /** Also called on logout, including when an account slot is later reused. */
  public synchronized void clear () {
    generation++;
    storage.clear(accountPrefix);
    clearExports();
  }
}
