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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Canonical HTTP origin comparison shared by navigation and the native message boundary. */
public final class WebAppOrigin {
  private WebAppOrigin () { }

  public static String origin (String url) {
    if (url == null || url.indexOf('\\') >= 0) return null;
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (host == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) ||
          uri.getRawUserInfo() != null || uri.getPort() > 65535) return null;
      int port = uri.getPort();
      return scheme.toLowerCase(Locale.US) + "://" + host.toLowerCase(Locale.US) +
        (port == -1 || port == 443 && "https".equalsIgnoreCase(scheme) ||
          port == 80 && "http".equalsIgnoreCase(scheme) ? "" : ":" + port);
    } catch (URISyntaxException | IllegalArgumentException ignored) {
      return null;
    }
  }

  public static boolean mayNavigate (String launchUrl, String target, boolean requireSameOrigin) {
    String source = origin(launchUrl);
    String destination = origin(target);
    if (source == null || destination == null) return false;
    if ("https".equalsIgnoreCase(URI.create(launchUrl).getScheme()) &&
        !"https".equalsIgnoreCase(URI.create(target).getScheme())) return false;
    return !requireSameOrigin || source.equals(destination);
  }
}
