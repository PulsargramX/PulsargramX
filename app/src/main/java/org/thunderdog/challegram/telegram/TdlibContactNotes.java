package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.util.NotesBackup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import tgx.td.ChatId;

/** Serializes silent legacy-note migration with explicit contact edits. */
public final class TdlibContactNotes {
  interface Transport {
    boolean ready ();
    TdApi.User user (long id);
    void send (TdApi.Function<?> request, Client.ResultHandler callback);
  }

  private final TdlibNotes notes;
  private final Transport transport;
  private final Map<Long, Task> active = new LinkedHashMap<>();
  private final Map<Long, ArrayDeque<Manual>> pending = new LinkedHashMap<>();
  // An explicit write may have reached TDLib even when its confirmation is lost.
  // Its retained snapshot must be reconciled, never blindly migrated again.
  private final Map<Long, Manual> unconfirmed = new LinkedHashMap<>();
  private final Set<Long> attempted = new HashSet<>();

  TdlibContactNotes (Tdlib tdlib) {
    this(tdlib.notes(), new Transport() {
      @Override public boolean ready () {
        return tdlib.authorizationStatus() == Tdlib.Status.READY && tdlib.isConnected();
      }
      @Override public TdApi.User user (long id) { return tdlib.cache().user(id); }
      @Override public void send (TdApi.Function<?> request, Client.ResultHandler callback) {
        tdlib.send(request, (result, error) -> callback.onResult(error != null ? error : result));
      }
    });
  }

  TdlibContactNotes (TdlibNotes notes, Transport transport) {
    this.notes = notes;
    this.transport = transport;
  }

  public static boolean isCloudContact (TdApi.User user, long myUserId) {
    return myUserId != 0 && user != null && ChatId.isPrivate(user.id) && user.id != myUserId && user.isContact &&
      user.type instanceof TdApi.UserTypeRegular;
  }

  /** Explicit retry boundary; errors are never retried in a callback loop. */
  public synchronized void migratePending () {
    if (!transport.ready()) return;
    final TdlibNotes.Session session;
    try { session = notes.session(); } catch (IllegalStateException ignored) { return; }
    for (Task task : new ArrayList<>(active.values())) {
      if (!notes.isCurrent(task.session)) abort(task);
    }
    attempted.clear();
    for (Task task : active.values()) {
      attempted.add(task.id);
      if (task.invalidated) task.retryAfterDrain = true;
    }
    for (Map.Entry<Long, String> entry : notes.exportNotes(session).notes.entrySet()) {
      long id = entry.getKey();
      if (ChatId.isPrivate(id) && !entry.getValue().isEmpty() && !active.containsKey(id) && attempted.add(id)) {
        startMigration(session, id, entry.getValue());
      }
    }
  }

  private void startMigration (TdlibNotes.Session session, long id, String local) {
    Manual explicit = unconfirmed.get(id);
    if (explicit != null) {
      if (notes.isCurrent(explicit.session) && local.equals(explicit.local)) {
        startReconciliation(explicit);
        return;
      }
      unconfirmed.remove(id);
    }
    Migration task = new Migration(session, id, local);
    active.put(id, task);
    send(task, new TdApi.GetUser(id), result -> {
      if (!(result instanceof TdApi.User) || !isCloudContact((TdApi.User) result, session.userId)) {
        finish(task);
        return;
      }
      task.user = (TdApi.User) result;
      send(task, new TdApi.GetUserFullInfo(id), full -> {
        if (!(full instanceof TdApi.UserFullInfo)) { finish(task); return; }
        TdApi.FormattedText existing = ((TdApi.UserFullInfo) full).note;
        String cloud = existing == null ? "" : existing.text;
        // A fresh nonempty cloud note always wins, including its formatting.
        // Never merge, truncate or rewrite a note that was saved on another device.
        if (!cloud.isEmpty()) {
          notes.removeMigrated(task.session, task.id, task.local);
          finish(task);
          return;
        }
        task.expected = task.local;
        send(task, new TdApi.SetUserNote(id, new TdApi.FormattedText(task.expected, new TdApi.TextEntity[0])), saved -> {
          if (!(saved instanceof TdApi.Ok)) { finish(task); return; }
          send(task, new TdApi.GetUserFullInfo(id), confirmation -> {
            if (confirmation instanceof TdApi.UserFullInfo) confirmed(task, (TdApi.UserFullInfo) confirmation);
            else finish(task);
          });
        });
      });
    });
  }

  private void startReconciliation (Manual explicit) {
    Reconciliation task = new Reconciliation(explicit);
    active.put(task.id, task);
    send(task, new TdApi.GetUser(task.id), result -> {
      if (!(result instanceof TdApi.User) || !isCloudContact((TdApi.User) result, task.session.userId)) {
        finish(task);
        return;
      }
      task.user = (TdApi.User) result;
      send(task, new TdApi.GetUserFullInfo(task.id), confirmation -> {
        if (confirmation instanceof TdApi.UserFullInfo) {
          TdApi.FormattedText cloud = ((TdApi.UserFullInfo) confirmation).note;
          if ((cloud == null ? "" : cloud.text).equals(explicit.note.text)) {
            notes.removeMigrated(task.session, task.id, task.local);
            unconfirmed.remove(task.id, explicit);
          }
        }
        // A failed or mismatched read keeps the snapshot protected until another
        // retry boundary or a newer explicit edit. Do not retry in a callback loop.
        finish(task);
      });
    });
  }

  /** Authorization/connectivity lifecycle hook. Call outside cache locks. */
  public synchronized void onStateChanged () {
    if (!transport.ready()) {
      // TDLib still owns the outstanding request. Keep its per-user lane occupied
      // until its terminal callback, otherwise a reconnect could reorder cloud writes.
      for (Task task : active.values()) {
        task.invalidated = true;
        if (task instanceof Manual) notifyCancelled((Manual) task);
      }
      for (ArrayDeque<Manual> queue : pending.values()) for (Manual task : queue) notifyCancelled(task);
      pending.clear();
      attempted.clear();
    } else {
      migratePending();
    }
  }

  /** Also used for authorization resets and account-slot reuse; never erases notes. */
  public synchronized void onIdentityChanged () {
    notes.invalidateSession();
    cancelPending();
    migratePending();
  }

  private void notifyCancelled (Manual task) {
    if (!task.completed) {
      task.completed = true;
      task.callback.onResult(new TdApi.Error(400, "Notes account changed or disconnected"));
    }
  }

  private void cancelPending () {
    ArrayDeque<Manual> cancelled = new ArrayDeque<>();
    for (Task task : active.values()) if (task instanceof Manual) cancelled.add((Manual) task);
    for (ArrayDeque<Manual> queue : pending.values()) cancelled.addAll(queue);
    active.clear();
    pending.clear();
    unconfirmed.clear();
    attempted.clear();
    for (Manual task : cancelled) complete(task, new TdApi.Error(400, "Notes account changed or disconnected"));
  }

  public synchronized void onUserUpdated (TdApi.User user) {
    if (user == null) return;
    if (!user.isContact || !(user.type instanceof TdApi.UserTypeRegular)) {
      attempted.remove(user.id);
      return;
    }
    if (!transport.ready() || attempted.contains(user.id)) return;
    TdlibNotes.Session session;
    try { session = notes.session(); } catch (IllegalStateException ignored) { return; }
    String local = notes.get(user.id);
    if (isCloudContact(user, session.userId) && !local.isEmpty() && !active.containsKey(user.id) && attempted.add(user.id)) {
      startMigration(session, user.id, local);
    }
  }

  /** Queue explicit edits behind an already-sent migration, so the user's edit wins. */
  public synchronized void saveContact (TdlibNotes.Session session, TdApi.AddContact request, Client.ResultHandler callback) {
    if (!notes.isCurrent(session) || !transport.ready()) {
      callback.onResult(new TdApi.Error(400, "Notes account changed or disconnected"));
      return;
    }
    TdApi.ImportedContact contact = request.contact;
    TdApi.FormattedText note = copyNote(contact.note);
    TdApi.AddContact copy = new TdApi.AddContact(request.userId,
      new TdApi.ImportedContact(contact.phoneNumber, contact.firstName, contact.lastName, note), request.sharePhoneNumber);
    Manual task = new Manual(session, request.userId, copy, note, false, notes.get(request.userId), callback);
    enqueue(task);
  }

  /** Save only the note, without adding/renaming a contact that may have been removed. */
  public synchronized void saveNote (TdlibNotes.Session session, long userId, TdApi.FormattedText note, Client.ResultHandler callback) {
    if (!notes.isCurrent(session) || !transport.ready()) {
      callback.onResult(new TdApi.Error(400, "Notes account changed or disconnected"));
      return;
    }
    note = copyNote(note);
    enqueue(new Manual(session, userId, new TdApi.SetUserNote(userId, note), note, true, notes.get(userId), callback));
  }

  private static TdApi.FormattedText copyNote (TdApi.FormattedText note) {
    if (note == null) return null; // TDLib null means preserve existing formatting/note.
    String text = NotesBackup.truncate(note.text);
    ArrayList<TdApi.TextEntity> entities = new ArrayList<>();
    if (note.entities != null) {
      for (TdApi.TextEntity entity : note.entities) {
        if (entity != null && entity.offset >= 0 && entity.offset < text.length() && entity.length > 0) {
          entities.add(new TdApi.TextEntity(entity.offset, Math.min(entity.length, text.length() - entity.offset), entity.type));
        }
      }
    }
    return new TdApi.FormattedText(text, entities.toArray(new TdApi.TextEntity[0]));
  }

  private void enqueue (Manual task) {
    if (active.containsKey(task.id)) {
      pending.computeIfAbsent(task.id, ignored -> new ArrayDeque<>()).add(task);
    } else {
      startManual(task);
    }
  }

  private void startManual (Manual task) {
    active.put(task.id, task);
    send(task, new TdApi.GetUser(task.id), result -> {
      if (!(result instanceof TdApi.User)) { complete(task, result); return; }
      TdApi.User user = (TdApi.User) result;
      TdApi.User cached = transport.user(task.id);
      if (cached != null) user = cached;
      if (user.id != task.id || user.id == task.session.userId ||
          (!(user.type instanceof TdApi.UserTypeRegular) && !isNameOnlyBotEdit(task, user)) ||
          (task.requireContact && !isCloudContact(user, task.session.userId))) {
        complete(task, new TdApi.Error(400, "User is not eligible for a cloud contact note"));
        return;
      }
      task.user = user;
      Manual previous = task.note != null && !task.local.isEmpty() ? unconfirmed.put(task.id, task) : null;
      send(task, task.request, saved -> {
        if (!(saved instanceof TdApi.Ok) && unconfirmed.remove(task.id, task) && previous != null) {
          unconfirmed.put(task.id, previous);
        }
        if (!(saved instanceof TdApi.Ok) || task.note == null) {
          complete(task, saved);
          return;
        }
        send(task, new TdApi.GetUserFullInfo(task.id), confirmation -> {
          if (!(confirmation instanceof TdApi.UserFullInfo)) { complete(task, confirmation); return; }
          TdApi.FormattedText cloud = ((TdApi.UserFullInfo) confirmation).note;
          String text = cloud == null ? "" : cloud.text;
          if (!text.equals(task.note.text)) {
            complete(task, new TdApi.Error(400, "Contact note confirmation failed"));
            return;
          }
          notes.removeMigrated(task.session, task.id, task.local);
          unconfirmed.remove(task.id, task);
          complete(task, saved);
        });
      });
    });
  }

  private void complete (Manual task, TdApi.Object result) {
    if (task.completed) return;
    task.completed = true;
    try { task.callback.onResult(result); } finally {
      finish(task);
      if (result instanceof TdApi.Ok && task.note == null && notes.isCurrent(task.session) && transport.ready() && !active.containsKey(task.id)) {
        String local = notes.get(task.id);
        if (!local.isEmpty()) {
          attempted.add(task.id);
          startMigration(task.session, task.id, local);
        }
      }
    }
  }

  private void confirmed (Migration task, TdApi.UserFullInfo full) {
    String cloud = full.note == null ? "" : full.note.text;
    if (cloud.equals(task.expected)) notes.removeMigrated(task.session, task.id, task.local);
    finish(task);
  }

  private void send (Task task, TdApi.Function<?> request, Client.ResultHandler callback) {
    if (!current(task)) { abort(task); return; }
    try {
      transport.send(request, result -> {
        synchronized (TdlibContactNotes.this) {
          if (!current(task)) { abort(task); return; }
          try {
            callback.onResult(result);
          } catch (RuntimeException ignored) {
            abort(task); // Storage failure: preserve the snapshot and retry at the next boundary.
          }
        }
      });
    } catch (RuntimeException ignored) {
      abort(task);
    }
  }

  private void abort (Task task) {
    if (task instanceof Manual && !((Manual) task).completed) {
      complete((Manual) task, new TdApi.Error(400, "Notes account changed, contact removed or disconnected"));
    } else {
      finish(task);
    }
  }

  private boolean current (Task task) {
    if (active.get(task.id) != task || task.invalidated || !notes.isCurrent(task.session) || !transport.ready()) return false;
    if (task.user != null) {
      TdApi.User cached = transport.user(task.id);
      TdApi.User user = cached != null ? cached : task.user;
      if (user.id != task.id || user.id == task.session.userId ||
          (!(user.type instanceof TdApi.UserTypeRegular) && !isNameOnlyBotEdit(task, user))) return false;
      if (!(task instanceof Manual) || ((Manual) task).requireContact) return isCloudContact(user, task.session.userId);
    }
    return true;
  }

  private static boolean isNameOnlyBotEdit (Task task, TdApi.User user) {
    return task instanceof Manual && ((Manual) task).request instanceof TdApi.AddContact &&
      ((Manual) task).note == null && user.type instanceof TdApi.UserTypeBot;
  }

  private void finish (Task task) {
    if (active.get(task.id) != task) return;
    active.remove(task.id);
    ArrayDeque<Manual> queue = pending.get(task.id);
    if (queue != null && !queue.isEmpty()) {
      Manual next = queue.remove();
      if (queue.isEmpty()) pending.remove(task.id);
      startManual(next);
    } else if (task.retryAfterDrain && transport.ready() && notes.isCurrent(task.session)) {
      String local = notes.get(task.id);
      if (!local.isEmpty()) {
        attempted.add(task.id);
        startMigration(task.session, task.id, local);
      }
    }
  }

  private static class Task {
    final TdlibNotes.Session session;
    final long id;
    final String local;
    TdApi.User user;
    boolean invalidated;
    boolean retryAfterDrain;
    Task (TdlibNotes.Session session, long id, String local) {
      this.session = session;
      this.id = id;
      this.local = local;
    }
  }

  private static final class Migration extends Task {
    String expected;
    Migration (TdlibNotes.Session session, long id, String local) { super(session, id, local); }
  }

  private static final class Reconciliation extends Task {
    Reconciliation (Manual explicit) { super(explicit.session, explicit.id, explicit.local); }
  }

  private static final class Manual extends Task {
    final TdApi.Function<?> request;
    final TdApi.FormattedText note;
    final boolean requireContact;
    final Client.ResultHandler callback;
    boolean completed;
    Manual (TdlibNotes.Session session, long id, TdApi.Function<?> request, TdApi.FormattedText note, boolean requireContact, String local, Client.ResultHandler callback) {
      super(session, id, local);
      this.request = request;
      this.note = note;
      this.requireContact = requireContact;
      this.callback = callback;
    }
  }
}
