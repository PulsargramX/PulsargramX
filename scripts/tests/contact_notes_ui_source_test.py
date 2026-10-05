#!/usr/bin/env python3
"""UI wiring and JVM behavior regressions; Android rendering still needs device/CI validation."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/org/thunderdog/challegram/ui"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for pos in range(opening, len(source)):
        depth += (source[pos] == "{") - (source[pos] == "}")
        if not depth:
            return source[start:pos + 1]
    raise AssertionError("Unterminated method")


class ContactNotesUiSourceTest(unittest.TestCase):
    def test_contact_action_is_edit_contact_not_rename(self):
        for name in ("ProfileController.java", "EditNameController.java"):
            source = (UI / name).read_text()
            self.assertIn("R.string.EditContact", source)
            self.assertNotIn("R.string.RenameContact", source)

    def test_local_menu_excludes_cloud_contacts(self):
        source = (UI / "ProfileController.java").read_text()
        action = method(source, "private void addNoteAction (")
        self.assertIn("!hasCloudNote()", action)
        self.assertIn("R.string.NotesAdd", action)
        editor = method(source, "private void editNote ()")
        self.assertIn("hasCloudNote()", editor)
        self.assertIn("Mode.RENAME_CONTACT", editor)

    def test_profile_reads_cloud_note_for_contacts(self):
        source = (UI / "ProfileController.java").read_text()
        load = method(source, "private boolean loadNote ()")
        self.assertIn("userFull.note", load)
        self.assertIn("hasCloudNote()", load)
        self.assertNotIn("10000", load)
        update = method(source, "public void onUserUpdated (")
        self.assertIn("checkNote();", update)

    def test_local_editor_clips_old_text_and_has_fixed_limit(self):
        source = (UI / "EditNoteController.java").read_text()
        self.assertIn("text = NotesBackup.truncate(args.text);", source)
        self.assertIn("final int limit = NotesBackup.MAX_NOTE_LENGTH;", source)
        self.assertNotIn("Math.max(NotesBackup.MAX_NOTE_LENGTH", source)

    def test_contact_form_has_unicode_limited_note_field(self):
        source = (UI / "EditNameController.java").read_text()
        self.assertIn("R.id.edit_contact_note", source)
        self.assertIn("new NoteLengthFilter(NotesBackup.MAX_NOTE_LENGTH)", source)
        self.assertEqual(2, source.count("new CodePointCountFilter(TdConstants.MAX_NAME_LENGTH)"),
                         "Unrelated name filters must retain their existing behavior")
        self.assertNotIn("new CodePointCountFilter(NotesBackup.MAX_NOTE_LENGTH)", source)
        local_editor = (UI / "EditNoteController.java").read_text()
        self.assertIn("InputFilter lengthFilter = new NoteLengthFilter(limit);", local_editor)
        self.assertIn("R.string.NotesContactHint", source)
        self.assertIn("tdlib.contactNotes().saveContact(", source)
        self.assertIn("contactNoteLoaded", source)
        self.assertIn("contactNoteChanged", source)
        self.assertIn("tdlib.notes().isCurrent(contactNoteSession)", source)

    def test_contact_form_protects_async_loads_and_unsaved_edits(self):
        source = (UI / "EditNameController.java").read_text()
        self.assertTrue("setInProgress(true);" in source, "Contact save must actually lock edits")
        updated = method(source, "public void onUserFullUpdated (")
        self.assertTrue("!isDestroyed()" in updated, "Ignore callbacks after destruction")
        self.assertTrue("applyContactNote(userFull);" in updated, "Refresh unedited cloud note")
        self.assertTrue("showUnsavedChangesPromptBeforeLeaving" in source, "Do not discard edited notes")

    def test_import_schedules_contact_migration(self):
        source = (UI / "SettingsNotesController.java").read_text()
        imported = method(source, "private void importNotes (")
        self.assertIn("tdlib.contactNotes().migratePending();", imported)
        self.assertLess(imported.index(".importNotes(session, backup)"),
                        imported.index(".migratePending()"))

    def test_open_local_editor_routes_later_contact_edits_to_cloud(self):
        source = (UI / "EditNoteController.java").read_text()
        save = method(source, "protected boolean onDoneClick ()")
        self.assertTrue("TdlibContactNotes.isCloudContact(" in save,
                        "Recheck contact state before a local editor saves")
        self.assertTrue("tdlib.contactNotes().saveNote(" in save,
                        "An explicit edit must not be dropped by cloud-wins migration")

    def test_import_ignores_feedback_after_account_change(self):
        source = (UI / "SettingsNotesController.java").read_text()
        imported = method(source, "private void importNotes (")
        self.assertTrue("!isDestroyed() && tdlib.notes().isCurrent(session)" in imported,
                        "Do not show old account import success for a reused account slot")

    def test_local_and_cloud_hints_are_not_misleading(self):
        resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        strings = {node.attrib["name"]: "".join(node.itertext())
                   for node in resources if node.tag == "string"}
        self.assertEqual("Edit contact", strings.get("EditContact"))
        self.assertEqual("Add local note", strings["NotesAdd"])
        self.assertEqual("Edit local note", strings["NotesEdit"])
        self.assertIn("not synced", strings["NotesEditorHint"])
        self.assertIn("only visible to you", strings["NotesEditorHint"])
        self.assertIn("this device", strings["NotesEditorHint"])
        self.assertEqual("Notes are only visible to you", strings["NotesContactHint"])
        self.assertIn("local", strings["NotesBackupDescription"].lower())


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK, not Gradle")
class ContactNoteEditorBehaviorTest(unittest.TestCase):
    def test_real_editor_methods_and_widget_lock_order_with_pinned_tdapi(self):
        """Execute editor/widget methods with Android plumbing fixtures and actual TDLib API."""
        tdapi = ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"
        self.assertTrue(tdapi.exists(), "Initialize the pinned tdlib submodule before this check")
        source = (UI / "EditNameController.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "private boolean canEditContactNote ()",
            "private void applyContactNote (",
            "private void loadContactNote ()",
            "private boolean isGoodInput (",
            "protected boolean onDoneClick ()",
            "public void onResult (",
            "protected void onProgressStateChanged (",
            "public void onTextChanged (",
        ))
        widget_source = (ROOT / "app/src/main/java/org/thunderdog/challegram/widget/MaterialEditTextGroup.java").read_text()
        widget_methods = "\n".join(method(widget_source, signature) for signature in (
            "public void setBlockedText (CharSequence blockedText)",
            "public void setBlockedText (CharSequence blockedText, boolean animated)",
            "public void onTextChanged (CharSequence s, int start, int before, int count)",
        ))
        harness = r'''
import org.drinkless.tdlib.TdApi;
interface ResultHandler { void onResult(TdApi.Object result); }
public final class ContactNoteEditorHarness implements ResultHandler {
  static class Mode { static final int SIGNUP=0, RENAME_SELF=1, RENAME_CONTACT=2, ADD_CONTACT=3, RENAME_BOT=4; }
  static class R {
    static class id { static final int edit_first_name=1, edit_last_name=2, edit_contact_note=3; }
    static class string { static final int TermsOfService=1, TermsOfServiceDone=2; }
  }
  // Fixture account/network/Android plumbing; production editor and widget callbacks run below.
  static class TdlibNotes { static boolean enabled=true; static boolean isEnabled(){return enabled;} }
  static class NotesBackup { static String truncate(String text){return text==null?"":text;} }
  static class NoteStore {
    String local="Legacy local"; boolean current=true;
    boolean isCurrent(Object session){return current;}
    String get(long id){return local;}
  }
  static class Client {
    ResultHandler pending; int requests;
    void send(TdApi.Function<?> request,ResultHandler handler){pending=handler;requests++;}
    void complete(TdApi.Object result){ResultHandler handler=pending;pending=null;handler.onResult(result);}
  }
  static class UI { static void showError(TdApi.Object error){} }
  static class TD {
    static boolean canEditBot(TdApi.User user){return false;}
    static CharSequence formatString(Object controller,String text,TdApi.TextEntity[] entities,Object a,Object b){return text;}
  }
  static class Lang { static String getString(int id){return "";} }
  static class Args { TdApi.AuthorizationStateWaitRegistration authState=new TdApi.AuthorizationStateWaitRegistration(); }
  interface DialogClickListener { void onClick(Object dialog,int which); }
  static class ContactNotes {
    TdApi.AddContact savedContact; ResultHandler pending;
    void saveContact(Object session,TdApi.AddContact request,ResultHandler handler){savedContact=request;pending=handler;}
    void complete(TdApi.Object result){ResultHandler handler=pending;pending=null;handler.onResult(result);}
  }
  static class Ui { void post(Runnable runnable){runnable.run();} }
  static class Tdlib {
    NoteStore store=new NoteStore(); Client client=new Client(); Ui ui=new Ui(); ContactNotes contactNotes=new ContactNotes();
    long myUserId(){return 100;} NoteStore notes(){return store;}
    Client client(){return client;} Ui ui(){return ui;} ContactNotes contactNotes(){return contactNotes;}
  }
  static class ListItem {
    String text=""; ListItem setStringValue(String value){text=value;return this;}
    String getStringValue(){return text;} boolean isSelected(){return false;}
  }
  static class StringUtils {
    static boolean isEmpty(CharSequence text){return text==null || text.length()==0;}
    static String trim(CharSequence text){return text.toString().trim();}
  }
  static class EditText { static CharSequence nonModifiableCopy(CharSequence text){return text.toString();} }
  interface TextListener { void onTextChanged(MaterialEditTextGroup view, CharSequence text); }
  static class MaterialEditTextGroup {
    String text; CharSequence blockedText, lastInput; boolean ignoreChanges, useTextChangeAnimations, hasFocus;
    TextListener textListener;
    MaterialEditTextGroup(String text){this.text=text;}
    String getText(){return text;}
    void setText(CharSequence text){setText(text,false);}
    void setText(CharSequence text,boolean animated){this.text=text.toString();onTextChanged(text,0,0,text.length());}
    void updateIsActive(boolean value){} void updateRemainingCharCount(){}
    void forceAlphaFactor(float value){} void setIsNotEmpty(boolean value){}
    __WIDGET_METHODS__
  }
  class Adapter {
    MaterialEditTextGroup visible=new MaterialEditTextGroup("Legacy local");
    Adapter(){visible.blockedText="Legacy local";visible.textListener=(view,text)->onTextChanged(R.id.edit_contact_note,contactNote,view);}
    void updateLockEditTextById(int id,CharSequence text){if(id==R.id.edit_contact_note)visible.setBlockedText(text);}
    void updateSimpleItemById(int id){
      // SettingsAdapter binds setText BEFORE modifyEditText updates blockedText.
      visible.setText(contactNote.text);
      visible.setBlockedText(!contactNoteLoaded || isInProgress()?contactNote.text:null);
    }
  }
  Tdlib tdlib=new Tdlib(); TdApi.User user=new TdApi.User(); int mode=Mode.RENAME_CONTACT;
  ListItem contactNote=new ListItem(), firstName=new ListItem(), lastName=new ListItem(), shareMyNumber;
  String knownPhoneNumber; static final int ALERT_NO_CANCELABLE=1, ALERT_HAS_LINKS=2;
  Args getArgumentsStrict(){return new Args();}
  void openAlert(int id,CharSequence text,CharSequence button,DialogClickListener listener,int flags){}
  Object contactNoteSession=new Object(); String contactNoteOriginal="";
  boolean contactNoteLoaded, contactNoteChanged, inProgress, destroyed, saved;
  Adapter adapter=new Adapter(); boolean isInProgress(){return inProgress;} void updateDoneState(){}
  boolean isDestroyed(){return destroyed;}
  void setInProgress(boolean value){inProgress=value;onProgressStateChanged(value);}
  void setDoneInProgress(boolean value){} void hideSoftwareKeyboard(){} void onSaveCompleted(){saved=true;}
  __METHODS__
  static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
  static TdApi.UserFullInfo full(String text){
    TdApi.UserFullInfo info=new TdApi.UserFullInfo();
    info.note=text==null?null:new TdApi.FormattedText(text,new TdApi.TextEntity[0]);return info;
  }
  public static void main(String[] args){
    ContactNoteEditorHarness editor=new ContactNoteEditorHarness();
    editor.user.id=7; editor.user.type=new TdApi.UserTypeRegular();editor.user.isContact=true;
    check(editor.canEditContactNote(),"Regular contacts have cloud field");
    editor.user.isContact=false;check(!editor.canEditContactNote(),"Noncontact rename cannot edit cloud");
    editor.mode=Mode.ADD_CONTACT;check(editor.canEditContactNote(),"Adding a person offers cloud field");
    editor.user.type=new TdApi.UserTypeBot();check(!editor.canEditContactNote(),"Bots stay local");
    editor.user.type=new TdApi.UserTypeRegular();editor.user.id=100;
    check(!editor.canEditContactNote(),"Self stays local");editor.user.id=7;
    TdlibNotes.enabled=false;check(!editor.canEditContactNote(),"Existing visibility preference retained");
    TdlibNotes.enabled=true;editor.user.isContact=true;editor.mode=Mode.RENAME_CONTACT;
    ContactNoteEditorHarness initiallyLocked=new ContactNoteEditorHarness();
    initiallyLocked.user=editor.user;
    initiallyLocked.contactNote.text="Legacy local";
    initiallyLocked.applyContactNote(full("Initial cloud"));
    check(initiallyLocked.adapter.visible.text.equals("Initial cloud"),"Initial cloud text must survive blocked-widget rebind ordering");
    check(initiallyLocked.contactNote.text.equals("Initial cloud") && !initiallyLocked.contactNoteChanged,
      "Initial cloud load keeps the model clean and equal to visible text");
    check(initiallyLocked.adapter.visible.blockedText==null,"Cloud load unlocks the visible note");
    editor.applyContactNote(full(null));
    check(editor.contactNoteLoaded && editor.contactNote.text.equals("Legacy local"),"Unknown local migrates without field erasure");
    check(!editor.contactNoteChanged,"Programmatic load is not an edit");
    editor.applyContactNote(full("Cloud wins"));
    check(editor.contactNote.text.equals("Cloud wins") && !editor.contactNoteChanged,"Nonempty cloud takes precedence");
    editor.onTextChanged(R.id.edit_contact_note,editor.contactNote,new MaterialEditTextGroup("My edit"));
    check(editor.contactNoteChanged,"Typing marks note dirty");
    editor.applyContactNote(full("Later server update"));
    check(editor.contactNote.text.equals("My edit"),"Callbacks must never overwrite typing");
    editor.onTextChanged(R.id.edit_contact_note,editor.contactNote,new MaterialEditTextGroup(""));
    check(editor.contactNoteChanged && editor.contactNote.text.isEmpty(),"Blank intentional deletion stays dirty");
    editor.contactNoteChanged=false;editor.inProgress=true;
    editor.applyContactNote(full("While saving"));check(editor.contactNote.text.isEmpty(),"No refresh during save");
    editor.inProgress=false;editor.tdlib.store.current=false;
    editor.applyContactNote(full("Wrong identity"));check(editor.contactNote.text.isEmpty(),"Ignore old-account callback");
    ContactNoteEditorHarness failed=new ContactNoteEditorHarness();
    failed.user=editor.user;failed.contactNote.text="Legacy local";
    failed.loadContactNote();
    failed.firstName.text="Renamed";failed.onDoneClick(); // Actual name-only save before initial full info.
    check(failed.inProgress && failed.tdlib.contactNotes.savedContact.contact.note==null,
      "Name-only save locks edits and passes null while the cloud note is unknown");
    failed.tdlib.client.complete(full("Arrived during failed save"));
    check(!failed.contactNoteLoaded && failed.adapter.visible.blockedText!=null,"Initial note remains locked during save");
    failed.tdlib.contactNotes.complete(new TdApi.Error(400,"Save failed"));
    check(failed.tdlib.client.pending!=null,"Failed name-only save reloads initial note dropped during progress");
    failed.tdlib.client.complete(full("Arrived during failed save"));
    check(!failed.inProgress && failed.contactNoteLoaded && failed.adapter.visible.blockedText==null,
      "Failed save eventually unlocks the loaded note");
    check(failed.contactNote.text.equals("Arrived during failed save") &&
      failed.adapter.visible.text.equals(failed.contactNote.text) && !failed.contactNoteChanged,
      "Reload after failed save restores clean cloud text in model and widget");
    failed.onTextChanged(R.id.edit_contact_note,failed.contactNote,new MaterialEditTextGroup("Keep dirty edit"));
    failed.onDoneClick();failed.applyContactNote(full("Do not replace dirty note"));
    check(failed.tdlib.contactNotes.savedContact.contact.note.text.equals("Keep dirty edit"),
      "Dirty note save sends the intentional note edit");
    int beforeFailure=failed.tdlib.client.requests;
    failed.tdlib.contactNotes.complete(new TdApi.Error(400,"Save failed again"));
    check(failed.contactNote.text.equals("Keep dirty edit") && failed.contactNoteChanged &&
      failed.tdlib.client.requests==beforeFailure,"Failed note save preserves dirty edit without reload");
    ContactNoteEditorHarness wrongSession=new ContactNoteEditorHarness();
    wrongSession.user=editor.user;wrongSession.tdlib.store.current=false;wrongSession.setInProgress(true);
    wrongSession.onResult(new TdApi.Error(400,"Old account failure"));
    check(wrongSession.tdlib.client.requests==0,"Failure cannot reload notes for stale session");
    ContactNoteEditorHarness closed=new ContactNoteEditorHarness();closed.user=editor.user;
    closed.destroyed=true;closed.setInProgress(true);closed.onResult(new TdApi.Error(400,"Late failure"));
    check(closed.tdlib.client.requests==0,"Destroyed editor cannot reload on late save failure");
    System.out.println("Contact editor behavior passed against pinned TdApi");
  }
}
'''.replace("__METHODS__", methods).replace("__WIDGET_METHODS__", widget_methods)
        with tempfile.TemporaryDirectory(prefix="contact-note-ui-", dir=os.environ.get("TMPDIR")) as directory:
            base = Path(directory)
            files = {
                "ContactNoteEditorHarness.java": harness,
                "androidx/annotation/IntDef.java": "package androidx.annotation; public @interface IntDef { long[] value() default {}; boolean flag() default false; }",
                "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
            }
            for name, text in files.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            subprocess.run(["javac", "--release", "17", "-d", directory, str(tdapi),
                            *(str(base / name) for name in files)], check=True)
            subprocess.run(["java", "-cp", directory, "ContactNoteEditorHarness"], check=True)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK, not Gradle")
class NoteInputFilterBehaviorTest(unittest.TestCase):
    def test_actual_contact_filter_paste_replacement_and_unicode_boundaries(self):
        source = (UI / "EditNameController.java").read_text()
        note_field = source[source.index("items.add(contactNote ="):]
        chosen_filter = re.search(r"new (\w+)\(NotesBackup.MAX_NOTE_LENGTH\)", note_field)
        assert chosen_filter is not None, "Contact note must use a fixed-length input filter"
        filter_name = chosen_filter.group(1)
        imported_filter = re.search(r"import ([\w.]+\." + filter_name + r");", source)
        assert imported_filter is not None, "Compile the actual imported contact note filter"
        package = imported_filter.group(1)
        filter_source = (ROOT / "app/src/main/java" / (package.replace(".", "/") + ".java"))
        if not filter_source.exists():
            filter_source = ROOT / "vkryl/android/src/main/java" / (package.replace(".", "/") + ".java")
        limit_source = (ROOT / "app/src/main/java/org/thunderdog/challegram/util/NotesBackup.java").read_text()
        limit_match = re.search(r"MAX_NOTE_LENGTH = (\d+);", limit_source)
        assert limit_match is not None
        limit = int(limit_match.group(1))
        self.assertEqual(128, limit)
        harness = r'''
import android.text.InputFilter;
import android.text.Spanned;
import __FILTER_IMPORT__;
public class NoteInputFilterHarness {
  // Android Spanned subsequences retain/clip spans; this fixture models that contract.
  static class Text implements Spanned {
    final String text; final Object span; final int spanStart,spanEnd;
    Text(String text){this(text,null,-1,-1);}
    Text(String text,Object span,int start,int end){this.text=text;this.span=span;spanStart=start;spanEnd=end;}
    public int length(){return text.length();} public char charAt(int i){return text.charAt(i);}
    public String toString(){return text;}
    public CharSequence subSequence(int start,int end){
      return new Text(text.substring(start,end),span,Math.max(0,spanStart-start),Math.min(end-start,spanEnd-start));
    }
  }
  static int failures;
  static void check(boolean result,String name){if(!result){System.err.println("FAIL: "+name);failures++;}}
  static CharSequence filter(InputFilter filter,CharSequence source,int start,int end,String dest,int ds,int de){
    return filter.filter(source,start,end,new Text(dest),ds,de);
  }
  public static void main(String[] args){
    InputFilter f=new __FILTER_NAME__(__LIMIT__);
    String paste="a".repeat(129);
    check("a".repeat(128).contentEquals(filter(f,paste,0,paste.length(),"",0,0)),"129-codepoint paste into empty note retains 128, not one");
    check("b".repeat(4).contentEquals(filter(f,"b".repeat(10),0,10,"a".repeat(128),20,24)),"Selection replacement uses reclaimed codepoints");
    check("b".repeat(3).contentEquals(filter(f,"b".repeat(10),0,10,"a".repeat(125),1,1)),"Insertion uses remaining capacity, not destination end index");
    String sliced="PREFIX"+"b".repeat(129)+"SUFFIX";
    check("b".repeat(128).contentEquals(filter(f,sliced,6,135,"",0,0)),"Nonzero source slice counts only requested source range");
    String emoji="\ud83d\ude00";
    check(emoji.repeat(128).contentEquals(filter(f,emoji.repeat(129),0,258,"",0,0)),"Supplementary paste retains 128 complete codepoints");
    check(emoji.contentEquals(filter(f,emoji+"x",0,3,"a".repeat(127),0,0)),"Supplementary codepoint at boundary remains intact");
    check("b".repeat(2).contentEquals(filter(f,"b".repeat(5),0,5,emoji.repeat(128),2,6)),"Destination replacement counts supplementary codepoints");
    check(filter(f,"a".repeat(128),0,128,"",0,0)==null,"Exactly 128 is accepted unchanged");
    check(filter(f,"",0,0,"a".repeat(128),0,1)==null,"Deletion remains accepted at limit");
    check("".contentEquals(filter(f,"x",0,1,"a".repeat(128),0,0)),"Full note rejects insertion");
    Object marker=new Object();Text spanned=new Text(sliced,marker,6,135);
    CharSequence clipped=filter(f,spanned,6,135,"",0,0);
    check(clipped instanceof Text && ((Text)clipped).span==marker && ((Text)clipped).spanStart==0 && ((Text)clipped).spanEnd==128,
      "Truncated source keeps and clips spans via subsequence");
    if(failures!=0)throw new AssertionError(failures+" note filter regressions");
    System.out.println("Note input filter behavior passed (11 boundary cases)");
  }
}
'''.replace("__FILTER_IMPORT__", package).replace("__FILTER_NAME__", filter_name).replace("__LIMIT__", str(limit))
        with tempfile.TemporaryDirectory(prefix="note-input-filter-", dir=os.environ.get("TMPDIR")) as directory:
            base = Path(directory)
            files = {
                "NoteInputFilterHarness.java": harness,
                "android/text/InputFilter.java": "package android.text; public interface InputFilter { CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend); }",
                "android/text/Spanned.java": "package android.text; public interface Spanned extends CharSequence {}",
            }
            for name, text in files.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            subprocess.run(["javac", "--release", "17", "-d", directory, str(filter_source),
                            *(str(base / name) for name in files)], check=True)
            subprocess.run(["java", "-cp", directory, "NoteInputFilterHarness"], check=True)


if __name__ == "__main__":
    unittest.main()
