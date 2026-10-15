"""Exercise the shared album loader against interleaved forum history."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class AlbumPreviewHistoryTest(unittest.TestCase):
    def test_album_boundaries_follow_the_forum_topic(self):
        source = read("app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java")
        methods = "\n".join(java_method(source, signature) for signature in (
            "public static class Album {",
            "public void getAlbum (TdApi.Message message,",
            "public void getAlbum (List<TdApi.Message> album,",
        ))
        if "private TdApi.Function<TdApi.Messages> newAlbumHistoryRequest (" in source:
            methods += "\n" + java_method(source,
                "private TdApi.Function<TdApi.Messages> newAlbumHistoryRequest (")
        harness = r'''
import java.util.*;
import java.util.concurrent.atomic.*;
import org.drinkless.tdlib.TdApi;
public class AlbumHistoryTest {
  @interface Nullable {}
  interface RunnableData<T> {void runWithData(T value);}
  interface ResultHandler {void onResult(TdApi.Object result);}
  static class TdConstants {static final int MAX_MESSAGE_GROUP_SIZE = 10;}
  static class Log {static void i(String format, Object error) {}}
  static class TD {static String toErrorString(TdApi.Object error) {return "error";}}
  static class Td {
    static boolean equalsTo(TdApi.MessageTopic a, TdApi.MessageTopic b) {
      return a == b || (a instanceof TdApi.MessageTopicForum && b instanceof TdApi.MessageTopicForum &&
        ((TdApi.MessageTopicForum) a).forumTopicId == ((TdApi.MessageTopicForum) b).forumTopicId);
    }
  }
  static class Pending {
    TdApi.Function<?> request; ResultHandler handler;
    Pending(TdApi.Function<?> request, ResultHandler handler) {this.request = request; this.handler = handler;}
  }
  static class Client {
    ArrayDeque<Pending> pending = new ArrayDeque<>();
    ArrayList<TdApi.Function<?>> requests = new ArrayList<>();
    void send(TdApi.Function<?> request, ResultHandler handler) {
      requests.add(request); pending.add(new Pending(request, handler));
    }
    void reply(TdApi.Object response) {pending.remove().handler.onResult(response);}
  }
  Client client = new Client();
  Client client() {return client;}
  METHODS
  static void check(boolean value, String message) {if (!value) throw new AssertionError(message);}
  static TdApi.Message photo(long id, int topic, long album, String caption) {
    TdApi.Message message = new TdApi.Message();
    message.chatId = -100; message.id = id; message.mediaAlbumId = album;
    message.topicId = topic == 0 ? null : new TdApi.MessageTopicForum(topic);
    TdApi.MessagePhoto content = new TdApi.MessagePhoto();
    content.caption = new TdApi.FormattedText(caption, null); message.content = content;
    return message;
  }
  static TdApi.Messages messages(TdApi.Message... messages) {return new TdApi.Messages(messages.length, messages);}
  static void checkRequest(TdApi.Function<?> request, long anchor, int offset, int limit, boolean local) {
    if (local) {
      check(request instanceof TdApi.GetChatHistory, "Cache-only album requests remain offline");
      TdApi.GetChatHistory history = (TdApi.GetChatHistory) request;
      check(history.chatId == -100 && history.fromMessageId == anchor && history.offset == offset &&
        history.limit == limit && history.onlyLocal, "Keep local anchor, direction, and limit");
    } else {
      check(request instanceof TdApi.GetForumTopicHistory, "Remote album requests must stay inside the forum topic");
      TdApi.GetForumTopicHistory history = (TdApi.GetForumTopicHistory) request;
      check(history.chatId == -100 && history.forumTopicId == 42 && history.fromMessageId == anchor &&
        history.offset == offset && history.limit == limit, "Keep topic, anchor, direction, and limit");
    }
  }
  public static void main(String[] args) {
    TdApi.Message[] photos = new TdApi.Message[8];
    for (int i = 0; i < photos.length; i++) photos[i] = photo(100 + 2 * i, 42, 77, i == 0 ? "Album caption" : "");
    TdApi.Message unrelated = photo(113, 43, 0, "Other topic");
    TdApi.Message older = photo(99, 42, 0, "Before album");
    TdApi.Message newer = photo(115, 42, 0, "After album");
    AlbumHistoryTest loader = new AlbumHistoryTest();
    ArrayList<Album> results = new ArrayList<>();
    loader.getAlbum(photos[7], true, null, results::add);
    checkRequest(loader.client.requests.get(0), 114, 0, 10, true);
    checkRequest(loader.client.requests.get(1), 114, -10, 10, true);
    loader.client.reply(messages(photos[7], unrelated));
    check(results.isEmpty(), "Wait for both directions");
    loader.client.reply(messages(photos[7]));
    Album cached = results.get(0);
    check(cached.messages.size() == 1 && cached.mayHaveOlderItems && cached.mayHaveNewerItems,
      "Another topic must not mark the missing seven album photos complete");
    results.clear();
    loader.getAlbum(photos[7], false, cached, results::add);
    checkRequest(loader.client.requests.get(2), 114, 0, 10, false);
    checkRequest(loader.client.requests.get(3), 114, -10, 10, false);
    loader.client.reply(messages(photos[7], photos[6], photos[5], photos[4], photos[3], photos[2], photos[1], photos[0], older));
    loader.client.reply(messages(newer, photos[7]));
    Album complete = results.get(0);
    check(complete.messages.size() == 8 && !complete.mayHaveMoreItems(), "Recover every photo up to same-topic boundaries");
    check(complete.messages.get(0) == photos[7] && complete.messages.get(7) == photos[0], "Preserve newest-first ordering and the caption-bearing item");
    check(((TdApi.MessagePhoto) complete.messages.get(7).content).caption.text.equals("Album caption"), "Recover caption on oldest photo");
    results.clear();
    loader.getAlbum(photos[0], true, null, results::add);
    loader.client.reply(messages(photos[0], older));
    loader.client.reply(messages(photos[7], unrelated, photos[6], photos[5], photos[4], photos[3], photos[2], photos[1], photos[0]));
    cached = results.get(0);
    check(cached.messages.size() == 8 && cached.mayHaveNewerItems && !cached.mayHaveOlderItems,
      "Cached album lookup skips other topics in both directions");
    int requests = loader.client.requests.size();
    results.clear(); loader.getAlbum(photos[7], false, complete, results::add);
    check(results.get(0) == complete && loader.client.requests.size() == requests, "Complete albums do not fetch history again");
    results.clear(); loader.getAlbum(photo(120, 0, 88, ""), false, null, results::add);
    check(loader.client.requests.get(requests) instanceof TdApi.GetChatHistory &&
      !((TdApi.GetChatHistory) loader.client.requests.get(requests)).onlyLocal, "Ordinary chat remote loading keeps GetChatHistory");
    loader.client.reply(messages(photo(120, 0, 88, ""), photo(119, 0, 0, "")));
    loader.client.reply(messages(photo(121, 0, 0, ""), photo(120, 0, 88, "")));
    check(results.get(0).messages.size() == 1 && !results.get(0).mayHaveMoreItems(), "Ordinary chat album boundaries are preserved");
    results.clear(); loader.getAlbum(photo(200, 42, 77, ""), false, null, results::add);
    loader.client.reply(new TdApi.Error(500, "Temporary failure"));
    loader.client.reply(new TdApi.Error(500, "Temporary failure"));
    check(results.get(0).mayHaveMoreItems(), "Failures must not mark an incomplete album complete");
    results.clear(); loader.getAlbum(photo(300, 42, 0, "Single photo"), true, null, results::add);
    check(results.size() == 1 && results.get(0) == null && loader.client.pending.isEmpty(), "Non-album messages need no history requests");
    System.out.println("Eight-photo forum album, caption recovery, topic boundaries and offline cache checks passed");
  }
}
'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            sources = [ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"]
            for name, text in {
                "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
                "androidx/annotation/IntDef.java": "package androidx.annotation; public @interface IntDef {long[] value() default {};}",
                "AlbumHistoryTest.java": harness,
            }.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                sources.append(path)
            result = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                                     *map(str, sources)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", str(work), "AlbumHistoryTest"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
