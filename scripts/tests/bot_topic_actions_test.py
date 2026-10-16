"""Exercise patched TDLib typing state with a fake clock and update transport."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.message_options_keyboard_source_test import java_method

ROOT = Path(__file__).resolve().parents[2]

HARNESS = r'''
#include <algorithm>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(#condition); } while (0)
struct Log { template<class T> Log &operator<<(const T &) { return *this; } };
#define LOG(level) Log()

enum class DialogType { User, Chat, Channel, SecretChat };
struct DialogId {
  int id = 0;
  DialogType get_type() const { return id < 0 ? DialogType::Channel : DialogType::User; }
  int get_user_id() const { return id; }
  int get() const { return id; }
  bool operator==(DialogId other) const { return id == other.id; }
  bool operator!=(DialogId other) const { return !(*this == other); }
  bool operator<(DialogId other) const { return id < other.id; }
};
struct ServerMessageId { int id; explicit ServerMessageId(int id) : id(id) {} };
struct MessageId {
  int id = 0;
  MessageId() = default;
  explicit MessageId(ServerMessageId id) : id(id.id) {}
  bool is_valid() const { return id > 0; }
  bool is_scheduled() const { return false; }
  bool operator==(MessageId other) const { return id == other.id; }
  bool operator!=(MessageId other) const { return !(*this == other); }
};
enum class MessageContentType { None, Text, Photo };
struct DialogAction {
  int type = 0;
  DialogAction clone() const { return *this; }
  bool operator==(DialogAction other) const { return type == other.type; }
  bool is_canceled_by_message_of_type(MessageContentType content) const {
    return content == MessageContentType::None || content == MessageContentType::Text;
  }
};
struct Time { static inline double value = 100; static double now() { return value; } };
struct Global { int unix_time() const { return 100; } };
Global *G() { static Global global; return &global; }
struct UserManager {
  bool is_user_bot(int id) const { return id == 10 || id == 20; }
  bool is_user_status_exact(int) const { return true; }
  void on_update_user_local_was_online(int, int) {}
};
struct AuthManager { bool is_bot() const { return false; } };
struct MessagesManager;
struct DialogActionManager;
struct Td {
  UserManager *user_manager_;
  AuthManager *auth_manager_;
  MessagesManager *messages_manager_;
  DialogActionManager *dialog_action_manager_;
};
struct Timeout {
  std::map<int, double> deadlines;
  void cancel_timeout(int id) { deadlines.erase(id); }
  void set_timeout_in(int id, double delay) { deadlines[id] = Time::now() + delay; }
  void add_timeout_in(int id, double delay) { set_timeout_in(id, delay); }
};
struct DialogActionManager {
  static constexpr double DIALOG_ACTION_TIMEOUT = 5.5;
  struct ActiveDialogAction {
    MessageId top_thread_message_id;
    DialogId typing_dialog_id;
    DialogAction action;
    double start_time;
    ActiveDialogAction(MessageId topic, DialogId sender, DialogAction action, double start)
      : top_thread_message_id(topic), typing_dialog_id(sender), action(action), start_time(start) {}
  };
  Td *td_;
  std::map<DialogId, std::vector<ActiveDialogAction>> active_dialog_actions_;
  Timeout active_dialog_action_timeout_;
  std::map<std::tuple<int, int, int>, int> displayed;
  std::vector<std::tuple<int, int, int, int>> updates;
  explicit DialogActionManager(Td *td) : td_(td) {}
  void send_update_chat_action(DialogId chat, MessageId topic, DialogId sender, const DialogAction &action) {
    displayed[{chat.id, topic.id, sender.id}] = action.type;
    updates.emplace_back(chat.id, topic.id, sender.id, action.type);
  }
  void on_dialog_action(DialogId dialog_id, MessageId top_thread_message_id,
                        DialogId typing_dialog_id, DialogAction action, int date,
                        MessageContentType message_content_type = MessageContentType::None);
  void on_active_dialog_action_timeout(DialogId dialog_id);
  void clear_active_dialog_actions(DialogId dialog_id);
};
struct Content { MessageContentType get_type() const { return MessageContentType::Text; } };
struct Message {
  int topic;
  DialogId sender{10};
  bool forwarded = false, hide_via_bot = false, is_channel_post = false;
  MessageId message_id{};
  struct ViaBot { bool is_valid() const { return false; } } via_bot_user_id;
  int date = 100;
  Content data;
  Content *content = &data;
};
struct MessageTopic {
  int id;
  int get_input_top_msg_id() const { return id; }
};
struct MessagesManager {
  Td *td_;
  bool is_dialog_opened(DialogId) const { return true; }
  bool is_message_forward(const Message *m) const { return m->forwarded; }
  DialogId get_message_sender(const Message *m) const { return m->sender; }
  MessageTopic get_message_topic(DialogId, const Message *m) const { return {m->topic}; }
  void cancel_dialog_action(DialogId dialog_id, const Message *m);
};

void DialogActionManager::on_dialog_action(DialogId dialog_id, MessageId top_thread_message_id,
                                          DialogId typing_dialog_id, DialogAction action, int date,
                                          MessageContentType message_content_type) {
  auto dialog_type = dialog_id.get_type();
  auto typing_dialog_type = typing_dialog_id.get_type();
ACTION_BODY
TIMEOUT_METHOD
CLEAR_METHOD
MESSAGE_METHOD

struct Fixture {
  UserManager users;
  AuthManager auth;
  Td td{&users, &auth, nullptr, nullptr};
  MessagesManager messages{&td};
  DialogActionManager manager{&td};
  Fixture() { td.messages_manager_ = &messages; td.dialog_action_manager_ = &manager; }
  void action(int topic, int type = 1, int sender = 10, int chat = 10) {
    manager.on_dialog_action({chat}, MessageId(ServerMessageId(topic)), {sender}, {type}, 100);
  }
  int shown(int topic, int sender = 10, int chat = 10) {
    return manager.displayed[{chat, topic, sender}];
  }
  size_t count(int chat = 10) { return manager.active_dialog_actions_[{chat}].size(); }
  void expect(bool condition, const char *reason) {
    if (!condition) throw std::runtime_error(reason);
  }
};

int main(int argc, char **argv) {
  CHECK(argc == 2);
  Fixture f;
  std::string scenario = argv[1];
  if (scenario == "simultaneous") {
    for (int i = 0; i < 20; i++) {
      f.action(1); f.action(2);
      f.expect(f.count() == 2 && f.shown(1) == 1 && f.shown(2) == 1,
               "The same bot must remain typing in both topics");
    }
    for (auto [chat, topic, sender, type] : f.manager.updates) {
      f.expect(type != 0, "Interleaved typing must not emit synthetic cancellations");
    }
  } else if (scenario == "cancel") {
    f.action(1); f.action(2, 2);
    f.action(3, 0); f.action(0, 0);
    f.expect(f.count() == 2, "Unknown and unscoped cancels cannot cancel a topic");
    f.action(2, 0);
    f.expect(f.count() == 1 && f.shown(1) == 1 && f.shown(2) == 0 && f.shown(0) == 1,
             "Cancelling a topic must restore the remaining action in the chat indicator");
    f.action(1, 0);
    f.expect(f.count() == 0 && f.shown(0) == 0, "The last cancel clears the chat indicator");
  } else if (scenario == "messages") {
    f.action(1); f.action(2);
    Message unrelated{3}; f.messages.cancel_dialog_action({10}, &unrelated);
    f.expect(f.count() == 2, "A message in another topic must not cancel typing");
    Message forwarded{1}; forwarded.forwarded = true;
    f.messages.cancel_dialog_action({10}, &forwarded);
    f.expect(f.count() == 2, "A forwarded message must not cancel typing");
    Message first{1}; f.messages.cancel_dialog_action({10}, &first);
    f.expect(f.count() == 1 && f.shown(1) == 0 && f.shown(2) == 1 && f.shown(0) == 1,
             "Receiving a bot message must cancel only that topic's action");
    Message second{2}; f.messages.cancel_dialog_action({10}, &second);
    f.expect(f.count() == 0 && f.shown(0) == 0, "The final bot message clears typing");
  } else if (scenario == "expiry") {
    f.action(1);
    Time::value = 101; f.action(2, 2);
    Time::value = 103; f.action(2, 2);
    Time::value = 105.6; f.manager.on_active_dialog_action_timeout({10});
    f.expect(f.count() == 1 && f.shown(1) == 0 && f.shown(2) == 2 && f.shown(0) == 2,
             "An expired topic must not clear a refreshed topic or the chat indicator");
    f.expect(f.manager.active_dialog_action_timeout_.deadlines[10] == 108.5,
             "The next timeout must use the refreshed topic's deadline");
    Time::value = 109; f.manager.on_active_dialog_action_timeout({10});
    f.expect(f.manager.active_dialog_actions_.empty() && f.shown(0) == 0 &&
             f.manager.active_dialog_action_timeout_.deadlines.empty(),
             "Expiry must clear the final action and its timer");
  } else if (scenario == "expire-all") {
    f.action(1); f.action(2); f.action(3);
    Time::value = 106; f.manager.on_active_dialog_action_timeout({10});
    f.expect(f.manager.active_dialog_actions_.empty() && f.shown(0) == 0 &&
             f.shown(1) == 0 && f.shown(2) == 0 && f.shown(3) == 0,
             "All expired topics from one bot must clear without a failed assertion");
  } else if (scenario == "clear") {
    f.action(1); f.action(2); f.action(3, 1, 20);
    f.manager.clear_active_dialog_actions({10});
    f.expect(f.manager.active_dialog_actions_.empty() &&
             f.shown(1) == 0 && f.shown(2) == 0 && f.shown(3, 20) == 0,
             "Cleanup must cancel every topic and sender");
  } else if (scenario == "unscoped") {
    f.action(0); f.action(1, 2);
    f.action(0, 0);
    f.expect(f.count() == 1 && f.shown(0) == 2 && f.shown(1) == 2,
             "An unscoped cancel must preserve the remaining topic and chat action");
    f.action(0); f.action(1, 0);
    f.expect(f.count() == 1 && f.shown(0) == 1 && f.shown(1) == 0,
             "A topic cancel must preserve an unscoped action");
    Message message{0}; f.messages.cancel_dialog_action({10}, &message);
    f.expect(f.count() == 0 && f.shown(0) == 0, "Ordinary bot messages still clear typing");
  } else if (scenario == "isolation") {
    f.action(1); f.action(1, 2, 20); f.action(1, 2, 10, 20);
    f.action(1, 0);
    f.expect(f.count() == 1 && f.count(20) == 1 && f.shown(1, 20) == 2 &&
             f.shown(1, 10, 20) == 2, "Senders and chats must remain independent");
    f.action(1, 0, 20);
    f.expect(f.shown(0, 20) == 0 && f.shown(0, 10, 20) == 2,
             "A different chat's action must not keep this chat's indicator active");
  } else if (scenario == "ordinary") {
    f.action(1, 1, 30); f.action(2, 1, 30);
    f.expect(f.count() == 1 && f.shown(1, 30) == 0 && f.shown(2, 30) == 1,
             "Human senders retain their existing topic-switch behavior");
    f.action(0, 0, 30);
    f.expect(f.count() == 0 && f.shown(2, 30) == 0,
             "Unscoped cancellation for a human sender is preserved");
    f.action(1, 1, 10, -100); f.action(2, 1, 10, -100);
    f.expect(f.count(-100) == 1 && f.shown(1, 10, -100) == 0 && f.shown(2, 10, -100) == 1,
             "Group typing behavior is preserved");
  } else {
    throw std::runtime_error("Unknown scenario");
  }
}
'''


@unittest.skipUnless(shutil.which("g++"), "Requires a C++ compiler")
class BotTopicActionsTest(unittest.TestCase):
  def test_topic_action_lifecycle(self):
    with tempfile.TemporaryDirectory(prefix="bot-topic-actions-") as directory:
      work = Path(directory)
      patches = sorted((ROOT / "patches/tdlib").glob("*.patch"))
      for patch in patches:
        for name in re.findall(r"^--- a/(.+)$", patch.read_text(), re.M):
          target = work / name
          if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / "tdlib/source/td" / name, target)
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=work, check=True)
        subprocess.run(["git", "apply", str(patch)], cwd=work, check=True)
      actions = (work / "td/telegram/DialogActionManager.cpp").read_text()
      method = java_method(actions, "void DialogActionManager::on_dialog_action(")
      # Keep production state mutation and notification code; omit protocol decoding.
      start = method.rfind("\n\n", 0, method.index("  bool is_canceled =")) + 2
      harness = HARNESS.replace("ACTION_BODY", method[start:]).replace(
        "TIMEOUT_METHOD", java_method(actions,
          "void DialogActionManager::on_active_dialog_action_timeout(")).replace(
        "CLEAR_METHOD", java_method(actions,
          "void DialogActionManager::clear_active_dialog_actions(")).replace(
        "MESSAGE_METHOD", java_method(
          (work / "td/telegram/MessagesManager.cpp").read_text(),
          "void MessagesManager::cancel_dialog_action("))
      source = work / "bot_topic_actions_test.cpp"
      source.write_text(harness)
      executable = work / "bot_topic_actions_test"
      subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                      str(source), "-o", str(executable)], check=True)
      for scenario in ("simultaneous", "cancel", "messages", "expiry", "expire-all", "clear",
                       "unscoped", "isolation", "ordinary"):
        with self.subTest(scenario=scenario):
          result = subprocess.run([str(executable), scenario], capture_output=True, text=True,
                                  timeout=10)
          self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
  unittest.main()
