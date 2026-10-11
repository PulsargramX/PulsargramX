"""Exercise positioning an answer inside a tall poll after message navigation."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method, read


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires a JDK")
class PollOptionNavigationTest(unittest.TestCase):
    def test_deferred_positioning_and_navigation_cancellation(self):
        method = java_method(read(
            "app/src/main/java/org/thunderdog/challegram/component/chat/MessagesManager.java"
        ), "private void ensurePollOptionVisible (")
        harness = r'''
import java.util.*;
class PollOptionNavigationHarness {
  String highlightPollOptionId = "answer";
  MessageId highlightMessageId = new MessageId(7, 100);
  int userScrollActionsCount;
  Runnable pendingQuoteScroll;
  Controller controller = new Controller();
  Adapter adapter = new Adapter();
  Manager manager = new Manager();
  METHOD
  static class RectF {
    float top, bottom;
    RectF(float top, float bottom) {this.top = top; this.bottom = bottom;}
    float height() {return bottom - top;}
  }
  static class TGMessagePoll {
    RectF bounds = new RectF(800, 900);
    long getChatId() {return 7;}
    long getId() {return 100;}
    RectF getOptionBounds(String id) {return "answer".equals(id) ? bounds : null;}
  }
  static class View {int getTop() {return -100;}}
  static class MessageId {
    long chat, id;
    MessageId(long chat, long id) {this.chat = chat; this.id = id;}
    boolean compareTo(long chat, long id) {return this.chat == chat && this.id == id;}
  }
  static class Adapter {
    TGMessagePoll message = new TGMessagePoll();
    int indexOfMessageContainer(MessageId id) {return 0;}
    TGMessagePoll getMessage(int index) {return message;}
  }
  static class Manager {
    View view = new View();
    View findViewByPosition(int index) {return view;}
  }
  static class Controller {
    RecyclerView recycler = new RecyclerView();
    RecyclerView getMessagesView() {return recycler;}
    int getTopOffset() {return 50;}
  }
  static class RecyclerView {
    static final int SCROLL_STATE_DRAGGING = 1, SCROLL_STATE_SETTLING = 2;
    int state, delta, smoothCalls;
    List<Runnable> queued = new ArrayList<>();
    int getScrollState() {return state;}
    int getPaddingTop() {return 20;}
    int getPaddingBottom() {return 20;}
    int getHeight() {return 600;}
    void post(Runnable task) {queued.add(task);}
    void scrollBy(int x, int y) {delta += y;}
    void smoothScrollBy(int x, int y) {smoothCalls++; delta += y;}
    void drain() {for (Runnable r : new ArrayList<>(queued)) r.run(); queued.clear();}
  }
  static class StringUtils {
    static boolean equalsOrBothEmpty(String a, String b) {return Objects.equals(a, b);}
  }
  static class Settings {
    static Settings settings = new Settings();
    boolean reduce;
    static Settings instance() {return settings;}
    boolean needReduceMotion() {return reduce;}
  }
  static void check(boolean condition) {if (!condition) throw new AssertionError();}
  public static void main(String[] args) {
    PollOptionNavigationHarness h = new PollOptionNavigationHarness();
    RecyclerView r = h.controller.recycler;
    TGMessagePoll poll = h.adapter.message;
    r.state = RecyclerView.SCROLL_STATE_SETTLING;
    h.ensurePollOptionVisible(poll); r.drain();
    check(r.delta == 0 && h.pendingQuoteScroll != null);
    // Recompute after votes or media have changed the selected answer's position.
    poll.bounds = new RectF(1000, 1100); r.state = 0;
    h.pendingQuoteScroll.run();
    check(r.delta == 635 && r.smoothCalls == 1);
    r.delta = 0;
    poll.bounds = new RectF(200, 300);
    h.ensurePollOptionVisible(poll); r.drain(); check(r.delta == 0);
    poll.bounds = new RectF(1000, 1100);
    h.ensurePollOptionVisible(poll); h.userScrollActionsCount++;
    r.drain(); check(r.delta == 0);
    h.ensurePollOptionVisible(poll); h.highlightPollOptionId = "another";
    r.drain(); check(r.delta == 0 && h.pendingQuoteScroll != null);
    h.highlightPollOptionId = "answer";
    h.ensurePollOptionVisible(poll); h.highlightMessageId = new MessageId(8, 100);
    r.drain(); check(r.delta == 0);
    h.highlightMessageId = new MessageId(7, 100);
    h.ensurePollOptionVisible(poll); r.state = RecyclerView.SCROLL_STATE_DRAGGING;
    r.drain(); check(r.delta == 0);
    r.state = 0; poll.bounds = null;
    h.ensurePollOptionVisible(poll); r.drain(); check(r.delta == 0);
    poll.bounds = new RectF(1000, 2000); Settings.settings.reduce = true;
    h.ensurePollOptionVisible(poll); r.drain();
    check(r.delta == 850 && r.smoothCalls == 1);
  }
}
'''.replace("METHOD", method)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "PollOptionNavigationHarness.java"
            path.write_text(harness)
            subprocess.run(["javac", "-d", directory, str(path)], check=True)
            subprocess.run(["java", "-cp", directory, "PollOptionNavigationHarness"],
                           check=True)
