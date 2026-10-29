"""Run image loader cancellation/rebinding with deterministic thread ordering."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method

ROOT = Path(__file__).resolve().parents[2]
LOADER = ROOT / "app/src/main/java/org/thunderdog/challegram/loader"


def standalone(source):
    return re.sub(r"^(?:package|import) .*;\n", "", source, flags=re.MULTILINE).replace(
        "public class ", "class ").replace("public interface ", "interface ")


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class ImageLoaderLifecycleTest(unittest.TestCase):
    def check_lifecycle(self, sources):
        actor_result = java_method(sources["ImageActor"], "public void onImageLoaded (")
        production = "\n".join(standalone(sources[name]) for name in (
            "ImageLoader", "ImageThread", "ImageWatchers", "WatcherReference", "Watcher"))
        fixture = r"""
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.ref.*;
import java.util.function.Consumer;

@interface Keep {}
class ArraySet<T> extends HashSet<T> {}
class Bitmap {
  int recycles;
  void recycle() {if (++recycles != 1) throw new AssertionError("Double recycle");}
}
class Message {
  int what, arg1, arg2;
  Object obj;
  static Message obtain(Object handler, int what, Object obj) {
    return obtain(handler, what, 0, 0, obj);
  }
  static Message obtain(Object handler, int what, int arg1, int arg2) {
    return obtain(handler, what, arg1, arg2, null);
  }
  static Message obtain(Object handler, int what, int arg1, int arg2, Object obj) {
    Message m = new Message(); m.what=what; m.arg1=arg1; m.arg2=arg2; m.obj=obj; return m;
  }
}
abstract class BaseThread extends Thread {
  final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
  final AtomicReference<Throwable> failure = new AtomicReference<>();
  BaseThread(String name) {super(name); setDaemon(true); start();}
  Object getHandler() {return this;}
  void sendMessage(Message message, long delay) {queue.add(() -> process(message));}
  abstract protected void process(Message message);
  public void run() {
    while (true) {
      try {queue.take().run();} catch (Throwable error) {failure.set(error);}
    }
  }
  void idle() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    queue.add(done::countDown);
    if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("Loader did not finish");
    if (failure.get() != null) throw new AssertionError(failure.get());
  }
}
class TdApi {
  static class Object {int getConstructor() {return 0;}}
  static class File extends Object {
    static final int CONSTRUCTOR = 1;
    int id;
    RemoteFile remote = new RemoteFile();
  }
  static class RemoteFile {String id = "remote";}
  static class Error extends Object {static final int CONSTRUCTOR = 2;}
  static class DownloadFile {DownloadFile(int id, int priority, int offset, int limit, boolean sync) {}}
  static class CancelDownloadFile {CancelDownloadFile(int id, boolean onlyPending) {}}
}
class Tdlib {
  int id() {return 1;}
  void send(Object request, Object handler) {}
  Object imageLoadHandler() {return null;}
  Object typedOkHandler() {return null;}
}
class TdlibAccount {static final int NO_ID = -1;}
class TdlibFilesManager {static final int PRIORITY_IMAGE=1, PRIORITY_PERSISTENT_IMAGE=2;}
class Config {static final boolean DEBUG_DISABLE_DOWNLOAD = false;}
class TD {
  static boolean isFileLoadedAndExists(TdApi.File file) {return false;}
  static boolean isFileLoaded(TdApi.File file) {return false;}
  static float getFileProgress(TdApi.File file) {return 0;}
}
class Log {
  static final int TAG_IMAGE_LOADER = 1;
  static boolean isEnabled(int tag) {return false;}
  static void d(int tag, String format, Object... args) {}
  static void v(int tag, String format, Object... args) {}
  static void w(int tag, String format, Object... args) {}
  static void i(int tag, String format, Object... args) {}
  static void e(String message, Throwable error) {}
}
class UI {static void showError(Object error) {}}
class Palette {
  static Palette from(Bitmap bitmap) {return new Palette();}
  Palette generate() {return this;}
  Object getDarkVibrantSwatch() {return null;}
}
class ImageCache {
  static final ImageCache INSTANCE = new ImageCache();
  static ImageCache instance() {return INSTANCE;}
  void putBitmap(ImageFile file, Bitmap bitmap) {}
  void clearForAccount(int id) {}
  void clear(boolean withMemcache) {}
}
class ImageFile {
  final Tdlib tdlib = new Tdlib();
  final TdApi.File file = new TdApi.File();
  final String key;
  boolean cached = true;
  ImageFile(String key, int id) {this.key=key; file.id=id;}
  public String toString() {return key;}
  int getId() {return file.id;}
  TdApi.File getFile() {return file;}
  String getFilePath() {return "image";}
  Tdlib tdlib() {return tdlib;}
  int accountId() {return tdlib.id();}
  String getFileLoadKey() {return getFileLoadKey(tdlib, getId());}
  static String getFileLoadKey(Tdlib tdlib, int id) {return tdlib.id() + "_" + id;}
  static String getFileLoadKey(Tdlib tdlib, String id) {return tdlib.id() + "_" + id;}
  boolean needCancellation() {return true;}
  boolean isCancellationOnlyPending() {return false;}
  boolean shouldBeCached() {return cached;}
  boolean needPalette() {return false;}
  void setPalette(Object palette) {}
  void updateFile(TdApi.File file) {}
}
class ImageFileRemote extends ImageFile {
  ImageFileRemote() {super("remote", 1);}
  void extractFile(Consumer<TdApi.Object> callback) {}
  void updateRemoteFile(TdApi.File file) {}
  boolean isRemoteFileReady() {return false;}
}
class ImageReader {interface Listener {void onImageLoaded(boolean success, Bitmap bitmap);}}
class ImageActor {
  static final List<ImageActor> created = new ArrayList<>();
  final ImageFile file;
  boolean isCancelled;
  int loads;
  ImageActor(ImageFile file) {this.file=file; created.add(this);}
  boolean act() {return true;}
  void cancel() {isCancelled=true;}
  void watcherJoined(WatcherReference watcher) {}
  void onProgress(TdApi.File file) {}
  void onLoad(TdApi.File file) {loads++;}
  ACTOR_RESULT
}
PRODUCTION

public class ImageLoaderLifecycleHarness {
  static final ImageLoader loader = ImageLoader.instance();
  static ImageThread thread;
  static class Preview implements Watcher {
    int failures, successes;
    Bitmap displayed;
    public void imageLoaded(ImageFile file, boolean success, Bitmap bitmap) {
      if (success) {successes++; displayed=bitmap;} else {failures++; displayed=null;}
    }
  }
  static class Request {
    final Preview preview = new Preview();
    final WatcherReference watcher = new WatcherReference(preview);
    final ImageFile file;
    final ImageActor actor;
    Request(ImageFile file) throws Exception {
      this.file=file;
      loader.requestFile(file, watcher);
      thread.idle();
      actor=ImageActor.created.get(ImageActor.created.size()-1);
    }
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  static void cancel(Request request) throws Exception {
    loader.removeWatcher(request.watcher);
    thread.idle();
    check(request.actor.isCancelled, "Last detached preview must cancel its actor");
  }
  static void finish(Request request) throws Exception {
    Bitmap bitmap = new Bitmap();
    request.actor.onImageLoaded(true, bitmap);
    thread.idle();
    check(request.preview.failures == 0 && request.preview.successes == 1 &&
      request.preview.displayed == bitmap && bitmap.recycles == 0,
      "Current preview did not receive its decoded bitmap");
  }
  static void lateResult(boolean success, boolean sameFile) throws Exception {
    ImageFile file = new ImageFile("photo", 1);
    Request old = new Request(file);
    cancel(old);
    Request current = new Request(sameFile ? file : new ImageFile("photo", 1));
    old.actor.onImageLoaded(success, success ? new Bitmap() : null);
    thread.idle();
    check(current.preview.failures == 0 && current.preview.successes == 0,
      "Cancelled actor delivered a stale result to a newly bound preview");
    check(loader.onLoad(current.file.tdlib(), current.file.getFile()),
      "Cancelled actor removed current download tracking");
    thread.idle();
    check(current.actor.loads == 1, "Current decode must still receive download completion");
    finish(current);
    check(old.preview.successes == 0 && old.preview.failures == 0,
      "Detached preview received a result");
  }
  public static void main(String[] args) throws Exception {
    java.lang.reflect.Field field = ImageLoader.class.getDeclaredField("thread");
    field.setAccessible(true);
    thread = (ImageThread) field.get(loader);
    for (boolean success : new boolean[] {false, true}) {
      for (boolean sameFile : new boolean[] {false, true}) lateResult(success, sameFile);
    }

    // Two resolutions share one download but have separate decoding actors.
    Request old = new Request(new ImageFile("small", 2));
    cancel(old);
    Request small = new Request(new ImageFile("small", 2));
    Request large = new Request(new ImageFile("large", 2));
    old.actor.onImageLoaded(false, null);
    thread.idle();
    finish(small);
    check(loader.onLoad(large.file.tdlib(), large.file.getFile()), "Sibling download lost");
    thread.idle();
    check(large.actor.loads == 1, "Sibling decode lost");
    finish(large);

    // A successful result already queued before cancellation must also be ignored.
    Request uncached = new Request(new ImageFile("uncached", 3));
    uncached.file.cached=false;
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    thread.queue.add(() -> {
      entered.countDown();
      try {release.await();} catch (InterruptedException e) {throw new AssertionError(e);}
    });
    check(entered.await(5, TimeUnit.SECONDS), "Could not pause loader");
    loader.removeWatcher(uncached.watcher);
    Preview rebound = new Preview();
    WatcherReference reboundWatcher = new WatcherReference(rebound);
    loader.requestFile(uncached.file, reboundWatcher);
    Bitmap stale = new Bitmap();
    uncached.actor.onImageLoaded(true, stale);
    release.countDown();
    thread.idle();
    check(stale.recycles == 1 && rebound.successes == 0,
      "Stale uncached result must be recycled without reaching the current preview");
    ImageActor current = ImageActor.created.get(ImageActor.created.size()-1);
    Bitmap bitmap = new Bitmap();
    current.onImageLoaded(true, bitmap);
    thread.idle();
    check(rebound.successes == 1 && rebound.displayed == bitmap && bitmap.recycles == 0,
      "Uncached current result was lost or recycled");

    Request shared = new Request(new ImageFile("shared", 4));
    Preview second = new Preview();
    WatcherReference secondWatcher = new WatcherReference(second);
    int actors = ImageActor.created.size();
    loader.requestFile(shared.file, secondWatcher);
    thread.idle();
    check(ImageActor.created.size() == actors, "Matching requests must share a decode");
    finish(shared);
    check(second.successes == 1 && second.displayed == shared.preview.displayed,
      "Shared watcher did not receive the result");

    Request failed = new Request(new ImageFile("failed", 5));
    failed.actor.onImageLoaded(false, null);
    thread.idle();
    check(failed.preview.failures == 1 && failed.preview.successes == 0,
      "Current failures must still be delivered");
    check(!loader.onLoad(failed.file.tdlib(), failed.file.getFile()),
      "Completed failure retained download tracking");
    System.out.println("Image loader cancellation, rebinding and result ownership passed");
  }
}
""".replace("ACTOR_RESULT", actor_result).replace("PRODUCTION", production)
        with tempfile.TemporaryDirectory(prefix="image-loader-lifecycle-") as directory:
            java = Path(directory) / "ImageLoaderLifecycleHarness.java"
            java.write_text(fixture, encoding="utf-8")
            result = subprocess.run(["javac", "-encoding", "UTF-8", str(java)],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(["java", "-cp", directory, "ImageLoaderLifecycleHarness"],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_cancelled_decode_cannot_complete_a_new_request(self):
        self.check_lifecycle({name: (LOADER / f"{name}.java").read_text(encoding="utf-8")
                              for name in ("ImageActor", "ImageLoader", "ImageThread",
                                           "ImageWatchers", "WatcherReference", "Watcher")})


if __name__ == "__main__":
    unittest.main()
