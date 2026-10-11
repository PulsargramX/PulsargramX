#!/usr/bin/env python3
"""Exercise shared poll metadata and actual live row replacement without Android."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class SharedPollArchiveTest(unittest.TestCase):
    def test_vote_policy_and_actual_archive_action_selection(self):
        actions = read("app/src/main/java/org/thunderdog/challegram/ui/SharedPollActions.java")
        methods = "\n".join(java_method(actions, signature) for signature in (
            "private void showMenu (", "private static boolean hasOptionMedia (",
            "private static void add (", "private static int[] integers ("))
        harness = r'''
import java.util.*;
import java.util.function.Consumer;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.SharedPollVotePolicy;
import org.thunderdog.challegram.data.PollResultPolicy;
class SharedPollActionHarness {
  long chatId = 1, messageId = 2;
  Object tdlib;
  Owner owner = new Owner();
  interface Callback {boolean onClick(Object view, int id);}
  static class Owner {
    int[] ids;
    boolean isDestroyed() {return false;}
    void showOptions(Object question, int[] ids, String[] labels, Callback callback) {this.ids = ids;}
    Object context() {return null;}
    void navigateTo(Object controller) {}
  }
  static class PollStatisticsController {
    PollStatisticsController(Object context, Object tdlib) {}
    void setArguments(Args args) {}
    static class Args {Args(long chatId, long messageId) {}}
  }
  void showInChat() {}
  void fresh(Consumer<TdApi.Message> action) {}
  void showVote(TdApi.Message message) {}
  void submit(List<String> ids, boolean retract, boolean confirmed) {}
  void showResults(TdApi.Message message) {}
  void showVoters(TdApi.Message message, String option) {}
  void showMedia(TdApi.Message message) {}
  void showExplanation(TdApi.Message message) {}
  static class TD {static Object toCharSequence(TdApi.FormattedText text) {return text;}}
  static class Lang {static String getString(int id) {return "label";}}
  static class R {static class string {
    static final int Vote=1, RetractVote=2, ViewPollResults=3, PollResultsTitle=4,
      PollVoteStatistics=5, Open=6, ShowInChat=7, QuizExplanation=8;
  }}
  METHODS
  static void check(boolean condition) {if (!condition) throw new AssertionError();}
  static TdApi.PollOption option(String id) {
    TdApi.PollOption option = new TdApi.PollOption(); option.id = id;
    option.text = new TdApi.FormattedText(id, null); return option;
  }
  static void checkActions(SharedPollActionHarness h, TdApi.Message message,
                           TdApi.MessageProperties properties, int... ids) {
    h.showMenu(message, properties); check(Arrays.equals(h.owner.ids, ids));
  }
  public static void main(String[] args) {
    TdApi.Poll poll = new TdApi.Poll(); poll.type = new TdApi.PollTypeRegular();
    poll.options = new TdApi.PollOption[]{option("a"), option("b"), option("c")};
    poll.optionOrder = new int[]{2, 2, -1, 8, 0};
    check(Arrays.equals(SharedPollVotePolicy.displayOrder(poll), new int[]{2,0,1}));
    check(SharedPollVotePolicy.canVote(poll));
    check(Arrays.equals(SharedPollVotePolicy.resolve(poll, Arrays.asList("c"), false), new int[]{2}));
    check(SharedPollVotePolicy.resolve(poll, Arrays.asList("a", "c"), false) == null);
    poll.allowsMultipleAnswers = true;
    check(Arrays.equals(SharedPollVotePolicy.resolve(poll, Arrays.asList("a", "c"), false), new int[]{0,2}));
    poll.options = new TdApi.PollOption[]{option("c"), option("a"), option("b")};
    check(Arrays.equals(SharedPollVotePolicy.resolve(poll, Arrays.asList("a", "c"), false), new int[]{1,0}));
    check(SharedPollVotePolicy.resolve(poll, Arrays.asList("deleted"), false) == null);
    check(SharedPollVotePolicy.resolve(poll, Arrays.asList("a", "a"), false) == null);
    check(SharedPollVotePolicy.resolve(poll, Arrays.asList(""), false) == null);
    check(SharedPollVotePolicy.resolve(poll, Collections.emptyList(), false) == null);
    check(SharedPollVotePolicy.resolve(poll, Collections.emptyList(), true) == null);
    poll.options[0].isChosen = true;
    check(!SharedPollVotePolicy.canVote(poll));
    poll.allowsRevoting = true;
    check(SharedPollVotePolicy.resolve(poll, Collections.emptyList(), true).length == 0);
    check(SharedPollVotePolicy.resolve(poll, Arrays.asList("a"), true) == null);
    poll.options[1].isBeingChosen = true;
    check(!SharedPollVotePolicy.canVote(poll)); poll.options[1].isBeingChosen = false;
    poll.voteRestrictionReason = new TdApi.PollVoteRestrictionReasonMembershipRequired();
    check(!SharedPollVotePolicy.canVote(poll)); poll.voteRestrictionReason = null;
    poll.isClosed = true; check(!SharedPollVotePolicy.canVote(poll)); poll.isClosed = false;

    SharedPollActionHarness h = new SharedPollActionHarness();
    TdApi.Message message = new TdApi.Message();
    TdApi.MessagePoll content = new TdApi.MessagePoll(); content.poll = poll; message.content = content;
    TdApi.MessageProperties properties = new TdApi.MessageProperties();
    checkActions(h, message, properties, 1, 2, 7);
    poll.canGetVoters = true; poll.canSeeResults = false;
    checkActions(h, message, properties, 1, 2, 7);
    poll.canSeeResults = true;
    checkActions(h, message, properties, 1, 2, 3, 4, 7);
    poll.isAnonymous = true;
    checkActions(h, message, properties, 1, 2, 3, 7);
    poll.canGetVoters = false; poll.isAnonymous = false;
    checkActions(h, message, properties, 1, 2, 3, 7);
    poll.allowsRevoting = false;
    checkActions(h, message, properties, 3, 7);
    properties.canGetPollVoteStatistics = true;
    poll.options[0].media = new TdApi.PollMediaLink();
    checkActions(h, message, properties, 3, 5, 6, 7);
    TdApi.PollTypeQuiz quiz = new TdApi.PollTypeQuiz(); poll.type = quiz;
    quiz.explanation = new TdApi.FormattedText("Explanation", null);
    checkActions(h, message, properties, 3, 5, 6, 8, 7);
    poll.canSeeResults = false;
    checkActions(h, message, properties, 5, 6, 7);
    properties.canGetPollVoteStatistics = false; poll.options[0].media = null;
    poll.options[0].isChosen = false; poll.isClosed = true;
    checkActions(h, message, properties, 7);
    poll.isClosed = false; poll.voteRestrictionReason = new TdApi.PollVoteRestrictionReasonScheduled();
    checkActions(h, message, properties, 7);
    poll.voteRestrictionReason = null;
    checkActions(h, message, properties, 1, 7);
  }
}
'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            annotations = []
            for name, body in (("Nullable", "public @interface Nullable {}"),
                               ("IntDef", "public @interface IntDef {long[] value() default {}; boolean flag() default false;}")):
                path = work / "androidx/annotation" / (name + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package androidx.annotation; " + body)
                annotations.append(path)
            test = work / "SharedPollActionHarness.java"
            test.write_text(harness)
            sources = [*annotations, test,
                       ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java",
                       ROOT / "app/src/main/java/org/thunderdog/challegram/data/SharedPollVotePolicy.java",
                       ROOT / "app/src/main/java/org/thunderdog/challegram/data/PollResultPolicy.java"]
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                            *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", str(work), "SharedPollActionHarness"], check=True)

    def test_media_quiz_metadata_and_live_row_refresh(self):
        poll = read("app/src/main/java/org/thunderdog/challegram/data/InlineResultPoll.java")
        base = read("app/src/main/java/org/thunderdog/challegram/ui/SharedBaseController.java")
        methods = "\n".join((java_method(poll, "private static String mediaLabel ("),
                             java_method(poll, "private static TdApi.InlineQueryResultArticle article ("),
                             java_method(base, "private void refreshPollRow ("),
                             java_method(read("app/src/main/java/org/thunderdog/challegram/data/TD.java"),
                                         "public static @TdApi.SearchMessagesFilter.Constructors int makeFilterConstructor (")))
        harness = r'''
import java.util.*;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.SharedPollVotePolicy;
class SharedPollArchiveHarness {
  Adapter adapter = new Adapter();
  static class Row {
    TdApi.Message message;
    TdApi.InlineQueryResultArticle summary;
    Row(TdApi.Message message) {this.message = message; this.summary = article(message);}
    long getSourceMessageId() {return message.id;}
    TdApi.Message getMessage() {return message;}
  }
  Row parseObject(TdApi.Message message) {return new Row(message);}
  static class ListItem {
    Row data;
    ListItem(Row data) {this.data = data;}
    Object getData() {return data;}
    void setData(Row row) {data = row;}
  }
  static class Adapter {
    List<ListItem> items = new ArrayList<>(); int changed;
    List<ListItem> getItems() {return items;}
    void notifyItemChanged(int position) {changed++;}
  }
  static class R {
    static class string {
      static final int Photo = 1, Video = 2, Gif = 3, Audio = 4, File = 5;
      static final int Sticker = 6, Location = 7, Link = 8, Quiz = 9, Poll = 10, xVotes = 11;
    }
  }
  static class Lang {
    static String plural(int resource, int count) {return count + " votes";}
    static String getString(int resource) {
      return new String[]{"", "Photo", "Video", "GIF", "Audio", "File", "Sticker",
        "Location", "Link", "Quiz", "Poll"}[resource];
    }
  }
  METHODS
  static void check(boolean condition) {if (!condition) throw new AssertionError();}
  static TdApi.Message message() {
    TdApi.Poll poll = new TdApi.Poll();
    poll.id = 777; poll.question = new TdApi.FormattedText("Question?", new TdApi.TextEntity[0]);
    poll.type = new TdApi.PollTypeQuiz();
    TdApi.PollOption option = new TdApi.PollOption();
    option.text = new TdApi.FormattedText("Answer", new TdApi.TextEntity[0]);
    option.media = new TdApi.PollMediaLink();
    poll.options = new TdApi.PollOption[]{option};
    TdApi.Message message = new TdApi.Message();
    message.id = 123; message.chatId = 7; message.date = 55;
    message.content = new TdApi.MessagePoll(poll,
      new TdApi.FormattedText("Description", new TdApi.TextEntity[0]),
      new TdApi.PollMediaPhoto(), true);
    return message;
  }
  public static void main(String[] args) {
    TdApi.Message message = message();
    check(makeFilterConstructor(message, false) == TdApi.SearchMessagesFilterPoll.CONSTRUCTOR);
    check(makeFilterConstructor(message, true) == TdApi.SearchMessagesFilterPoll.CONSTRUCTOR);
    TdApi.InlineQueryResultArticle summary = article(message);
    check(summary.title.equals("Question?"));
    check(summary.description.equals("Quiz · Photo\nDescription\nAnswer · Link"));
    check(summary.id.equals("123"));
    SharedPollArchiveHarness h = new SharedPollArchiveHarness();
    Row oldRow = new Row(message);
    ArrayList<Row> rows = new ArrayList<>(Arrays.asList(oldRow));
    h.adapter.items.add(new ListItem(oldRow));
    TdApi.MessagePoll content = (TdApi.MessagePoll) message.content;
    content.poll.question = new TdApi.FormattedText("Edited question", new TdApi.TextEntity[0]);
    h.refreshPollRow(rows, 123, content);
    Row newRow = rows.get(0);
    check(newRow != oldRow && newRow.getMessage() == message);
    check(newRow.summary.title.equals("Edited question"));
    check(newRow.summary.description.contains("Answer · Link"));
    check(h.adapter.items.get(0).getData() == newRow && h.adapter.changed == 1);
    check(message.chatId == 7 && message.date == 55 && newRow.getSourceMessageId() == 123);
    h.refreshPollRow(rows, 999, content);
    check(h.adapter.changed == 1);
    h.refreshPollRow(null, 123, content);
    check(h.adapter.changed == 1);
    content.poll.canSeeResults = true;
    content.poll.totalVoterCount = 4;
    h.refreshPollRow(rows, 123, content);
    check(rows.get(0).summary.description.startsWith("Quiz · 4 votes · Photo"));
    content.poll.canSeeResults = false;
    check(!article(message).description.contains("votes"));
    content.poll.type = new TdApi.PollTypeRegular();
    check(article(message).description.startsWith("Poll · Photo"));
    TdApi.PollOption second = new TdApi.PollOption();
    second.text = new TdApi.FormattedText("Second answer", null);
    content.poll.options = new TdApi.PollOption[]{content.poll.options[0], second};
    content.poll.optionOrder = new int[]{1, 0};
    check(article(message).description.endsWith("\nSecond answer\nAnswer · Link"));
  }
}
'''.replace("METHODS", methods.replace("ArrayList<T>", "ArrayList<Row>")
                               .replace("T oldRow", "Row oldRow").replace("T row", "Row row"))
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            annotations = []
            for name, body in (("Nullable", "public @interface Nullable {}"),
                               ("IntDef", "public @interface IntDef {long[] value() default {}; boolean flag() default false;}")):
                path = work / "androidx/annotation" / (name + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package androidx.annotation; " + body)
                annotations.append(path)
            test = work / "SharedPollArchiveHarness.java"
            test.write_text(harness)
            sources = [*annotations, test,
                       ROOT / "tdlib/src/main/java/org/drinkless/tdlib/TdApi.java",
                       ROOT / "app/src/main/java/org/thunderdog/challegram/data/SharedPollVotePolicy.java"]
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work),
                            *map(str, sources)], check=True)
            subprocess.run(["java", "-cp", str(work), "SharedPollArchiveHarness"], check=True)


if __name__ == "__main__":
    unittest.main()
