package org.thunderdog.challegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import org.thunderdog.challegram.FileProvider;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibNotes;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.NotesBackup;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class SettingsNotesController extends RecyclerViewController<Void>
    implements View.OnClickListener {
  private SettingsAdapter adapter;
  private boolean busy;

  public SettingsNotesController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_notesSettings;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.Notes);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        if (item.getId() == R.id.btn_notesEnabled) {
          view.getToggler().setRadioEnabled(TdlibNotes.isEnabled(), isUpdate);
        }
      }
    };
    adapter.setItems(new ListItem[] {
      new ListItem(ListItem.TYPE_SHADOW_TOP),
      new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_notesEnabled, 0, R.string.NotesEnable),
      new ListItem(ListItem.TYPE_SHADOW_BOTTOM),
      new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.NotesDescription),
      new ListItem(ListItem.TYPE_SHADOW_TOP),
      new ListItem(ListItem.TYPE_SETTING, R.id.btn_importNotes, R.drawable.baseline_file_download_24, R.string.NotesImport),
      new ListItem(ListItem.TYPE_SEPARATOR_FULL),
      new ListItem(ListItem.TYPE_SETTING, R.id.btn_exportNotes, R.drawable.baseline_share_24, R.string.NotesExport),
      new ListItem(ListItem.TYPE_SHADOW_BOTTOM),
      new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.NotesBackupDescription),
      new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.NotesStorageDescription)
    }, false);
    recyclerView.setAdapter(adapter);
  }

  @Override
  public void onClick (View view) {
    if (view.getId() == R.id.btn_notesEnabled) {
      TdlibNotes.setEnabled(adapter.toggleView(view));
    } else if (!busy && view.getId() == R.id.btn_importNotes) {
      chooseImport();
    } else if (!busy && view.getId() == R.id.btn_exportNotes) {
      exportNotes();
    }
  }

  private void chooseImport () {
    try {
      final TdlibNotes.Session session = tdlib.notes().session();
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      // Some file managers label JSON as text/plain or application/octet-stream.
      intent.setType("*/*");
      context().putActivityResultHandler(Intents.ACTIVITY_RESULT_IMPORT_NOTES, (request, result, data) -> {
        if (!isDestroyed() && result == Activity.RESULT_OK && data != null && data.getData() != null) {
          importNotes(session, data.getData());
        }
      });
      context().startActivityForResult(intent, Intents.ACTIVITY_RESULT_IMPORT_NOTES);
    } catch (RuntimeException e) {
      context().putActivityResultHandler(Intents.ACTIVITY_RESULT_IMPORT_NOTES, null);
      UI.showToast(R.string.NotesImportError, Toast.LENGTH_SHORT);
    }
  }

  private void importNotes (TdlibNotes.Session session, Uri uri) {
    busy = true;
    Background.instance().post(() -> {
      int error = 0;
      int imported = 0;
      int kept = 0;
      try (InputStream input = context().getContentResolver().openInputStream(uri)) {
        if (input == null) {
          throw new IOException("Missing notes file");
        }
        NotesBackup backup = NotesBackup.read(input);
        if (backup.userId != session.userId || backup.testDc != session.testDc) {
          error = R.string.NotesAccountMismatch;
        } else {
          imported = tdlib.notes().importNotes(session, backup);
          kept = backup.notes.size() - imported;
          tdlib.contactNotes().migratePending();
        }
      } catch (Exception e) {
        error = R.string.NotesImportError;
      }
      final int resultError = error;
      final int resultImported = imported;
      final int resultKept = kept;
      tdlib.ui().post(() -> {
        busy = false;
        if (!isDestroyed() && tdlib.notes().isCurrent(session)) {
          if (resultError != 0) {
            UI.showToast(resultError, Toast.LENGTH_LONG);
          } else {
            UI.showToast(Lang.getString(R.string.NotesImportSuccess, resultImported, resultKept), Toast.LENGTH_LONG);
          }
        }
      });
    });
  }

  private void exportNotes () {
    final TdlibNotes.Session session;
    try {
      session = tdlib.notes().session();
    } catch (RuntimeException e) {
      UI.showToast(R.string.NotesExportError, Toast.LENGTH_SHORT);
      return;
    }
    busy = true;
    Background.instance().post(() -> {
      try {
        File file = tdlib.notes().exportFile(session);
        tdlib.ui().post(() -> {
          busy = false;
          if (isDestroyed() || !tdlib.notes().isCurrent(session)) {
            if (file != null) {
              file.delete();
            }
            return;
          }
          if (file == null) {
            UI.showToast(R.string.NotesExportEmpty, Toast.LENGTH_SHORT);
            return;
          }
          try {
            Uri uri = FileProvider.getUriForFile(context(), context().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context().startActivity(Intent.createChooser(intent, Lang.getString(R.string.NotesExport)));
          } catch (RuntimeException e) {
            file.delete();
            UI.showToast(R.string.NotesExportError, Toast.LENGTH_SHORT);
          }
        });
      } catch (Exception e) {
        tdlib.ui().post(() -> {
          busy = false;
          if (!isDestroyed()) {
            UI.showToast(R.string.NotesExportError, Toast.LENGTH_SHORT);
          }
        });
      }
    });
  }

  @Override
  public void destroy () {
    context().putActivityResultHandler(Intents.ACTIVITY_RESULT_IMPORT_NOTES, null);
    super.destroy();
  }
}
