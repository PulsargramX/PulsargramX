package org.thunderdog.challegram.util;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tgx.td.ChatId;

import static org.junit.Assert.*;

public class NotesBackupTest {
  @Test
  public void roundTripsUnicodeAndFullWidthIdsWithAccountBinding () throws Exception {
    Map<Long, String> notes = new LinkedHashMap<>();
    notes.put(1099511627775L, "Привіт 🦊\nSecond line\t\"quote\"\\");
    notes.put(-42L, "Basic group");
    notes.put(-1997852516352L, "Channel");
    notes.put(-4000000000000L, "Channel direct messages");
    NotesBackup original = new NotesBackup(9876543210L, true, notes);
    NotesBackup decoded = NotesBackup.read(new ByteArrayInputStream(original.encode()));
    assertEquals(original.userId, decoded.userId);
    assertTrue(decoded.testDc);
    assertEquals(notes, decoded.notes);
    assertTrue(new String(original.encode(), StandardCharsets.UTF_8).contains("\"1099511627775\""));
  }

  @Test
  public void acceptsEmptyBackups () throws Exception {
    NotesBackup empty = new NotesBackup(100, false, Collections.emptyMap());
    assertTrue(NotesBackup.read(new ByteArrayInputStream(empty.encode())).notes.isEmpty());
  }

  @Test
  public void rejectsWrongFormatVersionAccountAndNonStringIds () throws Exception {
    JSONObject root = valid();
    root.put("version", 2);
    assertInvalid(root);
    root = valid();
    root.put("format", "unrelated-data");
    assertInvalid(root);
    root = valid();
    root.put("test_dc", "false");
    assertInvalid(root);
    for (Object id : new Object[] {0, "0", "-1", "100.0", "1e2", "+100", "0100", "9223372036854775808"}) {
      root = valid();
      root.put("account_user_id", id);
      assertInvalid(root);
    }
  }

  @Test
  public void rejectsDuplicateAndInvalidNotes () throws Exception {
    JSONObject root = valid();
    root.getJSONArray("notes").put(new JSONObject(root.getJSONArray("notes").getJSONObject(0).toString()));
    assertInvalid(root);
    for (Object id : new Object[] {7, "0", "-1000000000000", "-2000000000000", Long.toString(ChatId.fromSecretChatId(1)), "999999999999999999"}) {
      root = valid();
      root.getJSONArray("notes").getJSONObject(0).put("chat_id", id);
      assertInvalid(root);
    }
    for (Object text : new Object[] {JSONObject.NULL, 17, " \n\t"}) {
      root = valid();
      root.getJSONArray("notes").getJSONObject(0).put("text", text);
      assertInvalid(root);
    }
  }

  @Test
  public void clipsLongNotesPreservedByMigrationsBeforeRoundTrip () throws Exception {
    String text = String.join("", Collections.nCopies(30000, "🦊"));
    String expected = String.join("", Collections.nCopies(128, "🦊"));
    NotesBackup backup = new NotesBackup(100, false, Collections.singletonMap(-1000000000007L, text));
    assertEquals(expected, backup.notes.get(-1000000000007L));
    assertEquals(expected, NotesBackup.read(new ByteArrayInputStream(backup.encode())).notes.get(-1000000000007L));
  }

  @Test
  public void excludesBlankNotesAfterConstructorNormalization () throws Exception {
    Map<Long, String> notes = new LinkedHashMap<>();
    notes.put(1L, null);
    notes.put(2L, "");
    notes.put(3L, " \n\t");
    notes.put(4L, String.join("", Collections.nCopies(128, " ")) + "content beyond limit");
    notes.put(5L, " Kept with surrounding whitespace ");
    NotesBackup backup = new NotesBackup(100, false, notes);
    assertEquals(Collections.singletonMap(5L, " Kept with surrounding whitespace "), backup.notes);
    assertEquals(backup.notes, NotesBackup.read(new ByteArrayInputStream(backup.encode())).notes);
  }

  @Test
  public void truncateHandlesNullEmptyAndShortText () {
    assertEquals(128, NotesBackup.MAX_NOTE_LENGTH);
    assertEquals("", NotesBackup.truncate(null));
    assertEquals("", NotesBackup.truncate(""));
    assertEquals(" \n\t", NotesBackup.truncate(" \n\t"));
    assertEquals("Привіт 🦊", NotesBackup.truncate("Привіт 🦊"));
  }

  @Test
  public void truncateCountsCodePointsWithoutSplittingSupplementaryCharacters () {
    String prefix = String.join("", Collections.nCopies(127, "a"));
    String boundary = prefix + "🦊";
    assertEquals(prefix, NotesBackup.truncate(prefix));
    assertEquals(boundary, NotesBackup.truncate(boundary));
    String clipped = NotesBackup.truncate(boundary + "🦊extra");
    assertEquals(boundary, clipped);
    assertEquals(128, clipped.codePointCount(0, clipped.length()));
    assertTrue(Character.isSurrogatePair(clipped.charAt(127), clipped.charAt(128)));
    String asciiBoundary = prefix + "a";
    assertEquals(asciiBoundary, NotesBackup.truncate(asciiBoundary + "🦊"));
  }

  @Test
  public void importsLegacyJsonLongerThanTheNoteLimit () throws Exception {
    String text = String.join("", Collections.nCopies(30000, "🦊"));
    JSONObject root = valid();
    root.getJSONArray("notes").getJSONObject(0).put("text", text);
    NotesBackup imported = read(root.toString());
    assertEquals(String.join("", Collections.nCopies(128, "🦊")), imported.notes.get(7L));
    assertEquals(new NotesBackup(100, false, Collections.singletonMap(7L, text)).notes, imported.notes);
    assertEquals(imported.notes, NotesBackup.read(new ByteArrayInputStream(imported.encode())).notes);
  }

  @Test
  public void importsLegacyNotesThatBecomeBlankWithoutRejectingTheBackup () throws Exception {
    JSONObject root = valid();
    root.getJSONArray("notes").getJSONObject(0).put("text",
      String.join("", Collections.nCopies(128, " ")) + "content beyond limit");
    assertTrue(read(root.toString()).notes.isEmpty());
    root.getJSONArray("notes").put(new JSONObject().put("chat_id", "8").put("text", "Keep me"));
    assertEquals(Collections.singletonMap(8L, "Keep me"), read(root.toString()).notes);
  }

  @Test
  public void rejectsDuplicateIdsEvenWhenTheirNotesBecomeBlankAfterClipping () throws Exception {
    JSONObject root = valid();
    root.getJSONArray("notes").getJSONObject(0).put("text",
      String.join("", Collections.nCopies(128, " ")) + "content beyond limit");
    root.getJSONArray("notes").put(new JSONObject().put("chat_id", "7").put("text", "Duplicate"));
    assertInvalid(root);
    root.getJSONArray("notes").getJSONObject(1).put("text",
      String.join("", Collections.nCopies(128, "\t")) + "also clipped away");
    assertInvalid(root);
  }

  @Test
  public void keepsNormalizedNotesAsAnImmutableOrderedSnapshot () {
    Map<Long, String> notes = new LinkedHashMap<>();
    notes.put(8L, "First");
    notes.put(7L, String.join("", Collections.nCopies(129, "a")));
    NotesBackup backup = new NotesBackup(100, false, notes);
    notes.clear();
    assertEquals(Arrays.asList(8L, 7L), Arrays.asList(backup.notes.keySet().toArray()));
    assertEquals(String.join("", Collections.nCopies(128, "a")), backup.notes.get(7L));
    assertThrows(UnsupportedOperationException.class, () -> backup.notes.put(9L, "Cannot mutate"));
  }

  @Test
  public void rejectsTruncatedFilesAndTrailingData () throws Exception {
    String json = valid().toString();
    assertThrows(JSONException.class, () -> read(json.substring(0, json.length() - 1)));
    assertThrows(JSONException.class, () -> read(json + " trailing"));
    assertThrows(JSONException.class, () -> read("[]"));
  }

  @Test
  public void boundsFileSizeBeforeParsing () {
    byte[] bytes = new byte[NotesBackup.MAX_FILE_BYTES + 1];
    Arrays.fill(bytes, (byte) ' ');
    assertThrows(IOException.class, () -> NotesBackup.read(new ByteArrayInputStream(bytes)));
  }

  @Test
  public void rejectsInvalidUtf8RatherThanChangingTheNote () {
    assertThrows(IOException.class,
      () -> NotesBackup.read(new ByteArrayInputStream(new byte[] {(byte) 0xc3, (byte) 0x28})));
  }

  private static JSONObject valid () throws Exception {
    return new JSONObject(new String(new NotesBackup(100, false,
      Collections.singletonMap(7L, "Note")).encode(), StandardCharsets.UTF_8));
  }

  private static NotesBackup read (String text) throws Exception {
    return NotesBackup.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static void assertInvalid (JSONObject root) {
    assertThrows(JSONException.class, () -> read(root.toString()));
  }
}
