package org.thunderdog.challegram.telegram;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Exercises the real backend with controllable asynchronous TDLib results. */
public class TdlibContactNotesTest {
  @Rule public TemporaryFolder cache = new TemporaryFolder();
  private final AtomicLong identity = new AtomicLong(100);
  private final MemoryStorage storage = new MemoryStorage();
  private final FakeTransport transport = new FakeTransport();

  private TdlibNotes notes () {
    return new TdlibNotes(0, identity::get, () -> false, storage, cache.getRoot());
  }

  private static TdApi.User user (long id, boolean contact) {
    TdApi.User user = new TdApi.User();
    user.id = id;
    user.isContact = contact;
    user.type = new TdApi.UserTypeRegular();
    return user;
  }

  private static TdApi.UserFullInfo full (String text) {
    TdApi.UserFullInfo full = new TdApi.UserFullInfo();
    full.note = text == null ? null : new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    return full;
  }

  @Test
  public void resolvesUnknownContactsAndRemovesLocalOnlyAfterReadBack () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Private");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    assertEquals(7, transport.take(TdApi.GetUser.class).userId);
    transport.reply(user(7, true));
    assertEquals(7, transport.take(TdApi.GetUserFullInfo.class).userId);
    transport.reply(full(null));
    assertEquals("Private", transport.take(TdApi.SetUserNote.class).note.text);
    assertEquals("Private", notes.get(7));
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    assertEquals("Private", notes.get(7));
    transport.reply(full("Private"));
    assertEquals("", notes.get(7));
    assertTrue(notes.exportNotes(notes.session()).notes.isEmpty());
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void manualEditsWaitForInFlightMigrationAndWin () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Legacy");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full(null));
    assertEquals("Legacy", transport.take(TdApi.SetUserNote.class).note.text);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveContact(notes.session(), new TdApi.AddContact(7,
      new TdApi.ImportedContact("", "Name", "", new TdApi.FormattedText("Edited", new TdApi.TextEntity[0])), false), result::add);
    assertTrue(transport.requests.isEmpty());
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Legacy"));
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    assertEquals("Edited", transport.take(TdApi.AddContact.class).contact.note.text);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Edited"));
    assertTrue(result.remove() instanceof TdApi.Ok);
    assertTrue(transport.requests.isEmpty());
    assertEquals("", notes.get(7));
  }

  @Test
  public void offlineErrorsRetainLocalAndRetryOnlyAtAnExplicitBoundary () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Pending");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    transport.ready = false;
    cloud.migratePending();
    assertTrue(transport.requests.isEmpty());
    transport.ready = true;
    cloud.onStateChanged();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full(null));
    transport.take(TdApi.SetUserNote.class);
    transport.reply(new TdApi.Error(500, "Offline"));
    assertEquals("Pending", notes.get(7));
    cloud.onUserUpdated(user(7, true));
    cloud.onUserUpdated(user(7, true));
    assertTrue(transport.requests.isEmpty());
    transport.ready = false;
    cloud.onStateChanged();
    transport.ready = true;
    cloud.onStateChanged();
    transport.take(TdApi.GetUser.class);
  }

  @Test
  public void identityChangesInvalidateFlightsWithoutErasingEitherAccount () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "First");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    Client.ResultHandler old = transport.callbacks.remove();
    TdlibNotes.Session first = notes.session();
    identity.set(200);
    notes.set(notes.session(), 7, "Second");
    cloud.onIdentityChanged();
    transport.take(TdApi.GetUser.class);
    Client.ResultHandler second = transport.callbacks.remove();
    old.onResult(user(7, true));
    assertTrue(transport.requests.isEmpty());
    assertEquals("Second", notes.get(7));
    identity.set(100);
    cloud.onIdentityChanged();
    assertFalse(notes.isCurrent(first));
    transport.take(TdApi.GetUser.class);
    second.onResult(user(7, true));
    assertTrue(transport.requests.isEmpty());
    assertEquals("First", notes.get(7));
  }

  @Test
  public void contactRemovalWhileFetchingNeverWritesOrRemovesLocal () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Kept");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.users.put(7L, user(7, false));
    cloud.onUserUpdated(transport.users.get(7L));
    transport.reply(full(null));
    assertTrue(transport.requests.isEmpty());
    assertEquals("Kept", notes.get(7));
    transport.users.put(7L, user(7, true));
    cloud.onUserUpdated(transport.users.get(7L));
    transport.take(TdApi.GetUser.class);
  }

  @Test
  public void existingFormattedCloudNoteWinsWithoutAnyWrite () {
    for (String cloudText : new String[] {"Cloud", "🦊".repeat(128), "Local"}) {
      TdlibNotes notes = notes();
      notes.set(notes.session(), 7, "Local");
      TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
      cloud.migratePending();
      transport.take(TdApi.GetUser.class);
      transport.reply(user(7, true));
      transport.take(TdApi.GetUserFullInfo.class);
      TdApi.UserFullInfo full = full(cloudText);
      full.note.entities = new TdApi.TextEntity[] {new TdApi.TextEntity(0, cloudText.length(), new TdApi.TextEntityTypeBold())};
      TdApi.FormattedText original = full.note;
      transport.reply(full);
      assertTrue("Existing cloud note must not be rewritten", transport.requests.isEmpty());
      assertSame(original, full.note);
      assertEquals(cloudText, full.note.text);
      assertEquals(1, full.note.entities.length);
      assertEquals("", notes.get(7));
    }
  }

  @Test
  public void explicitNoteDeletionWaitsForMigrationAndNeverResurrectsLocal () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Legacy");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full(null));
    transport.take(TdApi.SetUserNote.class);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("", new TdApi.TextEntity[0]), result::add);
    assertTrue(transport.requests.isEmpty());
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Legacy"));
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    assertEquals("", transport.take(TdApi.SetUserNote.class).note.text);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full(null));
    assertTrue(result.remove() instanceof TdApi.Ok);
    cloud.migratePending();
    assertTrue(transport.requests.isEmpty());
    assertEquals("", notes.get(7));
  }

  @Test
  public void manualNotesRejectRemovedContactsBotsDeletedSelfAndMissingUsers () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Kept");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    TdApi.User bot = user(7, true); bot.type = new TdApi.UserTypeBot();
    TdApi.User deleted = user(7, true); deleted.type = new TdApi.UserTypeDeleted();
    for (TdApi.Object target : new TdApi.Object[] {user(7, false), bot, deleted, user(100, true), new TdApi.Error(404, "Missing")}) {
      long id = target instanceof TdApi.User ? ((TdApi.User) target).id : 7;
      ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
      cloud.saveNote(notes.session(), id, new TdApi.FormattedText("Edited", new TdApi.TextEntity[0]), result::add);
      transport.take(TdApi.GetUser.class);
      transport.reply(target);
      assertTrue("Must not write/add non-contact notes", transport.requests.isEmpty());
      assertTrue(result.remove() instanceof TdApi.Error);
      assertEquals("Kept", notes.get(7));
    }
  }

  @Test
  public void erasureDuringManualSaveCannotRemoveNewNotesAndCompletesOnce () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Old");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("Saved", new TdApi.TextEntity[0]), result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.SetUserNote.class);
    notes.clear();
    notes.set(notes.session(), 7, "New");
    transport.reply(new TdApi.Ok());
    assertTrue(transport.requests.isEmpty());
    assertTrue(result.remove() instanceof TdApi.Error);
    assertTrue(result.isEmpty());
    assertEquals("New", notes.get(7));
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
  }

  @Test
  public void explicitNotesClipAt128CodePointsWithoutMutatingQueuedRequests () {
    TdlibNotes notes = notes();
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    TdApi.FormattedText input = new TdApi.FormattedText("🦊".repeat(140), new TdApi.TextEntity[] {
      new TdApi.TextEntity(0, 280, new TdApi.TextEntityTypeBold()),
      new TdApi.TextEntity(260, 10, new TdApi.TextEntityTypeItalic())});
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, input, result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    TdApi.SetUserNote saved = transport.take(TdApi.SetUserNote.class);
    assertEquals("🦊".repeat(128), saved.note.text);
    assertEquals(1, saved.note.entities.length);
    assertEquals(256, saved.note.entities[0].length);
    assertEquals("🦊".repeat(140), input.text);
    assertEquals(280, input.entities[0].length);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("🦊".repeat(128)));
    assertTrue(result.remove() instanceof TdApi.Ok);
  }

  @Test
  public void addingContactWithUneditedNotePreservesNullAndMigratesPendingLocal () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Local");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveContact(notes.session(), new TdApi.AddContact(7,
      new TdApi.ImportedContact("+123", "New", "Contact", null), true), result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, false));
    TdApi.AddContact request = transport.take(TdApi.AddContact.class);
    assertNull(request.contact.note);
    assertTrue(request.sharePhoneNumber);
    transport.reply(new TdApi.Ok());
    assertTrue(result.remove() instanceof TdApi.Ok);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Cloud wins"));
    assertEquals("", notes.get(7));
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void removalDuringManualConfirmationRetainsSnapshotAndReturnsError () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Pending");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("Edited", new TdApi.TextEntity[0]), result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.SetUserNote.class);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.users.put(7L, user(7, false));
    cloud.onUserUpdated(transport.users.get(7L));
    transport.reply(full("Edited"));
    assertEquals("Pending", notes.get(7));
    assertTrue(result.remove() instanceof TdApi.Error);
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void staleFlightsDoNotBlockMigrationAfterAccountErasure () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Old");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    Client.ResultHandler old = transport.callbacks.remove();
    notes.clear();
    notes.set(notes.session(), 7, "New");
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    old.onResult(user(7, true));
    assertTrue(transport.requests.isEmpty());
    assertEquals("New", notes.get(7));
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
  }

  @Test
  public void synchronousTransportFailureRetainsLocalAndDoesNotJamTheLane () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Pending");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    transport.failSend = true;
    cloud.migratePending();
    assertEquals("Pending", notes.get(7));
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("Edited", new TdApi.TextEntity[0]), result::add);
    assertTrue(result.remove() instanceof TdApi.Error);
    assertEquals("Pending", notes.get(7));
    transport.failSend = false;
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
  }

  @Test
  public void failedLocalCleanupKeepsSnapshotAndAllowsLaterRetry () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Local");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    storage.failWrite = true;
    transport.reply(full("Cloud"));
    assertEquals("Local", notes.get(7));
    assertTrue(transport.requests.isEmpty());
    storage.failWrite = false;
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Cloud"));
    assertEquals("", notes.get(7));
  }

  @Test
  public void reconnectDoesNotLetManualEditsOvertakeAnOutstandingMigrationWrite () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Legacy");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full(null));
    transport.take(TdApi.SetUserNote.class);
    Client.ResultHandler outstanding = transport.callbacks.remove();
    transport.ready = false;
    cloud.onStateChanged();
    transport.ready = true;
    cloud.onStateChanged();
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("Edited", new TdApi.TextEntity[0]), result::add);
    assertTrue("Do not race the old cloud write", transport.requests.isEmpty());
    outstanding.onResult(new TdApi.Ok());
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    assertEquals("Edited", transport.take(TdApi.SetUserNote.class).note.text);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Edited"));
    assertTrue(result.remove() instanceof TdApi.Ok);
    assertEquals("", notes.get(7));
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void cloudContactEligibilityRequiresKnownAccountAndRegularOtherContact () {
    assertFalse(TdlibContactNotes.isCloudContact(user(7, true), 0));
    assertFalse(TdlibContactNotes.isCloudContact(user(7, true), 7));
    assertFalse(TdlibContactNotes.isCloudContact(null, 100));
    assertFalse(TdlibContactNotes.isCloudContact(user(7, false), 100));
    assertTrue(TdlibContactNotes.isCloudContact(user(7, true), 100));
    TdApi.User bot = user(7, true); bot.type = new TdApi.UserTypeBot();
    assertFalse(TdlibContactNotes.isCloudContact(bot, 100));
    TdApi.User deleted = user(7, true); deleted.type = new TdApi.UserTypeDeleted();
    assertFalse(TdlibContactNotes.isCloudContact(deleted, 100));
    TdApi.User unknown = user(7, true); unknown.type = new TdApi.UserTypeUnknown();
    assertFalse(TdlibContactNotes.isCloudContact(unknown, 100));
  }

  @Test
  public void everyMigrationFailureRetainsLocalUntilARetryConfirmsCloud () {
    for (int failedStage = 0; failedStage < 5; failedStage++) {
      TdlibNotes notes = notes();
      notes.set(notes.session(), 7, "Pending");
      TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
      cloud.migratePending();
      transport.take(TdApi.GetUser.class);
      if (failedStage == 0) transport.reply(new TdApi.Error(404, "Missing"));
      else {
        transport.reply(user(7, true));
        transport.take(TdApi.GetUserFullInfo.class);
        if (failedStage == 1) transport.reply(new TdApi.Error(500, "Read failed"));
        else {
          transport.reply(full(null));
          transport.take(TdApi.SetUserNote.class);
          if (failedStage == 2) transport.reply(new TdApi.Error(500, "Save failed"));
          else {
            transport.reply(new TdApi.Ok());
            transport.take(TdApi.GetUserFullInfo.class);
            transport.reply(failedStage == 3 ? new TdApi.Error(500, "Confirmation failed") : full("Different"));
          }
        }
      }
      assertEquals("Pending", notes.get(7));
      cloud.onUserUpdated(user(7, true));
      assertTrue(transport.requests.isEmpty());
      assertTrue(transport.callbacks.isEmpty());
      cloud.migratePending();
      transport.take(TdApi.GetUser.class);
      transport.reply(user(7, true));
      transport.take(TdApi.GetUserFullInfo.class);
      transport.reply(full("Confirmed cloud"));
      assertEquals("", notes.get(7));
      assertTrue(transport.requests.isEmpty());
    }
  }

  @Test
  public void botsNoncontactsDeletedMissingSelfGroupsAndChannelsStayLocal () {
    TdlibNotes notes = notes();
    TdApi.User bot = user(7, true); bot.type = new TdApi.UserTypeBot();
    TdApi.User deleted = user(9, true); deleted.type = new TdApi.UserTypeDeleted();
    Map<Long, TdApi.Object> users = new LinkedHashMap<>();
    users.put(7L, bot);
    users.put(8L, user(8, false));
    users.put(9L, deleted);
    users.put(10L, new TdApi.Error(404, "Missing"));
    users.put(100L, user(100, true));
    for (long id : users.keySet()) notes.set(notes.session(), id, "Local " + id);
    notes.set(notes.session(), -7, "Group");
    notes.set(notes.session(), tgx.td.ChatId.fromSupergroupId(7), "Channel");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    for (Map.Entry<Long, TdApi.Object> entry : users.entrySet()) {
      assertEquals(entry.getKey().longValue(), transport.take(TdApi.GetUser.class).userId);
      transport.reply(entry.getValue());
    }
    assertTrue(transport.requests.isEmpty());
    assertEquals(7, notes.exportNotes(notes.session()).notes.size());
  }

  @Test
  public void newerLocalEditsSurviveBothMigrationAndManualConfirmation () {
    for (boolean manual : new boolean[] {false, true}) {
      TdlibNotes notes = notes();
      notes.set(notes.session(), 7, "Old");
      TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
      ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
      if (manual) cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("Edited", new TdApi.TextEntity[0]), result::add);
      else cloud.migratePending();
      transport.take(TdApi.GetUser.class);
      transport.reply(user(7, true));
      if (!manual) {
        transport.take(TdApi.GetUserFullInfo.class);
        transport.reply(full(null));
      }
      transport.take(TdApi.SetUserNote.class);
      notes.set(notes.session(), 7, "Newer local");
      transport.reply(new TdApi.Ok());
      transport.take(TdApi.GetUserFullInfo.class);
      transport.reply(full(manual ? "Edited" : "Old"));
      assertEquals("Newer local", notes.get(7));
      assertTrue(transport.requests.isEmpty());
      if (manual) assertTrue(result.remove() instanceof TdApi.Ok);
    }
  }

  @Test
  public void confirmedExistingCloudNeverDeletesAnEditedLocalSnapshot () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Old");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    notes.set(notes.session(), 7, "Newer local");
    transport.reply(full("Cloud wins"));
    assertEquals("Newer local", notes.get(7));
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void reconnectRetriesAfterTheOutstandingRequestDrains () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Pending");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    Client.ResultHandler old = transport.callbacks.remove();
    transport.ready = false;
    cloud.onStateChanged();
    transport.ready = true;
    cloud.onStateChanged();
    assertTrue(transport.requests.isEmpty());
    old.onResult(user(7, true));
    transport.take(TdApi.GetUser.class);
    assertEquals("Pending", notes.get(7));
  }

  @Test
  public void failedExplicitDeletionRetainsLocalForLaterRetry () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Pending");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveContact(notes.session(), new TdApi.AddContact(7,
      new TdApi.ImportedContact("", "Name", "", new TdApi.FormattedText("", new TdApi.TextEntity[0])), false), result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.AddContact.class);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("Unexpected"));
    assertTrue(result.remove() instanceof TdApi.Error);
    assertEquals("Pending", notes.get(7));
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void reconnectReconcilesExplicitDeletionBeforeMigratingRetainedLegacy () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Legacy");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("", new TdApi.TextEntity[0]), result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    assertEquals("", transport.take(TdApi.SetUserNote.class).note.text);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    reconnect(cloud);
    assertEquals("Legacy", notes.get(7));
    assertTrue(result.remove() instanceof TdApi.Error);
    assertTrue(transport.requests.isEmpty());
    transport.reply(full(null)); // The invalidated confirmation only drains the old lane.
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    assertEquals("Legacy", notes.get(7));
    transport.reply(full(null)); // A fresh read confirms the deletion, not a migration write.
    assertTrue("Must not resurrect the deleted legacy note", transport.requests.isEmpty());
    assertEquals("", notes.get(7));
    assertTrue(result.isEmpty());
    cloud.migratePending();
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void existingBotContactCanBeRenamedWithoutACloudNote () {
    TdlibNotes notes = notes();
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    TdApi.User bot = user(7, true);
    bot.type = new TdApi.UserTypeBot();
    transport.users.put(7L, bot);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveContact(notes.session(), new TdApi.AddContact(7,
      new TdApi.ImportedContact("", "Renamed", "Bot", null), false), result::add);
    transport.take(TdApi.GetUser.class);
    transport.reply(bot);
    TdApi.AddContact request = transport.take(TdApi.AddContact.class);
    assertEquals("Renamed", request.contact.firstName);
    assertEquals("Bot", request.contact.lastName);
    assertNull(request.contact.note);
    transport.reply(new TdApi.Ok());
    assertTrue(result.remove() instanceof TdApi.Ok);
    assertTrue(result.isEmpty());
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void reconnectBeforeExplicitWriteRetainsSnapshotWithoutConfirmingAnUnsentClear () {
    for (boolean contact : new boolean[] {false, true}) {
      TdlibNotes notes = notes();
      notes.set(notes.session(), 7, "Legacy");
      TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
      ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
      saveClear(cloud, notes, contact, result);
      transport.take(TdApi.GetUser.class);
      reconnect(cloud);
      assertTrue(result.remove() instanceof TdApi.Error);
      assertEquals("Legacy", notes.get(7));
      assertTrue(transport.requests.isEmpty());
      transport.reply(user(7, true));
      transport.take(TdApi.GetUser.class);
      transport.reply(user(7, true));
      transport.take(TdApi.GetUserFullInfo.class);
      transport.reply(full(null));
      // No explicit write was ever submitted, so ordinary migration still applies.
      assertEquals("Legacy", transport.take(TdApi.SetUserNote.class).note.text);
      assertEquals("Legacy", notes.get(7));
      transport.reply(new TdApi.Ok());
      transport.take(TdApi.GetUserFullInfo.class);
      transport.reply(full("Legacy"));
      assertEquals("", notes.get(7));
      assertTrue(result.isEmpty());
      assertTrue(transport.requests.isEmpty());
    }
  }

  @Test
  public void reconnectDuringExplicitClearWriteOrConfirmationReconcilesBothApis () {
    for (boolean contact : new boolean[] {false, true}) {
      for (boolean confirmation : new boolean[] {false, true}) {
        TdlibNotes notes = notes();
        notes.set(notes.session(), 7, "Legacy");
        TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
        ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
        saveClear(cloud, notes, contact, result);
        transport.take(TdApi.GetUser.class);
        transport.reply(user(7, true));
        takeClear(contact);
        if (confirmation) {
          transport.reply(new TdApi.Ok());
          transport.take(TdApi.GetUserFullInfo.class);
        }
        reconnect(cloud);
        assertTrue(result.remove() instanceof TdApi.Error);
        assertEquals("Legacy", notes.get(7));
        assertTrue(transport.requests.isEmpty());
        transport.reply(confirmation ? full(null) : new TdApi.Ok());
        transport.take(TdApi.GetUser.class);
        transport.reply(user(7, true));
        transport.take(TdApi.GetUserFullInfo.class);
        assertEquals("Legacy", notes.get(7));
        transport.reply(full(null));
        assertEquals("", notes.get(7));
        assertTrue(result.isEmpty());
        assertTrue(transport.requests.isEmpty());
        cloud.migratePending();
        assertTrue(transport.requests.isEmpty());
      }
    }
  }

  @Test
  public void failedClearConfirmationKeepsLegacyProtectedUntilAFreshMatchingRead () {
    for (boolean contact : new boolean[] {false, true}) {
      for (boolean mismatch : new boolean[] {false, true}) {
        TdlibNotes notes = notes();
        notes.set(notes.session(), 7, "Legacy");
        TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
        ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
        saveClear(cloud, notes, contact, result);
        transport.take(TdApi.GetUser.class);
        transport.reply(user(7, true));
        takeClear(contact);
        transport.reply(new TdApi.Ok());
        transport.take(TdApi.GetUserFullInfo.class);
        transport.reply(mismatch ? full("Different") : new TdApi.Error(500, "Confirmation failed"));
        assertTrue(result.remove() instanceof TdApi.Error);
        assertEquals("Legacy", notes.get(7));
        assertTrue(transport.requests.isEmpty());
        reconnect(cloud);
        transport.take(TdApi.GetUser.class);
        transport.reply(user(7, true));
        transport.take(TdApi.GetUserFullInfo.class);
        transport.reply(mismatch ? full("Different") : new TdApi.Error(500, "Read failed"));
        assertEquals("Legacy", notes.get(7));
        assertTrue("Failed reconciliation must not migrate or erase the snapshot", transport.requests.isEmpty());
        cloud.onUserUpdated(user(7, true));
        assertTrue("No callback-loop retry", transport.requests.isEmpty());
        cloud.migratePending();
        transport.take(TdApi.GetUser.class);
        transport.reply(user(7, true));
        transport.take(TdApi.GetUserFullInfo.class);
        transport.reply(full(null));
        assertEquals("", notes.get(7));
        assertTrue(transport.requests.isEmpty());
        assertTrue(result.isEmpty());
      }
    }
  }

  @Test
  public void newerManualEditWaitsForInterruptedClearAndWinsBeforeReconciliation () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Legacy");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> cancelled = new ArrayDeque<>();
    saveClear(cloud, notes, false, cancelled);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    takeClear(false);
    reconnect(cloud);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("New edit", new TdApi.TextEntity[0]), result::add);
    assertTrue(transport.requests.isEmpty());
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    assertEquals("New edit", transport.take(TdApi.SetUserNote.class).note.text);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full("New edit"));
    assertTrue(result.remove() instanceof TdApi.Ok);
    assertTrue(cancelled.remove() instanceof TdApi.Error);
    assertTrue(cancelled.isEmpty());
    assertTrue(result.isEmpty());
    assertEquals("", notes.get(7));
    cloud.migratePending();
    assertTrue(transport.requests.isEmpty());
  }

  @Test
  public void deletionReconciliationCannotEraseNotesAfterErasureOrIdentityChange () {
    for (boolean accountChange : new boolean[] {false, true}) {
      TdlibNotes notes = notes();
      identity.set(100);
      notes.set(notes.session(), 7, "Legacy");
      TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
      ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
      saveClear(cloud, notes, false, result);
      transport.take(TdApi.GetUser.class);
      transport.reply(user(7, true));
      takeClear(false);
      reconnect(cloud);
      transport.reply(new TdApi.Ok());
      transport.take(TdApi.GetUser.class);
      transport.reply(user(7, true));
      transport.take(TdApi.GetUserFullInfo.class);
      Client.ResultHandler stale = transport.callbacks.remove();
      if (accountChange) identity.set(200);
      else notes.clear();
      notes.set(notes.session(), 7, "Replacement");
      if (accountChange) cloud.onIdentityChanged();
      else cloud.migratePending();
      transport.take(TdApi.GetUser.class);
      stale.onResult(full(null));
      assertEquals("Replacement", notes.get(7));
      assertTrue(transport.requests.isEmpty());
      transport.reply(user(7, true));
      transport.take(TdApi.GetUserFullInfo.class);
      transport.reply(full(null));
      assertEquals("Replacement", transport.take(TdApi.SetUserNote.class).note.text);
      transport.reply(new TdApi.Ok());
      transport.take(TdApi.GetUserFullInfo.class);
      transport.reply(full("Replacement"));
      assertEquals("", notes.get(7));
      assertTrue(result.remove() instanceof TdApi.Error);
      assertTrue(result.isEmpty());
      assertTrue(transport.requests.isEmpty());
    }
  }

  @Test
  public void botContactRejectsExplicitCloudNotesForBothApis () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Local bot note");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    TdApi.User bot = user(7, true);
    bot.type = new TdApi.UserTypeBot();
    transport.users.put(7L, bot);
    for (boolean contact : new boolean[] {false, true}) {
      for (String text : new String[] {"", "Explicit"}) {
        TdApi.FormattedText note = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
        if (contact) cloud.saveContact(notes.session(), new TdApi.AddContact(7,
          new TdApi.ImportedContact("", "Name", "", note), false), result::add);
        else cloud.saveNote(notes.session(), 7, note, result::add);
        transport.take(TdApi.GetUser.class);
        transport.reply(bot);
        assertTrue(result.remove() instanceof TdApi.Error);
        assertTrue(result.isEmpty());
        assertTrue("Do not send note-bearing AddContact or SetUserNote for bots", transport.requests.isEmpty());
        assertEquals("Local bot note", notes.get(7));
      }
    }
  }

  @Test
  public void rejectedNewerEditDoesNotForgetTheInterruptedDeletion () {
    TdlibNotes notes = notes();
    notes.set(notes.session(), 7, "Legacy");
    TdlibContactNotes cloud = new TdlibContactNotes(notes, transport);
    ArrayDeque<TdApi.Object> cancelled = new ArrayDeque<>();
    saveClear(cloud, notes, false, cancelled);
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    takeClear(false);
    reconnect(cloud);
    ArrayDeque<TdApi.Object> result = new ArrayDeque<>();
    cloud.saveNote(notes.session(), 7, new TdApi.FormattedText("New edit", new TdApi.TextEntity[0]), result::add);
    transport.reply(new TdApi.Ok());
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.SetUserNote.class);
    transport.reply(new TdApi.Error(400, "Edit rejected"));
    assertTrue(result.remove() instanceof TdApi.Error);
    assertTrue(cancelled.remove() instanceof TdApi.Error);
    assertEquals("Legacy", notes.get(7));
    cloud.migratePending();
    transport.take(TdApi.GetUser.class);
    transport.reply(user(7, true));
    transport.take(TdApi.GetUserFullInfo.class);
    transport.reply(full(null));
    assertTrue("A rejected newer edit must not resurrect the earlier deletion", transport.requests.isEmpty());
    assertEquals("", notes.get(7));
    assertTrue(result.isEmpty());
    assertTrue(cancelled.isEmpty());
  }

  private void saveClear (TdlibContactNotes cloud, TdlibNotes notes, boolean contact, ArrayDeque<TdApi.Object> result) {
    TdApi.FormattedText empty = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
    if (contact) cloud.saveContact(notes.session(), new TdApi.AddContact(7,
      new TdApi.ImportedContact("", "Name", "", empty), false), result::add);
    else cloud.saveNote(notes.session(), 7, empty, result::add);
  }

  private void takeClear (boolean contact) {
    String text = contact ? transport.take(TdApi.AddContact.class).contact.note.text :
      transport.take(TdApi.SetUserNote.class).note.text;
    assertEquals("", text);
  }

  private void reconnect (TdlibContactNotes cloud) {
    transport.ready = false;
    cloud.onStateChanged();
    transport.ready = true;
    cloud.onStateChanged();
  }

  private static final class FakeTransport implements TdlibContactNotes.Transport {
    final ArrayDeque<TdApi.Function<?>> requests = new ArrayDeque<>();
    final ArrayDeque<Client.ResultHandler> callbacks = new ArrayDeque<>();
    final Map<Long, TdApi.User> users = new LinkedHashMap<>();
    boolean ready = true;
    boolean failSend;
    @Override public boolean ready () { return ready; }
    @Override public TdApi.User user (long id) { return users.get(id); }
    @Override public void send (TdApi.Function<?> request, Client.ResultHandler callback) {
      if (failSend) throw new IllegalStateException("Transport unavailable");
      requests.add(request);
      callbacks.add(callback);
    }
    <T extends TdApi.Function<?>> T take (Class<T> type) {
      assertFalse("Expected " + type.getSimpleName(), requests.isEmpty());
      return type.cast(requests.remove());
    }
    void reply (TdApi.Object result) {
      callbacks.remove().onResult(result);
    }
  }

  private static final class MemoryStorage implements TdlibNotes.Storage {
    final Map<String, String> data = new LinkedHashMap<>();
    boolean failWrite;
    @Override public String get (String key) { return data.getOrDefault(key, ""); }
    @Override public Map<String, String> entries (String prefix) {
      Map<String, String> result = new LinkedHashMap<>();
      data.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
      return result;
    }
    @Override public void write (Map<String, String> changes) {
      if (failWrite) throw new IllegalStateException("Disk unavailable");
      changes.forEach((key, value) -> { if (value == null) data.remove(key); else data.put(key, value); });
    }
    @Override public void clear (String prefix) { data.keySet().removeIf(key -> key.startsWith(prefix)); }
  }
}
