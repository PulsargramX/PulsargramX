package org.thunderdog.challegram.component.chat;

import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.util.StringList;

import me.vkryl.core.collection.IntList;

public final class ClientMessageActions {
  private ClientMessageActions () { }

  public static void appendActions (TGMessage message, boolean isSent, IntList ids,
                                   IntList icons, StringList strings) { }

  public static boolean handleAction (int id, TGMessage message) { return false; }
}
