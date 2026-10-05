package org.thunderdog.challegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.emoji.EmojiFilter;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibContactNotes;
import org.thunderdog.challegram.telegram.TdlibNotes;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.CharacterStyleFilter;
import org.thunderdog.challegram.util.NoteLengthFilter;
import org.thunderdog.challegram.util.NotesBackup;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import me.vkryl.android.widget.FrameLayoutFix;

public final class EditNoteController extends EditBaseController<EditNoteController.Arguments>
    implements SettingsAdapter.TextChangeListener {
  public static final class Arguments {
    final long chatId;
    final String text;

    public Arguments (long chatId, String text) {
      this.chatId = chatId;
      this.text = text;
    }
  }

  private SettingsAdapter adapter;
  private TdlibNotes.Session session;
  private String text;

  public EditNoteController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_editNote;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(getArgumentsStrict().text.isEmpty() ? R.string.NotesAdd : R.string.NotesEdit);
  }

  @Override
  public void setArguments (Arguments args) {
    super.setArguments(args);
    text = NotesBackup.truncate(args.text);
    session = tdlib.notes().session();
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, RecyclerView recyclerView) {
    final int limit = NotesBackup.MAX_NOTE_LENGTH;
    adapter = new SettingsAdapter(this) {
      @Override
      protected void modifyEditText (ListItem item, ViewGroup parent, MaterialEditTextGroup editText) {
        editText.getEditText().setInputType(InputType.TYPE_CLASS_TEXT |
          InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        Views.setSingleLine(editText.getEditText(), false);
        editText.setMaxLength(limit);
      }
    };
    InputFilter lengthFilter = new NoteLengthFilter(limit);
    adapter.setItems(new ListItem[] {
      new ListItem(ListItem.TYPE_EDITTEXT_COUNTERED, R.id.input, 0, R.string.Notes)
        .setStringValue(text)
        .setInputFilters(new InputFilter[] {lengthFilter, new EmojiFilter(), new CharacterStyleFilter()}),
      new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.NotesEditorHint)
    }, false);
    adapter.setTextChangeListener(this);
    adapter.setLockFocusOn(this, true);
    recyclerView.setAdapter(adapter);
    recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    setDoneVisible(true);
  }

  @Override
  public void onTextChanged (int id, ListItem item, MaterialEditTextGroup view) {
    text = view.getText().toString();
  }

  @Override
  protected void onProgressStateChanged (boolean inProgress) {
    adapter.updateLockEditTextById(R.id.input, inProgress ? text : null);
  }

  @Override
  protected boolean onDoneClick () {
    if (isInProgress()) {
      return true;
    }
    setInProgress(true);
    final String savedText = text;
    final long chatId = getArgumentsStrict().chatId;
    if (TdlibContactNotes.isCloudContact(tdlib.cache().user(chatId), tdlib.myUserId())) {
      // A profile can become a contact while this editor is on the stack.
      // This is an intentional edit, not a legacy note subject to cloud-wins migration.
      tdlib.contactNotes().saveNote(session, chatId, new TdApi.FormattedText(savedText, null), result ->
        finishSave(result instanceof TdApi.Ok));
      return true;
    }
    Background.instance().post(() -> {
      boolean success;
      try {
        tdlib.notes().set(session, getArgumentsStrict().chatId, savedText);
        // The person may have become a contact while this local editor was open.
        tdlib.contactNotes().migratePending();
        success = true;
      } catch (RuntimeException e) {
        success = false;
      }
      finishSave(success);
    });
    return true;
  }

  private void finishSave (boolean saved) {
    tdlib.ui().post(() -> {
      if (!isDestroyed() && tdlib.notes().isCurrent(session)) {
        setInProgress(false);
        if (saved) {
          onSaveCompleted();
        } else {
          UI.showToast(R.string.NotesSaveError, Toast.LENGTH_SHORT);
        }
      }
    });
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (isInProgress()) {
      return true;
    }
    if (!text.equals(NotesBackup.truncate(getArgumentsStrict().text))) {
      if (commit) {
        showUnsavedChangesPromptBeforeLeaving(null);
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigationController, float x, float y) {
    return !isInProgress() && text.equals(NotesBackup.truncate(getArgumentsStrict().text));
  }
}
