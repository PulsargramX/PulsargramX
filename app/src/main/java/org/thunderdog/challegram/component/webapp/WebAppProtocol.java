/*
 * This file is a part of Pulsargram X, based on Telegram X.
 * Copyright © 2026 Pulsargram X contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See <https://www.gnu.org/licenses/> for the GNU General Public License.
 */
package org.thunderdog.challegram.component.webapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Validates the untrusted wire envelope before it reaches native request handlers. */
public final class WebAppProtocol {
  private WebAppProtocol () { }

  public static final class Message {
    public final String document;
    public final String event;
    public final JSONObject data;
    private Message (String document, String event, JSONObject data) {
      this.document = document;
      this.event = event;
      this.data = data;
    }
  }

  public static Message parse (String raw, boolean paymentOnly) {
    if (raw == null || raw.length() > 8 * 1024 * 1024) return null;
    try {
      JSONObject envelope = new JSONObject(raw);
      if (!(envelope.opt("document") instanceof String) || !(envelope.opt("event") instanceof String)) return null;
      String document = envelope.getString("document");
      String event = envelope.getString("event");
      if (document.isEmpty() || document.length() > 128 || event.length() > 96 ||
          !(event.equals("__init") || event.equals("__dispose") || (paymentOnly ? event.equals("payment_form_submit") :
            event.startsWith("web_app_")))) return null;
      Object payload = envelope.opt("data");
      if (payload instanceof String) {
        String encoded = (String) payload;
        if (encoded.trim().isEmpty()) {
          payload = null;
        } else {
          JSONTokener token = new JSONTokener(encoded);
          payload = token.nextValue();
          if (token.nextClean() != 0) return null;
          // Telegram's SDK serializes absent event arguments as JSON.stringify(''), i.e. "".
          if (payload instanceof String && ((String) payload).isEmpty()) payload = null;
        }
      }
      if (event.equals("web_app_allow_scroll") && payload instanceof JSONArray) {
        JSONArray axes = (JSONArray) payload;
        payload = new JSONObject().put("x", axes.optBoolean(0, true)).put("y", axes.optBoolean(1, true));
      }
      if (payload != null && payload != JSONObject.NULL && !(payload instanceof JSONObject)) return null;
      return new Message(document, event, payload instanceof JSONObject ? (JSONObject) payload : new JSONObject());
    } catch (JSONException | IllegalArgumentException ignored) {
      return null;
    }
  }
}
