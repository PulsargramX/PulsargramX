"""Exercise business location edits against the checked-in TDLib types, without Gradle."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/org/thunderdog/challegram/data/BusinessLocationDraft.java'

CONTRACTS = '''
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.BusinessLocationDraft;

public final class BusinessLocationContracts {
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    BusinessLocationDraft draft = new BusinessLocationDraft(null);
    check(!draft.hasChanges() && draft.isEmpty() && draft.isValid(), "empty initial profile");
    check(draft.toLocation() == null, "absence is not a fabricated location");
    draft.setAddress("  Example Street 8  ");
    check(draft.hasChanges() && draft.isValid(), "address without coordinates is supported");
    TdApi.BusinessLocation addressOnly = draft.toLocation();
    check(addressOnly.location == null && addressOnly.address.equals("Example Street 8"),
      "address-only request preserves absence of coordinates");
    check(new TdApi.SetBusinessLocation(addressOnly).location == addressOnly, "TDLib save payload");

    draft = new BusinessLocationDraft(addressOnly);
    check(!draft.hasChanges(), "existing address does not need saving");
    draft.setLocation(new TdApi.Location(-12.5, 42.75, 10), "Resolved address");
    check(draft.address().equals("Example Street 8"), "map selection preserves custom address");
    check(draft.hasChanges() && draft.toLocation().location.latitude == -12.5, "add map pin");
    draft.setLocation(null, null);
    check(!draft.hasChanges(), "removing new pin restores original address-only state");

    TdApi.BusinessLocation mapped = new TdApi.BusinessLocation(
      new TdApi.Location(15, -30, 5), "An address");
    draft = new BusinessLocationDraft(mapped);
    check(!draft.hasChanges(), "existing mapped address does not need saving");
    draft.setLocation(new TdApi.Location(15, -30, 100), null);
    check(!draft.hasChanges(), "accuracy alone is not a business location change");
    draft.setLocation(new TdApi.Location(15, -31, 0), null);
    check(draft.hasChanges(), "longitude changes are saved");
    draft.setLocation(new TdApi.Location(16, -30, 0), null);
    check(draft.hasChanges(), "latitude changes are saved");
    draft.setLocation(null, null);
    check(draft.isValid() && draft.toLocation().location == null, "remove only map pin");
    draft.setAddress("  ");
    check(draft.hasChanges() && draft.isEmpty() && draft.isValid(), "remove entire location");
    check(new TdApi.SetBusinessLocation(draft.toLocation()).location == null, "TDLib removal payload");

    draft = new BusinessLocationDraft(null);
    draft.setLocation(new TdApi.Location(0, 0, 0), null);
    check(!draft.isEmpty() && !draft.isValid(), "map pin requires an address, including at 0,0");
    try {
      draft.toLocation();
      throw new AssertionError("invalid pin was submitted");
    } catch (IllegalStateException expected) { }
    draft.setAddress("x".repeat(96));
    check(draft.isValid(), "96 character address accepted");
    draft.setAddress("x".repeat(97));
    check(!draft.isValid(), "overlong address rejected");

    draft = new BusinessLocationDraft(null);
    draft.setLocation(new TdApi.Location(1, 2, 0), "First geocoded address");
    check(draft.address().equals("First geocoded address"), "geocode fills empty address");
    draft.setLocation(new TdApi.Location(3, 4, 0), "Second geocoded address");
    check(draft.address().equals("Second geocoded address"), "geocoded address follows moved pin");
    draft.setAddress("Custom address");
    draft.setLocation(new TdApi.Location(5, 6, 0), "Third geocoded address");
    check(draft.address().equals("Custom address"), "later geocodes preserve user edits");

    draft = new BusinessLocationDraft(mapped);
    mapped.address = "Incoming profile change";
    mapped.location.latitude = 80;
    check(draft.address().equals("An address") && !draft.hasChanges(), "edit snapshot is independent");
    TdApi.Location pin = draft.location();
    pin.latitude = 70;
    TdApi.BusinessLocation saved = draft.toLocation();
    saved.address = "Mutated request";
    saved.location.longitude = 80;
    check(!draft.hasChanges() && draft.location().latitude == 15, "getters and requests are copies");

    TdApi.UserFullInfo full = new TdApi.UserFullInfo();
    check(BusinessLocationDraft.fromUser(null) == null, "unloaded profile");
    check(BusinessLocationDraft.fromUser(full) == null, "non-business profile");
    full.businessInfo = new TdApi.BusinessInfo();
    check(BusinessLocationDraft.fromUser(full) == null, "business profile without a location");
    full.businessInfo.location = addressOnly;
    check(BusinessLocationDraft.fromUser(full) == addressOnly, "location read from TDLib profile");
    full.businessInfo.location = null;
    check(BusinessLocationDraft.fromUser(full) == null, "server removal is immediately reflected");
  }
}
'''


def method(source, signature):
  start = source.index(signature)
  opening = source.index('{', start)
  depth = 1
  end = opening + 1
  while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
  return source[start:end]


def row_contracts():
  ui = ROOT / 'app/src/main/java/org/thunderdog/challegram/ui'
  profile = (ui / 'ProfileController.java').read_text()
  settings = (ui / 'SettingsController.java').read_text()
  return '''
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.BusinessLocationDraft;
import java.util.*;

public final class BusinessLocationRows {
  static class R { static class id {
    static final int btn_businessLocation=1, btn_profileNote=2, btn_phone=3,
      btn_encryptionKey=4, btn_notifications=5, btn_peer_id=6, btn_username=7,
      btn_birthdate=8, btn_description=9, btn_personalChannel=10, btn_bio=11;
  }}
  static class ListItem {
    static final int TYPE_SHADOW_BOTTOM=1, TYPE_SHADOW_TOP=2, TYPE_RADIO_SETTING=3,
      TYPE_SEPARATOR_FULL=4, TYPE_SEPARATOR=5, TYPE_MEMBERS_LIST=6;
    int type, id;
    ListItem(int type) { this.type=type; }
    ListItem(int type, int id) { this.type=type; this.id=id; }
    int getId() { return id; }
    int getViewType() { return type; }
    void setViewType(int type) { this.type=type; }
  }
  static class BusinessLocationUi {
    static ListItem newItem() { return new ListItem(7, R.id.btn_businessLocation); }
  }
  static class Adapter {
    final ArrayList<ListItem> items=new ArrayList<>();
    int updates;
    ArrayList<ListItem> getItems() { return items; }
    int indexOfViewById(int id) {
      for(int i=0;i<items.size();i++) if(items.get(i).id==id) return i;
      return -1;
    }
    void notifyItemRangeInserted(int start,int count) {}
    void notifyItemRangeRemoved(int start,int count) {}
    void notifyItemChanged(int index) {}
    void updateValuedSettingById(int id) { updates++; }
    String snapshot() { return items.stream().map(i->i.type+":"+i.id).toList().toString(); }
  }
  static class Profile {
    final Adapter baseAdapter=new Adapter();
    TdApi.UserFullInfo userFull=full();
    TdApi.User user=new TdApi.User();
    int baseHeaderItemCount;
    static final int OPEN_WEB_APP_ID=12, WEB_APP_PREVIEWS_ID=13;
    static class Tdlib { boolean isSelfUserId(long id) { return id==1; } }
    final Tdlib tdlib=new Tdlib();
    boolean isEditing() { return false; }
    void onItemsHeightProbablyChanged() {}
    void updateValuedItem(int id) { baseAdapter.updates++; }
''' + '\n'.join(method(profile, signature) for signature in [
    'private void checkBusinessLocation ()', 'private void addTopItem (',
    'private void removeTopItem ('
  ]) + '''
  }
  static class Settings {
    final Adapter adapter=new Adapter();
    TdApi.UserFullInfo userFull=full();
''' + method(settings, 'private void checkBusinessLocation ()') + '''
  }
  static TdApi.UserFullInfo full() {
    TdApi.UserFullInfo result=new TdApi.UserFullInfo();
    result.businessInfo=new TdApi.BusinessInfo();
    return result;
  }
  static void check(boolean value,String message) {
    if(!value) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    // Exercise the production list mutation methods with loaded and delayed profile data.
    for(boolean self : new boolean[]{false,true}) {
      for(boolean populated : new boolean[]{false,true}) {
        for(boolean channel : new boolean[]{false,true}) {
          Profile p=new Profile();
          p.user.id=self?1:2;
          p.baseAdapter.items.add(new ListItem(0));
          if(channel) {
            p.baseAdapter.items.add(new ListItem(7,R.id.btn_personalChannel));
            p.baseAdapter.items.add(new ListItem(0));
            p.baseAdapter.items.add(new ListItem(0));
          }
          if(populated) {
            int[] ids={R.id.btn_username,R.id.btn_birthdate,R.id.btn_description,
              R.id.btn_profileNote,R.id.btn_phone};
            for(int id:ids) {
              if(p.baseHeaderItemCount++>0) p.baseAdapter.items.add(new ListItem(5));
              p.baseAdapter.items.add(new ListItem(7,id));
            }
            p.baseAdapter.items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
            if(!self) p.baseAdapter.items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
          }
          if(!self) p.baseAdapter.items.add(new ListItem(7,R.id.btn_notifications));
          p.baseAdapter.items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
          String initial=p.baseAdapter.snapshot();
          int count=p.baseHeaderItemCount;
          p.checkBusinessLocation();
          check(initial.equals(p.baseAdapter.snapshot()),"no phantom location while loading");
          p.userFull.businessInfo.location=new TdApi.BusinessLocation(null,"Street 8");
          p.checkBusinessLocation();
          check(p.baseHeaderItemCount==count+1,"header count increases once");
          int location=p.baseAdapter.indexOfViewById(R.id.btn_businessLocation);
          check(location>=0,"loaded location inserted");
          if(populated) {
            check(location>p.baseAdapter.indexOfViewById(R.id.btn_description),"after bio");
            check(location<p.baseAdapter.indexOfViewById(R.id.btn_profileNote),"before note");
          }
          String inserted=p.baseAdapter.snapshot();
          p.userFull.businessInfo.location.address="Updated address";
          p.checkBusinessLocation();
          check(inserted.equals(p.baseAdapter.snapshot())&&p.baseAdapter.updates==1,
            "address updates rebind without inserting duplicate rows");
          p.userFull.businessInfo.location=null;
          p.checkBusinessLocation();
          check(p.baseHeaderItemCount==count&&initial.equals(p.baseAdapter.snapshot()),
            "server removal restores rows and separators");
        }
      }
    }
    Settings s=new Settings();
    s.adapter.items.add(new ListItem(7,R.id.btn_bio));
    s.adapter.items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    String initial=s.adapter.snapshot();
    s.userFull.businessInfo.location=new TdApi.BusinessLocation(null,"Street 8");
    s.checkBusinessLocation();
    check(s.adapter.indexOfViewById(R.id.btn_businessLocation)==2,"own location follows bio");
    s.checkBusinessLocation();
    check(s.adapter.items.size()==4&&s.adapter.updates==1,"own location rebinds");
    s.userFull.businessInfo=null;
    s.checkBusinessLocation();
    check(initial.equals(s.adapter.snapshot()),"own location removal restores section");
  }
}
'''


class BusinessLocationTest(unittest.TestCase):
  def test_edit_contracts(self):
    javac = shutil.which('javac')
    self.assertIsNotNone(javac, 'A JDK is required for location edit tests')
    java = str(Path(javac).with_name('java'))
    with tempfile.TemporaryDirectory(prefix='business-location-tests-') as directory:
      work = Path(directory)
      annotation = work / 'androidx/annotation/Nullable.java'
      annotation.parent.mkdir(parents=True)
      annotation.write_text('package androidx.annotation; public @interface Nullable {}')
      int_def = annotation.with_name('IntDef.java')
      int_def.write_text('package androidx.annotation; public @interface IntDef { '
        'long[] value() default {}; boolean flag() default false; }')
      contracts = work / 'BusinessLocationContracts.java'
      contracts.write_text(CONTRACTS)
      rows = work / 'BusinessLocationRows.java'
      rows.write_text(row_contracts())
      classes = work / 'classes'
      result = subprocess.run([
        javac, '-encoding', 'UTF-8', '-d', str(classes), str(annotation), str(int_def), str(SOURCE),
        str(ROOT / 'tdlib/src/main/java/org/drinkless/tdlib/TdApi.java'), str(contracts), str(rows)
      ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([java, '-cp', str(classes), 'BusinessLocationRows'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)
      result = subprocess.run([java, '-cp', str(classes), 'BusinessLocationContracts'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
      self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == '__main__':
  unittest.main()
