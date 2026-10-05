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

import android.webkit.WebView;
import org.json.JSONObject;

/** Legacy Android lacks a bridge that can authenticate a message's origin. */
public final class WebAppBridge {
  public interface Listener { void onEvent (String event, JSONObject data); }
  public WebAppBridge (WebView view, String launchUrl, Listener listener) { }
  public WebAppBridge (WebView view, String launchUrl, Listener listener, boolean paymentOnly) { }
  public WebAppBridge (WebView view, String launchUrl, Listener listener, boolean paymentOnly, boolean requireSameOrigin) { }
  public static String origin (String url) { return WebAppOrigin.origin(url); }
  public static void configureProfile (WebView view, String name) {
    throw new UnsupportedOperationException("Isolated WebView profiles unavailable");
  }
  public static void forgetProfile (String name) { }
  public static void forgetProfiles (String prefix) { }
  public boolean install () { return false; }
  public void newDocument () { }
  public boolean isTrusted () { return false; }
  public void emit (String event, JSONObject data) { }
  public String requestingDocument () { return null; }
  public boolean hasDocument (String document) { return false; }
  public void emitTo (String document, String event, JSONObject data) { }
  public void destroy () { }
}
