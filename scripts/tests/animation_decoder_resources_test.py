"""Exercise decoder resource limits and repeated loading without an APK."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


class AnimationDecoderResourcesTest(unittest.TestCase):
  @unittest.skipUnless(shutil.which("c++"), "Requires a C++ compiler")
  def test_preview_decoders_do_not_request_per_cpu_worker_pools(self):
    method = java_method(read("app/jni/gif.cpp"), "int open_codec_context (")
    harness = r'''
#include <cassert>
#include <cerrno>
#include <initializer_list>

enum AVMediaType { AVMEDIA_TYPE_VIDEO };
constexpr int AV_CODEC_CAP_FRAME_THREADS = 1, AV_CODEC_CAP_SLICE_THREADS = 2;
constexpr int FF_THREAD_FRAME = 1, FF_THREAD_SLICE = 2, TAG_GIF_LOADER = 0;
#define AVERROR(value) (-(value))
#define loge(...) ((void) 0)
struct AVCodec { int capabilities; };
struct AVCodecContext { int thread_count = 0, thread_type = 3; };
struct AVCodecParameters { int codec_id = 1; };
struct AVStream { AVCodecParameters *codecpar; };
struct AVFormatContext { AVStream **streams; };
struct AVDictionary {};
AVCodec codec{};
int workers = 0, opened = 0, streamResult = 0, parameterResult = 0, openResult = 0;
int av_find_best_stream(AVFormatContext *, AVMediaType, int, int, void *, int) {
  return streamResult;
}
const AVCodec *avcodec_find_decoder(int) { return &codec; }
AVCodecContext *avcodec_alloc_context3(const AVCodec *) { return new AVCodecContext; }
int avcodec_parameters_to_context(AVCodecContext *, AVCodecParameters *) {
  return parameterResult;
}
void av_dict_set(AVDictionary **, const char *, const char *, int) {}
int avcodec_open2(AVCodecContext *context, const AVCodec *decoder, AVDictionary **) {
  // Model an eight-core device: FFmpeg's automatic frame pool uses cores + 1.
  int threads = context->thread_count == 0 ? 9 : context->thread_count;
  if (decoder->capabilities && threads > 1) workers += threads;
  ++opened;
  return openResult;
}
METHOD
int main() {
  AVCodecParameters parameters;
  AVStream stream{&parameters};
  AVStream *streams[] = {&stream};
  AVFormatContext format{streams};
  for (int capabilities : {0, AV_CODEC_CAP_FRAME_THREADS, AV_CODEC_CAP_SLICE_THREADS,
                          AV_CODEC_CAP_FRAME_THREADS | AV_CODEC_CAP_SLICE_THREADS}) {
    codec.capabilities = capabilities;
    workers = opened = 0;
    for (int index = 0; index < 50; ++index) {
      AVCodecContext *context = nullptr;
      int streamIndex = -1;
      assert(open_codec_context(&streamIndex, &context, &format, AVMEDIA_TYPE_VIDEO) == 0);
      assert(context && streamIndex == 0);
      delete context;
    }
    assert(opened == 50);
    assert(workers == 0);
  }
  AVCodecContext *context = nullptr;
  int streamIndex = -1;
  opened = 0;
  streamResult = -7;
  assert(open_codec_context(&streamIndex, &context, &format, AVMEDIA_TYPE_VIDEO) == -7);
  assert(!context && opened == 0);
  streamResult = 0;
  parameterResult = -8;
  assert(open_codec_context(&streamIndex, &context, &format, AVMEDIA_TYPE_VIDEO) == -8);
  assert(opened == 0);
  delete context;
  parameterResult = 0;
  openResult = -9;
  assert(open_codec_context(&streamIndex, &context, &format, AVMEDIA_TYPE_VIDEO) < 0);
  assert(opened == 1);
  delete context;
}
'''.replace("METHOD", method)
    with tempfile.TemporaryDirectory(prefix="animation-codec-") as directory:
      source = Path(directory) / "codec.cpp"
      executable = Path(directory) / "codec"
      source.write_text(harness)
      compiled = subprocess.run([
        "c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(executable)
      ], capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
      result = subprocess.run([str(executable)], capture_output=True, text=True, timeout=10)
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

  @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
  def test_duplicate_loads_keep_one_decoder_and_one_frame_queue(self):
    method = java_method(read(
      "app/src/main/java/org/thunderdog/challegram/loader/gif/GifActor.java"),
      "public void startDecoding (")
    harness = r'''
public class DecoderHarness {
  static class Bitmap { enum Config { ARGB_8888 } }
  static class Frame { Object bitmap; long no; }
  interface Init { boolean run(Frame frame); }
  static class GifState {
    static final int DEFAULT_QUEUE_SIZE = 3;
    static int created, recycled;
    GifState(int width, int height, int rotation, Object callback, int queueSize) {++created;}
    void setFrozen(boolean frozen) {}
    boolean init(Init init, int count, Bitmap.Config config) {return init.run(new Frame());}
    void recycle() {++recycled;}
  }
  static class GifFile {
    static class OptimizationMode {
      static final int EMOJI=0, STICKER_PREVIEW=1, EMOJI_PREVIEW=2, NONE=3;
    }
    void setTotalFrameCount(long count) {}
    int getFitzpatrickType() {return 0;}
    int getOptimizationMode() {return OptimizationMode.NONE;}
    int getRequestedSize() {return 0;}
    boolean needDecodeLastFrame() {return false;}
    boolean hasLooped() {return false;}
    boolean isStill() {return false;}
    void setIsStill(boolean still) {}
    double getStartMediaTimestamp() {return 0;}
  }
  static class N {
    static int created;
    static boolean failFrame;
    static long createDecoder(String path, long[] metadata, double start) {
      metadata[0]=320; metadata[1]=200;
      return ++created;
    }
    static long createLottieDecoder(String path, String json, double[] metadata, int fitz) {
      metadata[0]=30; metadata[1]=30; metadata[2]=1;
      return ++created;
    }
    static int getVideoFrame(long pointer, Object bitmap, long[] metadata) {
      return failFrame ? 0 : 1;
    }
    static boolean getLottieFrame(long pointer, Object bitmap, long frame) {return !failFrame;}
    static boolean isVideoBroken(long pointer) {return false;}
  }
  static class GifBridge {
    static final GifBridge INSTANCE = new GifBridge();
    int delivered;
    static GifBridge instance() {return INSTANCE;}
    void onGifLoaded(GifFile file, GifState state) {++delivered;}
  }
  static class U {
    static String gzipFileToString(String path) {return "{}";}
    static int getVideoRotation(String path) {return 0;}
  }
  static class StringUtils {static boolean isEmpty(String s) {return s.isEmpty();}}
  static class Screen {static int dp(float value) {return (int) value;}}
  static class TGMessageSticker {static final int MAX_STICKER_SIZE=512;}
  static class EmojiMediaListController {static int getEstimateColumnResolution() {return 100;}}
  static class StickersListController {static int getEstimateColumnResolution() {return 100;}}
  static class Log {
    static final int TAG_GIF_LOADER=0;
    static void w(int tag, String message, Throwable error) {}
  }
  final GifFile file = new GifFile();
  final long[] metadata = new long[4];
  final double[] lottieMetadata = new double[3];
  final Object nativeSync = new Object();
  boolean isLottie, cancelled, isPlayingRoundVideo;
  long nativePtr, totalFrameCount, lastTimeStamp;
  double frameRate, lastFrameNo;
  int destroyed;
  boolean isCancelled() {return cancelled;}
  void destroyDecoder() {if (nativePtr != 0) {++destroyed; nativePtr=0;}}
  METHOD
  static void check(boolean condition) {if (!condition) throw new AssertionError();}
  public static void main(String[] args) {
    for (boolean lottie : new boolean[] {false, true}) {
      N.created=GifState.created=GifState.recycled=GifBridge.INSTANCE.delivered=0;
      DecoderHarness actor = new DecoderHarness();
      actor.isLottie=lottie;
      actor.startDecoding("animation");
      long pointer=actor.nativePtr;
      for (int i=0; i<50; ++i) actor.startDecoding("animation");
      check(pointer != 0 && actor.nativePtr==pointer && N.created==1);
      check(GifState.created==1 && GifBridge.INSTANCE.delivered==1);
      actor.destroyDecoder();
      actor.cancelled=true;
      actor.startDecoding("animation");
      check(N.created==1 && actor.destroyed==1);

      actor = new DecoderHarness();
      actor.isLottie=lottie;
      N.failFrame=true;
      actor.startDecoding("animation");
      check(actor.nativePtr==0 && actor.destroyed==1 && GifState.recycled==1);
      N.failFrame=false;
      actor.startDecoding("animation");
      check(actor.nativePtr!=0 && N.created==3 && GifBridge.INSTANCE.delivered==2);
      actor.destroyDecoder();
      check(actor.destroyed==2);
    }
  }
}
'''.replace("METHOD", method)
    with tempfile.TemporaryDirectory(prefix="animation-actor-") as directory:
      source = Path(directory) / "DecoderHarness.java"
      source.write_text(harness)
      compiled = subprocess.run(["javac", "-d", directory, str(source)],
                                capture_output=True, text=True)
      self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
      result = subprocess.run(["java", "-cp", directory, "DecoderHarness"],
                              capture_output=True, text=True, timeout=10)
      self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
  unittest.main()
