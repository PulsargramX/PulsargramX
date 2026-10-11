"""Exercise selection actions against changing server option identities."""
import pathlib
import subprocess
import tempfile
import unittest


class PollInteractionTest(unittest.TestCase):
    def test_stable_ids_and_individual_choice_updates(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        source = root / 'app/src/main/java/org/thunderdog/challegram/data/PollOptionSelection.java'
        policy = source.with_name('PollResultPolicy.java')
        harness = '''
import java.util.Arrays;
import org.thunderdog.challegram.data.PollOptionSelection;
import org.thunderdog.challegram.data.PollResultPolicy;
public class PollInteractionHarness {
  static void expect(int[] actual, int... expected) {
    if (!Arrays.equals(actual, expected)) throw new AssertionError(Arrays.toString(actual));
  }
  public static void main(String[] args) {
    // Even a creator preview cannot expose voters in anonymous or unavailable results.
    if (PollResultPolicy.canGetVoters(true, true, true)) throw new AssertionError();
    if (PollResultPolicy.canGetVoters(false, false, true)) throw new AssertionError();
    if (PollResultPolicy.canGetVoters(false, true, false)) throw new AssertionError();
    if (!PollResultPolicy.canGetVoters(false, true, true)) throw new AssertionError();
    if (!PollResultPolicy.canPreview(true, false, false)) throw new AssertionError();
    if (PollResultPolicy.canPreview(false, false, false)) throw new AssertionError();
    if (PollResultPolicy.canPreview(true, true, false)) throw new AssertionError();
    if (PollResultPolicy.canPreview(true, false, true)) throw new AssertionError();
    // A shuffled display never changes TDLib's option indices.
    String[] server = {"a", "b", "c"};
    expect(PollOptionSelection.toggle(server, new boolean[] {false, false, false}, "c", false), 2);
    // A delayed action resolves the same stable id after insertion or reorder.
    String[] updated = {"new", "c", "a", "b"};
    expect(PollOptionSelection.toggle(updated, new boolean[] {false, false, true, true}, "c", true), 1, 2, 3);
    // Retracting one choice preserves the other choices in a multiple-answer poll.
    expect(PollOptionSelection.toggle(updated, new boolean[] {false, true, true, true}, "c", true), 2, 3);
    // Revoting a single-answer poll replaces the previous choice.
    expect(PollOptionSelection.toggle(server, new boolean[] {true, false, false}, "b", false), 1);
    expect(PollOptionSelection.toggle(server, new boolean[] {true, false, false}, "a", false));
    // Deleted ids never fall back to an unrelated option at the captured index.
    if (PollOptionSelection.toggle(new String[] {"a", "c"}, new boolean[] {true, false}, "b", true) != null) throw new AssertionError();
    if (PollOptionSelection.find(server, "") != -1 || PollOptionSelection.find(server, null) != -1) throw new AssertionError();
    try {
      PollOptionSelection.toggle(server, new boolean[] {false}, "a", true);
      throw new AssertionError();
    } catch (IllegalArgumentException expected) { }
  }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            test = pathlib.Path(directory) / 'PollInteractionHarness.java'
            test.write_text(harness)
            subprocess.run(['javac', '-d', directory, str(source), str(policy), str(test)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', directory, 'PollInteractionHarness'], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
