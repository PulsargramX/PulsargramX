import pathlib
import shutil
import subprocess
import tempfile
import unittest


class PollDraftRulesTest(unittest.TestCase):
  def test_draft_rules(self):
    if not shutil.which('javac') or not shutil.which('java'):
      self.skipTest('JDK is required for poll draft rules')
    root = pathlib.Path(__file__).resolve().parents[2]
    source = root / 'app/src/main/java/org/thunderdog/challegram/ui/PollDraftRules.java'
    harness = '''
import java.util.List;
import org.thunderdog.challegram.ui.PollDraftRules;
public class PollDraftRulesHarness {
  static void expect(boolean actual) {
    if (!actual) throw new AssertionError();
  }
  public static void main(String[] args) {
    expect(!PollDraftRules.validAnswers(1, 1, true, false));
    expect(!PollDraftRules.validAnswers(0, 2, true, true));
    expect(PollDraftRules.validAnswers(2, 0, false, false));
    expect(!PollDraftRules.validAnswers(2, 0, true, true));
    expect(PollDraftRules.validAnswers(2, 1, true, false));
    expect(!PollDraftRules.validAnswers(3, 2, true, false));
    expect(PollDraftRules.validAnswers(3, 2, true, true));
    expect(PollDraftRules.validDuration(3600, 0, 100, 2000, false, 3600));
    expect(!PollDraftRules.validDuration(3601, 0, 100, 100, false, 3600));
    expect(!PollDraftRules.validDuration(3600, 200, 100, 100, false, 3600));
    expect(PollDraftRules.validDuration(0, 3700, 100, 100, false, 3600));
    expect(!PollDraftRules.validDuration(0, 3701, 100, 100, false, 3600));
    expect(!PollDraftRules.validDuration(0, 200, 100, 200, false, 3600));
    expect(!PollDraftRules.validDuration(0, 200, 100, 100, true, 3600));
    expect(PollDraftRules.validDuration(3600, 0, 100, 100, true, 3600));
    expect(!PollDraftRules.validDuration(0, 100, 100, 100, false, 3600));
    expect(PollDraftRules.validDuration(0, 0, 100, Long.MAX_VALUE, true, 3600));
    String text = "xx  Alpha\\r\\n\\r\\n Beta 😀 \\n Gamma  yy";
    List<int[]> lines = PollDraftRules.splitLines(text, 2, text.length() - 2);
    expect(lines.size() == 3);
    expect(text.substring(lines.get(0)[0], lines.get(0)[1]).equals("Alpha"));
    expect(text.substring(lines.get(1)[0], lines.get(1)[1]).equals("Beta 😀"));
    expect(text.substring(lines.get(2)[0], lines.get(2)[1]).equals("Gamma"));
    expect(PollDraftRules.splitLines("\\n \\r\\n", 0, 4).isEmpty());
    expect(PollDraftRules.splitLines("A", 0, 1).size() == 1);
  }
}
'''
    with tempfile.TemporaryDirectory() as directory:
      path = pathlib.Path(directory) / 'PollDraftRulesHarness.java'
      path.write_text(harness, encoding='utf-8')
      subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, str(source), str(path)],
                     check=True, capture_output=True, text=True)
      subprocess.run(['java', '-cp', directory, 'PollDraftRulesHarness'],
                     check=True, capture_output=True, text=True)


if __name__ == '__main__':
  unittest.main()
