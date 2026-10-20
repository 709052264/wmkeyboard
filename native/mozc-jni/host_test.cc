// Drives mozc_bridge on the desktop against a real mozc.data, one command
// per line of standard input, so a change can be checked before a phone
// sees it (host-test.sh). Not shipped.
//
//   mozc_host_test mozc.data user/ <<'EOF'
//   suggest きょうはいいてんき
//   convert きょうはいいてんき 2 1
//   commit-suggest きょうはいいてんき 3
//   predict 今日は
//   EOF
//
// Columns are tab-separated: the command, the reading, then the rest. The
// text before the caret, when a command takes it, follows `|` at the end.
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "wmkeyboard/mozc_bridge.h"

namespace {

void Print(const wmmozc::Menu& menu) {
  if (!menu.segments.empty()) {
    std::cout << "  segments:";
    for (size_t i = 0; i < menu.segments.size(); ++i) {
      const auto& s = menu.segments[i];
      std::cout << (static_cast<int>(i) == menu.focus ? " [" : " ") << s.value << "/" << s.key
                << (static_cast<int>(i) == menu.focus ? "]" : "");
    }
    std::cout << "\n";
  }
  for (size_t i = 0; i < menu.candidates.size(); ++i) {
    const auto& c = menu.candidates[i];
    std::cout << "  " << i << " " << c.value << " (id " << c.id << ", " << c.consumed << ")\n";
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    std::cerr << "usage: mozc_host_test mozc.data userdir < commands\n";
    return 2;
  }
  std::string error;
  if (!wmmozc::Start(argv[1], argv[2], &error)) {
    std::cerr << "start failed: " << error << "\n";
    return 1;
  }
  std::cout << "data " << wmmozc::DataVersion() << "\n";
  std::string line;
  while (std::getline(std::cin, line)) {
    if (line.empty() || line[0] == '#') continue;
    std::string preceding;
    if (const auto bar = line.rfind(" |"); bar != std::string::npos) {
      preceding = line.substr(bar + 2);
      line = line.substr(0, bar);
    }
    std::istringstream in(line);
    std::string cmd, reading;
    in >> cmd >> reading;
    std::vector<int> args;
    for (int v; in >> v;) args.push_back(v);
    std::cout << "> " << line << (preceding.empty() ? "" : " |" + preceding) << "\n";
    wmmozc::Menu menu;
    if (cmd == "suggest") {
      if (!wmmozc::Suggest(reading, preceding, args.empty() ? 20 : args[0], &menu)) std::cout << "  (none)\n";
      Print(menu);
    } else if (cmd == "convert") {
      if (!wmmozc::Convert(reading, preceding, args, 20, &menu)) std::cout << "  (none)\n";
      Print(menu);
    } else if (cmd == "predict") {
      // The reading column holds the text before the caret here.
      if (!wmmozc::Predict(reading, 20, &menu)) std::cout << "  (none)\n";
      Print(menu);
    } else if (cmd == "commit-suggest") {
      std::cout << "  consumed " << wmmozc::CommitSuggestion(reading, preceding, args.at(0)) << "\n";
    } else if (cmd == "commit-convert") {
      const int id = args.back();
      args.pop_back();
      std::cout << "  consumed " << wmmozc::CommitConversion(reading, preceding, args, id) << "\n";
    } else if (cmd == "commit-predict") {
      std::cout << "  -> " << wmmozc::CommitPrediction(reading, args.at(0)) << "\n";
    } else if (cmd == "loose") {
      wmmozc::SetKanaModifierInsensitive(reading == "on");
    } else if (cmd == "reset") {
      wmmozc::ResetContext();
    } else if (cmd == "sync") {
      std::cout << "  sync " << wmmozc::Sync() << "\n";
    } else if (cmd == "clear") {
      std::cout << "  clear " << wmmozc::ClearHistory() << "\n";
    } else {
      std::cout << "  unknown command\n";
    }
  }
  wmmozc::Stop();
  return 0;
}
