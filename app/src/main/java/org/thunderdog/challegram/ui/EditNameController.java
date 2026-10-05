/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 22/12/2016
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.attach.AvatarPickerManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGFoundChat;
import org.thunderdog.challegram.emoji.EmojiFilter;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.telegram.TdlibNotes;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.CharacterStyleFilter;
import org.thunderdog.challegram.util.NoteLengthFilter;
import org.thunderdog.challegram.util.NotesBackup;
import org.thunderdog.challegram.widget.BetterChatView;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import me.vkryl.android.text.CodePointCountFilter;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import tgx.td.TdConstants;

public class EditNameController extends EditBaseController<EditNameController.Args> implements SettingsAdapter.TextChangeListener, Client.ResultHandler, TdlibCache.UserDataChangeListener, View.OnClickListener {
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    Mode.SIGNUP,
    Mode.RENAME_SELF,
    Mode.RENAME_CONTACT,
    Mode.ADD_CONTACT,
    Mode.RENAME_BOT
  })
  public @interface Mode {
    int
      SIGNUP = 0,
      RENAME_SELF = 1,
      RENAME_CONTACT = 2,
      ADD_CONTACT = 3,
      RENAME_BOT = 4;
  }

  public static class Args {
    public int mode;
    public TdApi.AuthorizationStateWaitRegistration authState;
    public String formattedPhone;
    public String knownPhoneNumber;

    public Args (int mode, TdApi.AuthorizationStateWaitRegistration authState, String formattedPhone) {
      this.mode = mode;
      this.authState = authState;
      this.formattedPhone = formattedPhone;
    }

    public Args setKnownPhoneNumber (String phoneNumber) {
      this.knownPhoneNumber = phoneNumber;
      return this;
    }
  }

  public EditNameController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public boolean isUnauthorized () {
    return mode == Mode.SIGNUP;
  }

  public void setMode (int mode) {
    this.mode = mode;
  }

  public void setUser (TdApi.User user) {
    this.user = user;
  }

  private int mode;
  private TdApi.User user;
  private String knownPhoneNumber;

  @Override
  public void setArguments (Args args) {
    super.setArguments(args);
    this.mode = args.mode;
    this.knownPhoneNumber = args.knownPhoneNumber;
  }

  public void setKnownPhoneNumber (String phoneNumber) {
    this.knownPhoneNumber = phoneNumber;
  }

  @Override
  public int getId () {
    return R.id.controller_name;
  }

  private SettingsAdapter adapter;
  private ListItem firstName, shareMyNumber;
  private @Nullable ListItem lastName;
  private @Nullable ListItem contactNote;
  private TdlibNotes.Session contactNoteSession;
  private String contactNoteOriginal = "";
  private boolean contactNoteLoaded, contactNoteChanged;

  private AvatarPickerManager avatarPicker;

  private boolean canEditContactPhoto () {
    return mode == Mode.RENAME_CONTACT && user != null && user.isContact &&
      user.id != tdlib.myUserId() && user.type instanceof TdApi.UserTypeRegular;
  }

  private boolean canEditContactNote () {
    return TdlibNotes.isEnabled() && user != null && user.id != tdlib.myUserId() &&
      user.type instanceof TdApi.UserTypeRegular &&
      (mode == Mode.ADD_CONTACT || (mode == Mode.RENAME_CONTACT && user.isContact));
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, RecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        if (item.getId() == R.id.edit_contact_note) {
          editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
          Views.setSingleLine(editText.getEditText(), false);
          editText.setMaxLength(NotesBackup.MAX_NOTE_LENGTH);
          editText.setBlockedText(!contactNoteLoaded || isInProgress() ? item.getStringValue() : null);
        } else {
          editText.getEditText().setInputType(InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
          editText.setBlockedText(isInProgress() ? item.getStringValue() : null);
        }
      }

      @Override
      protected void setChatData (ListItem item, int position, BetterChatView chatView) {
        chatView.setChat((TGFoundChat) item.getData());
        chatView.setEnabled(false);
      }
    };
    adapter.setLockFocusOn(this, mode != Mode.RENAME_CONTACT);
    if (mode == Mode.RENAME_CONTACT) {
      // Keep the contact menu neutral until the user chooses a field to edit.
      contentView.setFocusableInTouchMode(true);
      contentView.requestFocus();
    }
    adapter.setTextChangeListener(this);

    TdApi.User user;

    switch (mode) {
      case Mode.ADD_CONTACT:
      case Mode.RENAME_CONTACT:
      case Mode.RENAME_BOT: {
        user = this.user;
        break;
      }
      case Mode.RENAME_SELF: {
        user = tdlib.myUser();
        break;
      }
      default: {
        user = null;
        break;
      }
    }
    String firstNameValue, lastNameValue;
    if (user != null) {
      firstNameValue = user.firstName;
      lastNameValue = user.lastName;

      setDoneVisible(isGoodInput(firstNameValue, lastNameValue));
    } else {
      firstNameValue = lastNameValue = "";
      if (mode == Mode.SIGNUP && UI.inTestMode()) {
        firstNameValue = "Robot #" + tdlib.robotId();
      }
    }

    List<ListItem> items = new ArrayList<>();
    if ((mode == Mode.RENAME_CONTACT || mode == Mode.ADD_CONTACT || mode == Mode.RENAME_BOT) && user != null) {
      TGFoundChat chat = new TGFoundChat(tdlib, user.id);
      if (mode == Mode.RENAME_BOT) {
        chat.setForceUsername();
      } else {
        chat.setForcedSubtitle(
          !StringUtils.isEmpty(knownPhoneNumber) ?
            Strings.formatPhone(knownPhoneNumber) :
            TD.hasPhoneNumber(user) ?
              Strings.formatPhone(user.phoneNumber) :
              Lang.getString(R.string.NumberHidden)
        );
      }
      items.add(new ListItem(ListItem.TYPE_CHAT_BETTER, R.id.contact_photo_header).setData(chat));
    }
    items.add((firstName = new ListItem(items.isEmpty() ? ListItem.TYPE_EDITTEXT : ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_first_name, 0, mode == Mode.RENAME_BOT ? R.string.BotName : R.string.login_FirstName)
      .setStringValue(firstNameValue)
      .setInputFilters(new InputFilter[] {
        new CodePointCountFilter(TdConstants.MAX_NAME_LENGTH),
        new EmojiFilter(),
        new CharacterStyleFilter()
      })));
    if (mode != Mode.RENAME_BOT) {
      items.add((lastName = new ListItem(ListItem.TYPE_EDITTEXT_NO_PADDING, R.id.edit_last_name, 0, mode == Mode.RENAME_CONTACT || mode == Mode.ADD_CONTACT ? R.string.LastName : R.string.login_LastName)
        .setStringValue(lastNameValue)
        .setInputFilters(new InputFilter[] {
          new CodePointCountFilter(TdConstants.MAX_NAME_LENGTH),
          new EmojiFilter(),
          new CharacterStyleFilter()
        }).setOnEditorActionListener(new SimpleEditorActionListener(EditorInfo.IME_ACTION_DONE, this))));
    }
    TdApi.TermsOfService termsOfService = mode == Mode.SIGNUP ? getArgumentsStrict().authState.termsOfService : null;
    if (termsOfService != null && termsOfService.minUserAge != 0) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.plural(R.string.AgeVerification, termsOfService.minUserAge), false));
    }
    if ((mode == Mode.RENAME_CONTACT || mode == Mode.ADD_CONTACT) && user != null) {
      contactNoteSession = tdlib.notes().session();
      if (StringUtils.isEmpty(knownPhoneNumber) && !TD.hasPhoneNumber(user)) {
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getStringBold(R.string.NumberHiddenHint, tdlib.cache().userName(user.id)), false));
      }
      tdlib.cache().addUserDataListener(user.id, this);
      TdApi.UserFullInfo userFull = tdlib.cache().userFull(user.id);
      if (userFull != null && userFull.needPhoneNumberPrivacyException) {
        items.add(shareMyNumber = newShareItem());
      }
      if (canEditContactNote()) {
        contactNoteOriginal = tdlib.notes().get(user.id);
        contactNoteLoaded = mode == Mode.ADD_CONTACT;
        items.add(contactNote = new ListItem(ListItem.TYPE_EDITTEXT_COUNTERED, R.id.edit_contact_note, 0, R.string.Notes)
          .setStringValue(contactNoteOriginal)
          .setInputFilters(new InputFilter[] {
            new NoteLengthFilter(NotesBackup.MAX_NOTE_LENGTH),
            new EmojiFilter(),
            new CharacterStyleFilter()
          }));
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.NotesContactHint));
      }
    }
    if (canEditContactPhoto()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_suggestContactPhoto,
        R.drawable.baseline_image_24, Lang.getString(R.string.SuggestContactPhoto, user.firstName))
        .setTextColorId(ColorId.textNeutral));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_setContactPhoto,
        R.drawable.baseline_camera_alt_24, Lang.getString(R.string.SetContactPhoto, user.firstName))
        .setTextColorId(ColorId.textNeutral));
      TdApi.UserFullInfo full = tdlib.cache().userFull(user.id, false);
      if (full != null && full.personalPhoto != null) {
        items.add(newResetPhotoItem());
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, R.id.contact_photo_hint, 0, R.string.ContactPhotoHint));
    }
    if (mode == Mode.RENAME_BOT) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getMarkdownStringSecure(this, R.string.RenameBotHint), false));
    }
    adapter.setItems(items, false);
    recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    recyclerView.setAdapter(adapter);
    setDoneIcon(mode == Mode.SIGNUP ? R.drawable.baseline_arrow_forward_24 : R.drawable.baseline_check_24);
    if (contactNote != null && mode == Mode.RENAME_CONTACT) {
      loadContactNote();
    }
  }

  private void loadContactNote () {
    final long userId = user.id;
    tdlib.client().send(new TdApi.GetUserFullInfo(userId), result -> tdlib.ui().post(() -> {
      if (isDestroyed() || user == null || user.id != userId || !tdlib.notes().isCurrent(contactNoteSession)) {
        return;
      }
      if (result instanceof TdApi.UserFullInfo) {
        applyContactNote((TdApi.UserFullInfo) result);
      } else if (result instanceof TdApi.Error && !contactNoteLoaded) {
        // Keep an unknown note locked. Name-only edits pass null, never erase it.
        UI.showError(result);
      }
    }));
  }

  private void applyContactNote (TdApi.UserFullInfo info) {
    if (contactNote == null || contactNoteChanged || isInProgress() ||
        !tdlib.notes().isCurrent(contactNoteSession)) {
      return;
    }
    String cloudText = NotesBackup.truncate(info.note != null ? info.note.text : "");
    contactNoteOriginal = cloudText.isEmpty() ? tdlib.notes().get(user.id) : cloudText;
    contactNote.setStringValue(contactNoteOriginal);
    contactNoteLoaded = true;
    // Binding sets text before modifyEditText; release the old visible-text lock first.
    adapter.updateLockEditTextById(R.id.edit_contact_note, null);
    adapter.updateSimpleItemById(R.id.edit_contact_note);
  }

  @Override
  public void onFocus () {
    if (mode == Mode.RENAME_CONTACT) {
      getViewForApplyingOffsets().requestFocus();
    }
    super.onFocus();
    if (mode == Mode.SIGNUP) {
      ViewController<?> c = previousStackItem();
      destroyStackItemById(R.id.controller_code);
      if (UI.inTestMode()) {
        updateDoneState();
        UI.post(this::onDoneClick);
      }
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    if ((mode == Mode.ADD_CONTACT || mode == Mode.RENAME_CONTACT) && user != null) {
      tdlib.cache().removeUserDataListener(user.id, this);
    }
  }

  private ListItem newShareItem () {
    return new ListItem(ListItem.TYPE_CHECKBOX_OPTION_REVERSE, R.id.btn_shareMyContact, 0, Lang.getStringBold(R.string.ShareMyNumber, tdlib.cache().userName(user.id)), true);
  }

  @Override
  public void onUserUpdated (TdApi.User user) {
    tdlib.ui().post(() -> {
      if (!isDestroyed() && this.user != null && this.user.id == user.id && adapter != null) {
        this.user = user;
        ListItem header = adapter.findItemById(R.id.contact_photo_header);
        if (header != null) {
          TGFoundChat chat = new TGFoundChat(tdlib, user.id);
          chat.setForcedSubtitle(!StringUtils.isEmpty(knownPhoneNumber) ?
            Strings.formatPhone(knownPhoneNumber) : TD.hasPhoneNumber(user) ?
            Strings.formatPhone(user.phoneNumber) : Lang.getString(R.string.NumberHidden));
          header.setData(chat);
          adapter.updateItemById(R.id.contact_photo_header);
        }
      }
    });
  }

  private ListItem newResetPhotoItem () {
    return new ListItem(ListItem.TYPE_SETTING, R.id.btn_resetContactPhoto,
      R.drawable.baseline_replay_24, R.string.ResetContactPhoto).setTextColorId(ColorId.textNeutral);
  }

  private AvatarPickerManager avatarPicker () {
    if (avatarPicker == null) {
      avatarPicker = new AvatarPickerManager(this);
    }
    return avatarPicker;
  }

  @Override
  public void onUserFullUpdated (long userId, TdApi.UserFullInfo userFull) {
    tdlib.ui().post(() -> {
      if (!isDestroyed() && adapter != null && user != null && userId == user.id) {
        applyContactNote(userFull);
        if (canEditContactPhoto()) {
          if (userFull.personalPhoto != null) {
            if (adapter.findItemById(R.id.btn_resetContactPhoto) == null) {
              adapter.addItem(adapter.indexOfViewById(R.id.btn_setContactPhoto) + 1, newResetPhotoItem());
            }
          } else {
            adapter.removeItemById(R.id.btn_resetContactPhoto);
          }
        }
        if (mode == Mode.ADD_CONTACT && userFull.needPhoneNumberPrivacyException) {
          if (adapter.findItemById(R.id.btn_shareMyContact) == null) {
            adapter.addItem(adapter.getItemCount(), shareMyNumber = newShareItem());
          }
        } else if (mode == Mode.ADD_CONTACT) {
          adapter.removeItemById(R.id.btn_shareMyContact);
          shareMyNumber = null;
        }
      }
    });
  }

  @Override
  public void onClick (View v) {
    if (isInProgress()) {
      return;
    }
    if ((v.getId() == R.id.btn_suggestContactPhoto || v.getId() == R.id.btn_setContactPhoto) && canEditContactPhoto()) {
      hideSoftwareKeyboard();
      avatarPicker().showContactPhotoPicker(user, v.getId() == R.id.btn_suggestContactPhoto);
    } else if (v.getId() == R.id.btn_resetContactPhoto && canEditContactPhoto()) {
      avatarPicker().resetContactPhoto(user);
    } else if (v.getId() == R.id.btn_shareMyContact) {
      shareMyNumber.setSelected(adapter.toggleView(v));
    }
  }

  @Override
  public CharSequence getName () {
    switch (mode) {
      case Mode.RENAME_CONTACT: {
        return Lang.getString(R.string.EditContact);
      }
      case Mode.RENAME_BOT: {
        return Lang.getString(R.string.RenameBot);
      }
      case Mode.ADD_CONTACT: {
        return Lang.getString(R.string.AddContact);
      }
      case Mode.RENAME_SELF: {
        return Lang.getString(R.string.EditName);
      }
      case Mode.SIGNUP: {
        return Lang.getString(R.string.Registration);
      }
    }
    return "";
  }

  private boolean isGoodInput (String firstName, String lastName) {
    if (!StringUtils.isEmpty(firstName))
      return true;
    if (!StringUtils.isEmpty(lastName)) {
      switch (mode) {
        case Mode.RENAME_SELF:
        case Mode.RENAME_CONTACT:
        case Mode.RENAME_BOT:
        case Mode.ADD_CONTACT:
          return true;
      }
    }
    return false;
  }

  @Override
  protected boolean onDoneClick () {
    if (isInProgress()) {
      return true;
    }
    final String firstName = this.firstName.getStringValue().trim();
    final String lastName = this.lastName != null ? this.lastName.getStringValue().trim() : "";

    if (isGoodInput(firstName, lastName)) {
      switch (mode) {
        case Mode.RENAME_SELF: {
          setDoneInProgress(true);
          tdlib.client().send(new TdApi.SetName(firstName, lastName), this);
          break;
        }
        case Mode.ADD_CONTACT:
        case Mode.RENAME_CONTACT: {
          if (user != null) {
            setInProgress(true);
            TdApi.ImportedContact contact = new TdApi.ImportedContact(
              !StringUtils.isEmpty(knownPhoneNumber) ? knownPhoneNumber : user.phoneNumber,
              firstName, lastName,
              contactNote != null && contactNoteLoaded && contactNoteChanged ?
                new TdApi.FormattedText(NotesBackup.truncate(contactNote.getStringValue()), null) : null
            );
            boolean sharePhoneNumber = shareMyNumber != null && shareMyNumber.isSelected();
            tdlib.contactNotes().saveContact(contactNoteSession,
              new TdApi.AddContact(user.id, contact, sharePhoneNumber), this);
          }
          break;
        }
        case Mode.RENAME_BOT: {
          if (TD.canEditBot(user)) {
            setDoneInProgress(true);
            tdlib.client().send(new TdApi.SetBotName(user.id, "", firstName), this);
          }
          break;
        }
        case Mode.SIGNUP: {
          TdApi.TermsOfService termsOfService = getArgumentsStrict().authState.termsOfService;
          CharSequence text = TD.formatString(this, termsOfService.text.text, termsOfService.text.entities, null, null);
          openAlert(R.string.TermsOfService, text, Lang.getString(R.string.TermsOfServiceDone), (dialog, which) -> {
            setDoneInProgress(true);
            // TODO: add checkbox & explanation that this allows
            tdlib.client().send(new TdApi.RegisterUser(firstName, lastName, true), this);
          }, ALERT_NO_CANCELABLE | ALERT_HAS_LINKS);
          break;
        }
      }
    }

    return true;
  }

  @Override
  public void onResult (final TdApi.Object object) {
    tdlib.ui().post(() -> {
      if (!isDestroyed()) {
        if (mode == Mode.ADD_CONTACT || mode == Mode.RENAME_CONTACT) {
          setInProgress(false);
        } else {
          setDoneInProgress(false);
        }
        switch (object.getConstructor()) {
          case TdApi.Ok.CONSTRUCTOR:
          case TdApi.ImportedContacts.CONSTRUCTOR: {
            if (mode == Mode.SIGNUP) {
              hideSoftwareKeyboard();
            } else {
              onSaveCompleted();
            }
            break;
          }
          case TdApi.Error.CONSTRUCTOR: {
            UI.showError(object);
            if (contactNote != null && !contactNoteLoaded && !contactNoteChanged &&
                tdlib.notes().isCurrent(contactNoteSession)) {
              // The initial response may have arrived while a name-only save locked edits.
              loadContactNote();
            }
            break;
          }
        }
      }
    });
  }

  @Override
  public void onTextChanged (int id, ListItem item, MaterialEditTextGroup v) {
    String text = v.getText().toString();
    if (id == R.id.edit_first_name) {
      firstName.setStringValue(text);
      updateDoneState();
    } else if (id == R.id.edit_last_name && lastName != null) {
      lastName.setStringValue(text);
      updateDoneState();
    } else if (id == R.id.edit_contact_note && contactNote != null && contactNoteLoaded) {
      contactNote.setStringValue(text);
      contactNoteChanged = !text.equals(contactNoteOriginal);
    }
  }

  @Override
  protected void onProgressStateChanged (boolean inProgress) {
    if (adapter == null) {
      return;
    }
    adapter.updateLockEditTextById(R.id.edit_first_name, inProgress ? firstName.getStringValue() : null);
    if (lastName != null) {
      adapter.updateLockEditTextById(R.id.edit_last_name, inProgress ? lastName.getStringValue() : null);
    }
    if (contactNote != null) {
      adapter.updateLockEditTextById(R.id.edit_contact_note,
        inProgress || !contactNoteLoaded ? contactNote.getStringValue() : null);
    }
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (isInProgress()) {
      return true;
    }
    if (contactNoteChanged) {
      if (commit) {
        showUnsavedChangesPromptBeforeLeaving(null);
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  public boolean canSlideBackFrom (NavigationController controller, float x, float y) {
    return !isInProgress() && !contactNoteChanged && super.canSlideBackFrom(controller, x, y);
  }

  private void updateDoneState () {
    setDoneVisible(isGoodInput(firstName.getStringValue().trim(), lastName != null ? lastName.getStringValue().trim() : ""));
  }
}
