package org.thunderdog.challegram.telegram;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.thunderdog.challegram.util.NotesBackup;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import tgx.td.ChatId;

import static org.junit.Assert.*;

public class TdlibNotesTest {
  @Rule public TemporaryFolder cache = new TemporaryFolder();

  private final MemoryStorage storage = new MemoryStorage();

  private TdlibNotes account (int slot, long userId) {
    return new TdlibNotes(slot, () -> userId, () -> false, storage, cache.getRoot());
  }

  @Test
  public void isolatesAccountsAndPeerTypesAndSurvivesReopening () {
    TdlibNotes first = account(1, 100);
    TdlibNotes second = account(10, 200);
    first.set(first.session(), 7, "Person");
    first.set(first.session(), -7, "Group");
    first.set(first.session(), ChatId.fromSupergroupId(7), "Channel");
    second.set(second.session(), 7, "Other account");

    assertEquals("Person", account(1, 100).get(7));
    assertEquals("Group", first.get(-7));
    assertEquals("Channel", first.get(ChatId.fromSupergroupId(7)));
    assertEquals("Other account", second.get(7));
    assertEquals(3, first.exportNotes(first.session()).notes.size());
    assertEquals(Collections.singletonMap(7L, "Other account"),
      second.exportNotes(second.session()).notes);
    first.clear();
    assertEquals("", first.get(7));
    assertEquals("Other account", second.get(7));
  }

  @Test
  public void neverReusesAnotherIdentityOrServerInTheSameSlot () {
    TdlibNotes original = account(0, 100);
    original.set(original.session(), 7, "Private");
    assertEquals("", account(0, 200).get(7));
    TdlibNotes testAccount = new TdlibNotes(0, () -> 100, () -> true, storage, cache.getRoot());
    assertEquals("", testAccount.get(7));
    assertFalse(account(0, 100).isCurrent(original.session()));
  }

  @Test
  public void rejectsPendingEditsAndImportsAfterErasureOrIdentityChange () {
    AtomicLong user = new AtomicLong(100);
    TdlibNotes notes = new TdlibNotes(0, user::get, () -> false, storage, cache.getRoot());
    TdlibNotes.Session session = notes.session();
    NotesBackup backup = new NotesBackup(100, false, Collections.singletonMap(7L, "Old"));
    notes.clear();
    assertThrows(IllegalStateException.class, () -> notes.set(session, 7, "Stale edit"));
    assertThrows(IllegalStateException.class, () -> notes.importNotes(session, backup));
    TdlibNotes.Session next = notes.session();
    user.set(200);
    assertThrows(IllegalStateException.class, () -> notes.set(next, 7, "Wrong account"));
    assertTrue(notes.exportNotes(notes.session()).notes.isEmpty());
  }

  @Test
  public void migratesGroupsAndResolvesOldBackupsAndEditors () {
    TdlibNotes notes = account(0, 100);
    long newId = ChatId.fromSupergroupId(88);
    notes.set(notes.session(), -42, "Before upgrade");
    notes.migrateGroup(42, 88);
    notes.migrateGroup(42, 88);
    assertEquals("Before upgrade", notes.get(newId));
    assertEquals(notes.get(newId), notes.get(-42));
    assertFalse(notes.exportNotes(notes.session()).notes.containsKey(-42L));
    assertEquals(0, notes.importNotes(notes.session(),
      new NotesBackup(100, false, Collections.singletonMap(-42L, "Older backup"))));
    notes.set(notes.session(), -42, "Edited from old profile");
    assertEquals("Edited from old profile", notes.get(newId));
    notes.set(notes.session(), newId, "");
    assertEquals("", notes.get(-42));
    assertEquals(1, notes.importNotes(notes.session(),
      new NotesBackup(100, false, Collections.singletonMap(-42L, "Restored"))));
    assertEquals("Restored", notes.get(newId));
  }

  @Test
  public void clipsMergedNotesIfMigrationIsDiscoveredLate () throws Exception {
    TdlibNotes notes = account(0, 100);
    long newId = ChatId.fromSupergroupId(88);
    String old = String.join("", Collections.nCopies(100, "a"));
    String newer = String.join("", Collections.nCopies(100, "🦊"));
    notes.set(notes.session(), -42, old);
    notes.set(notes.session(), newId, newer);
    notes.migrateGroup(42, 88);
    assertEquals(NotesBackup.truncate(old + "\n\n" + newer), notes.get(newId));
    notes.migrateGroup(42, 88);
    assertEquals(NotesBackup.truncate(old + "\n\n" + newer), notes.get(newId));
    NotesBackup restored = NotesBackup.read(new ByteArrayInputStream(notes.exportNotes(notes.session()).encode()));
    assertEquals(notes.get(newId), restored.notes.get(newId));
    notes.set(notes.session(), newId, notes.get(newId));
  }

  @Test
  public void importsMissingNotesWithoutReplacingExistingOnes () {
    TdlibNotes notes = account(0, 100);
    notes.set(notes.session(), 7, "Current");
    Map<Long, String> imported = new LinkedHashMap<>();
    imported.put(7L, "Old");
    imported.put(-42L, "Added");
    assertEquals(1, notes.importNotes(notes.session(), new NotesBackup(100, false, imported)));
    assertEquals("Current", notes.get(7));
    assertEquals("Added", notes.get(-42));
    assertThrows(IllegalArgumentException.class,
      () -> notes.importNotes(notes.session(), new NotesBackup(200, false, imported)));
    assertThrows(IllegalArgumentException.class,
      () -> notes.importNotes(notes.session(), new NotesBackup(100, true, imported)));
    assertEquals(2, notes.exportNotes(notes.session()).notes.size());
  }

  @Test
  public void countsUnicodeCharactersAndDeletesBlankNotes () {
    TdlibNotes notes = account(0, 100);
    String limit = String.join("", Collections.nCopies(128, "🦊"));
    notes.set(notes.session(), 7, limit);
    assertEquals(limit, notes.get(7));
    notes.set(notes.session(), 7, limit + "x");
    assertEquals(limit, notes.get(7));
    notes.set(notes.session(), 7, " \n\t");
    assertTrue(notes.exportNotes(notes.session()).notes.isEmpty());
  }

  @Test
  public void clearingExportCacheDoesNotEraseNotesAndErasureClearsExports () throws Exception {
    TdlibNotes first = account(1, 100);
    TdlibNotes second = account(10, 200);
    first.set(first.session(), 7, "Private");
    second.set(second.session(), 7, "Other");
    File oldExport = first.exportFile(first.session());
    File newExport = first.exportFile(first.session());
    File otherExport = second.exportFile(second.session());
    assertFalse(oldExport.exists());
    assertNotEquals(oldExport, newExport);
    assertTrue(newExport.delete());
    assertEquals("Private", first.get(7));
    File export = first.exportFile(first.session());
    first.clear();
    assertFalse(export.exists());
    assertTrue(otherExport.exists());
    assertEquals("Other", second.get(7));
  }

  @Test
  public void clipsLegacyReadsAndExportsOnDisk () {
    TdlibNotes notes = account(0, 100);
    String key = "client_notes_account_0_live_100_note_7";
    String longText = "🦊".repeat(200);
    storage.data.put(key, longText);
    assertEquals("🦊".repeat(128), notes.get(7));
    assertEquals("🦊".repeat(128), storage.data.get(key));
    storage.data.put(key, longText);
    assertEquals("🦊".repeat(128), notes.exportNotes(notes.session()).notes.get(7L));
    assertEquals("🦊".repeat(128), storage.data.get(key));
  }

  @Test
  public void removesOnlyTheConfirmedMigrationSnapshot () {
    TdlibNotes notes = account(0, 100);
    TdlibNotes.Session session = notes.session();
    notes.set(session, 7, "Old");
    notes.set(session, 7, "New");
    assertFalse(notes.removeMigrated(session, 7, "Old"));
    assertEquals("New", notes.get(7));
    assertTrue(notes.removeMigrated(session, 7, "New"));
    assertEquals("", notes.get(7));
    notes.set(session, 7, "Kept");
    notes.invalidateSession();
    assertFalse(notes.removeMigrated(session, 7, "Kept"));
    assertEquals("Kept", notes.get(7));
  }

  @Test
  public void importedLegacyNotesAndOldLengthOverridesAlwaysClipTo128 () {
    TdlibNotes notes = account(0, 100);
    storage.data.put("client_notes_account_0_live_100_note_7", "a".repeat(10000));
    notes.set(notes.session(), 7, "🦊".repeat(200));
    assertEquals("🦊".repeat(128), notes.get(7));
    Map<Long, String> imported = new LinkedHashMap<>();
    imported.put(-42L, "b".repeat(10000));
    imported.put(ChatId.fromSupergroupId(88), "🦊".repeat(10000));
    assertEquals(2, notes.importNotes(notes.session(), new NotesBackup(100, false, imported)));
    assertEquals("b".repeat(128), notes.get(-42));
    assertEquals("🦊".repeat(128), notes.get(ChatId.fromSupergroupId(88)));
    for (String text : notes.exportNotes(notes.session()).notes.values()) {
      assertEquals(128, text.codePointCount(0, text.length()));
    }
  }

  @Test
  public void importsValidateEveryIdentityBeforeWritingAnyNote () {
    TdlibNotes notes = account(0, 100);
    Map<Long, String> imported = new LinkedHashMap<>();
    imported.put(7L, "Valid");
    imported.put(0L, "Invalid");
    assertThrows(IllegalArgumentException.class, () -> notes.importNotes(notes.session(), new NotesBackup(100, false, imported)));
    assertTrue(notes.exportNotes(notes.session()).notes.isEmpty());
  }

  private static final class MemoryStorage implements TdlibNotes.Storage {
    private final Map<String, String> data = new LinkedHashMap<>();

    @Override
    public String get (String key) {
      String value = data.get(key);
      return value != null ? value : "";
    }

    @Override
    public Map<String, String> entries (String prefix) {
      Map<String, String> result = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : data.entrySet()) {
        if (entry.getKey().startsWith(prefix)) {
          result.put(entry.getKey(), entry.getValue());
        }
      }
      return result;
    }

    @Override
    public void write (Map<String, String> changes) {
      changes.forEach((key, value) -> {
        if (value == null) {
          data.remove(key);
        } else {
          data.put(key, value);
        }
      });
    }

    @Override
    public void clear (String prefix) {
      data.keySet().removeIf(key -> key.startsWith(prefix));
    }
  }
}
