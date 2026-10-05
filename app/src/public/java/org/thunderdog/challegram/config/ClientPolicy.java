package org.thunderdog.challegram.config;

import org.drinkless.tdlib.TdApi;

/** The public client's content policy. Personal implementations are local source inputs. */
public final class ClientPolicy {
  private ClientPolicy () { }

  public static boolean allowContentAccess (boolean allowed) { return allowed; }
  public static boolean canSaveMessage (boolean allowed, boolean selfDestruct) { return allowed; }
  public static boolean enforceProtection () { return true; }
  public static boolean useDisposableViewer () { return true; }
  public static boolean useContentTimer () { return true; }
  public static boolean shouldOpenContent (boolean selfDestruct) { return true; }
  public static boolean showAds () { return true; }

  public static boolean shouldOpenMedia (TdApi.MessageSelfDestructType type, boolean closed) {
    return closed && type != null &&
      type.getConstructor() == TdApi.MessageSelfDestructTypeImmediately.CONSTRUCTOR;
  }

  public static boolean sendAsCopy (boolean requested, TdApi.MessageProperties[] properties) {
    return requested;
  }
}
