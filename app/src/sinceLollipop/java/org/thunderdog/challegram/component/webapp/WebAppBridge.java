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

import androidx.webkit.ScriptHandler;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.config.ClientIdentity;

import java.util.Collections;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** TDLib origin policy and document-scoped channels authenticate native Mini App requests. */
public final class WebAppBridge {
  public interface Listener {
    void onEvent (String event, JSONObject data);
  }

  private static final String NAME = ClientIdentity.webAppBridgeName();
  private final WebView webView;
  private final String origin;
  private final Listener listener;
  private final boolean paymentOnly;
  private final boolean requireSameOrigin;
  private final Set<String> frameDocuments = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
  private final Map<String, JavaScriptReplyProxy> replies = new HashMap<>();
  private ScriptHandler script;
  private volatile String documentId;
  private String requestingDocument;
  private volatile boolean destroyed;

  public static String origin (String url) { return WebAppOrigin.origin(url); }

  public static void configureProfile (WebView view, String name) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
      WebViewCompat.setProfile(view, name);
    } else {
      throw new UnsupportedOperationException("Isolated WebView profiles unavailable");
    }
  }

  public static void forgetProfiles (String prefix) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
      for (String name : ProfileStore.getInstance().getAllProfileNames()) {
        if (name.startsWith(prefix)) forgetProfile(name);
      }
    }
  }

  public static void forgetProfile (String name) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
      try { ProfileStore.getInstance().deleteProfile(name); } catch (RuntimeException ignored) { }
    }
  }


  public WebAppBridge (WebView view, String launchUrl, Listener listener) {
    this(view, launchUrl, listener, false);
  }

  public WebAppBridge (WebView view, String launchUrl, Listener listener, boolean paymentOnly) {
    this(view, launchUrl, listener, paymentOnly, true);
  }

  public WebAppBridge (WebView view, String launchUrl, Listener listener, boolean paymentOnly, boolean requireSameOrigin) {
    this.requireSameOrigin = requireSameOrigin;
    this.paymentOnly = paymentOnly;
    webView = view;
    origin = origin(launchUrl);
    this.listener = listener;
  }

  public boolean install () {
    if (origin == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false;
    WebViewCompat.addWebMessageListener(webView, NAME, Collections.singleton(requireSameOrigin ? origin : "*"),
      (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
        if (destroyed || origin(sourceOrigin.toString()) == null || origin(view.getUrl()) == null ||
            (paymentOnly && !isMainFrame) ||
            (requireSameOrigin && (!origin.equals(origin(sourceOrigin.toString())) ||
              !origin.equals(origin(view.getUrl()))))) return;
        String raw;
        try { raw = message.getData(); } catch (RuntimeException ignored) { return; }
        WebAppProtocol.Message parsed = WebAppProtocol.parse(raw, paymentOnly);
        if (parsed == null) return;
        String token = parsed.document;
        if (parsed.event.equals("__dispose")) {
          if (isMainFrame && token.equals(documentId)) newDocument();
          else { frameDocuments.remove(token); replies.remove(token); }
          return;
        }
        if (parsed.event.equals("__init")) {
          if (isMainFrame && documentId == null) documentId = token;
          if (!isMainFrame && frameDocuments.size() < 128) frameDocuments.add(token);
          if (token.equals(documentId) || frameDocuments.contains(token)) replies.put(token, replyProxy);
          return;
        }
        if (documentId == null || !(isMainFrame ? token.equals(documentId) : frameDocuments.contains(token))) return;
        requestingDocument = token;
        try { listener.onEvent(parsed.event, parsed.data); }
        finally { requestingDocument = null; }
      });
    String bootstrap = "(function(){" +
      "var d=Date.now().toString(36)+Math.random().toString(36);" +
      "function send(e,p){try{" + NAME + ".postMessage(JSON.stringify({document:d,event:e,data:p}));}catch(x){}}" +
      NAME + ".onmessage=function(e){try{var v=JSON.parse(e.data);" +
      "if(window.Telegram&&Telegram.WebView)Telegram.WebView.receiveEvent(v.event,v.data);}catch(x){}};" +
      "window.TelegramWebviewProxy={postEvent:send};send('__init',{});" +
      "window.addEventListener('pageshow',function(){send('__init',{});});" +
      "window.addEventListener('pagehide',function(){send('__dispose',{});});})();";
    script = WebViewCompat.addDocumentStartJavaScript(webView, bootstrap,
      Collections.singleton(requireSameOrigin ? origin : "*"));
    return true;
  }

  public void newDocument () {
    documentId = null;
    frameDocuments.clear();
    replies.clear();
  }

  public boolean isTrusted () {
    return !destroyed && documentId != null;
  }

  public String requestingDocument () { return requestingDocument; }

  public boolean hasDocument (String document) {
    return isTrusted() && (document == null || document.equals(documentId) || frameDocuments.contains(document));
  }

  public void emitTo (String document, String event, JSONObject data) {
    if (!hasDocument(document)) return;
    JavaScriptReplyProxy reply = replies.get(document == null ? documentId : document);
    if (reply == null) return;
    try {
      reply.postMessage(new JSONObject().put("event", event)
        .put("data", data == null ? new JSONObject() : data).toString());
    } catch (JSONException | RuntimeException ignored) { }
  }

  public void emit (String event, JSONObject data) {
    if (!isTrusted()) return;
    try {
      String message = new JSONObject().put("event", event)
        .put("data", data == null ? new JSONObject() : data).toString();
      for (JavaScriptReplyProxy reply : new java.util.ArrayList<>(replies.values())) {
        reply.postMessage(message);
      }
    } catch (JSONException | RuntimeException ignored) { }
  }

  public void destroy () {
    destroyed = true;
    newDocument();
    if (script != null) script.remove();
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      WebViewCompat.removeWebMessageListener(webView, NAME);
    }
  }
}
