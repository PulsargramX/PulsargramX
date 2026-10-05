package org.thunderdog.challegram.util;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.thunderdog.challegram.config.ClientIdentity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tgx.td.ChatId;

/** Versioned, account-bound JSON. IDs are strings so other tools cannot round them. */
public final class NotesBackup {
  /** Maximum note length in Unicode code points, not UTF-16 code units. */
  public static final int MAX_NOTE_LENGTH = 128;
  public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
  private static final String FORMAT = ClientIdentity.notesFormat();

  public final long userId;
  public final boolean testDc;
  public final Map<Long, String> notes;

  public NotesBackup (long userId, boolean testDc, Map<Long, String> notes) {
    this.userId = userId;
    this.testDc = testDc;
    Map<Long, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<Long, String> entry : notes.entrySet()) {
      String text = truncate(entry.getValue());
      if (!text.trim().isEmpty()) {
        normalized.put(entry.getKey(), text);
      }
    }
    this.notes = Collections.unmodifiableMap(normalized);
  }

  public static String truncate (String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    int length = text.codePointCount(0, text.length());
    return length <= MAX_NOTE_LENGTH ? text :
      text.substring(0, text.offsetByCodePoints(0, MAX_NOTE_LENGTH));
  }

  public static boolean isValidChatId (long chatId) {
    long supergroupId = ChatId.toSupergroupId(chatId);
    return ChatId.isPrivate(chatId) || ChatId.isBasicGroup(chatId) ||
      (supergroupId > 0 && supergroupId <= ChatId.MAX_CHANNEL_ID) ||
      (supergroupId >= ChatId.MIN_MONOFORUM_CHANNEL_ID && supergroupId <= ChatId.MAX_MONOFORUM_CHANNEL_ID);
  }

  public byte[] encode () throws JSONException, IOException {
    JSONObject root = new JSONObject();
    root.put("format", FORMAT);
    root.put("version", 1);
    root.put("account_user_id", Long.toString(userId));
    root.put("test_dc", testDc);
    JSONArray entries = new JSONArray();
    for (Map.Entry<Long, String> entry : notes.entrySet()) {
      JSONObject note = new JSONObject();
      note.put("chat_id", Long.toString(entry.getKey()));
      note.put("text", entry.getValue());
      entries.put(note);
    }
    root.put("notes", entries);
    byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FILE_BYTES) {
      throw new IOException("Notes backup is too large");
    }
    return bytes;
  }

  public static NotesBackup read (InputStream input) throws IOException, JSONException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (output.size() > MAX_FILE_BYTES - count) {
        throw new IOException("Notes backup is too large");
      }
      output.write(buffer, 0, count);
    }
    String json = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(output.toByteArray())).toString();
    JSONTokener tokener = new JSONTokener(json);
    Object value = tokener.nextValue();
    if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
      throw new JSONException("Invalid notes file");
    }
    JSONObject root = (JSONObject) value;
    if (!FORMAT.equals(root.get("format")) || !Integer.valueOf(1).equals(root.get("version")) ||
        !(root.get("test_dc") instanceof Boolean)) {
      throw new JSONException("Unsupported notes format");
    }
    long userId = readId(root, "account_user_id");
    if (!ChatId.isPrivate(userId)) {
      throw new JSONException("Invalid account ID");
    }
    JSONArray entries = root.getJSONArray("notes");
    Map<Long, String> notes = new LinkedHashMap<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject note = entries.getJSONObject(i);
      long chatId = readId(note, "chat_id");
      Object text = note.get("text");
      // Validate original text and duplicate IDs before constructor normalization so
      // legacy long notes are clipped, but clipping cannot hide invalid entries.
      if (!isValidChatId(chatId) || !(text instanceof String) ||
          ((String) text).trim().isEmpty() ||
          notes.containsKey(chatId)) {
        throw new JSONException("Invalid note");
      }
      notes.put(chatId, (String) text);
    }
    return new NotesBackup(userId, root.getBoolean("test_dc"), notes);
  }

  private static long readId (JSONObject object, String key) throws JSONException {
    Object value = object.get(key);
    if (value instanceof String) {
      try {
        long id = Long.parseLong((String) value);
        if (Long.toString(id).equals(value)) {
          return id;
        }
      } catch (NumberFormatException ignored) { }
    }
    throw new JSONException("Invalid ID");
  }
}
