package org.thunderdog.challegram.config;

public final class ClientIdentity {
  private ClientIdentity () { }

  public static String notesPrefix () { return "client_notes_"; }
  public static String notesFormat () { return "client-notes"; }
  public static String webAppBridgeName () { return "ClientWebApp"; }
}
