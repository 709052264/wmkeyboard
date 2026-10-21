// Drives rime_bridge.cc on a desktop against real schema data, the way the
// keyboard does: deploy, start, then type, list, pick. Not part of the app;
// build it with host-test.sh.
//
//   rime_host_test <shared> <user> <schema_id> <input> [pick ...]
//
// Deploys every *.schema.yaml in <shared>, prints the menu for <input>, then
// for each pick index chooses it and prints what happened. RIME_OPTIONS (a
// comma-separated list) switches options on first, such as simplification.
// RIME_SKIP_DEPLOY=1 starts on what an earlier run deployed into <user>.
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>

#include "rime_bridge.h"

static void PrintMenu(uint64_t session) {
    wmrime::Menu menu;
    if (!wmrime::GetMenu(session, 10, &menu)) {
        std::printf("  (no session)\n");
        return;
    }
    std::printf("  input=%s start=%zu\n", wmrime::Input(session).c_str(), menu.start);
    for (size_t i = 0; i < menu.texts.size(); ++i) {
        std::printf("  %zu. %s  end=%d  %s\n", i, menu.texts[i].c_str(), menu.ends[i], menu.comments[i].c_str());
    }
}

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s <shared> <user> <schema_id> <input> [pick ...]\n", argv[0]);
        return 2;
    }
    wmrime::Paths paths{argv[1], argv[2], "test"};
    if (!std::getenv("RIME_SKIP_DEPLOY")) {
        wmrime::BeginDeploy(paths);
        for (const auto& entry : std::filesystem::directory_iterator(paths.shared_dir)) {
            const std::string name = entry.path().filename().string();
            if (name.size() > 12 && name.compare(name.size() - 12, 12, ".schema.yaml") == 0) {
                bool ok = wmrime::DeploySchema(entry.path().string());
                std::printf("deploy %s: %s\n", name.c_str(), ok ? "ok" : "FAILED");
            }
        }
        std::printf("deploy default.yaml: %s\n", wmrime::DeployConfig("default.yaml", "config_version") ? "ok" : "FAILED");
    }
    wmrime::Start(paths);
    std::printf("librime %s\n", wmrime::Version().c_str());
    for (const auto& [id, name] : wmrime::SchemaList()) std::printf("schema %s (%s)\n", id.c_str(), name.c_str());
    uint64_t session = wmrime::CreateSession();
    std::printf("select %s: %d\n", argv[3], wmrime::SelectSchema(session, argv[3]));
    wmrime::SetOption(session, "ascii_mode", false);
    // RIME_OPTIONS=simplification,... switches those options on.
    if (const char* options = std::getenv("RIME_OPTIONS")) {
        std::string list = options;
        for (size_t start = 0; start <= list.size();) {
            size_t end = list.find(',', start);
            if (end == std::string::npos) end = list.size();
            if (end > start) wmrime::SetOption(session, list.substr(start, end - start), true);
            start = end + 1;
        }
    }
    wmrime::SetInput(session, argv[4]);
    PrintMenu(session);
    for (int i = 5; i < argc; ++i) {
        std::string committed;
        bool ok = wmrime::Select(session, std::strtoul(argv[i], nullptr, 10), &committed);
        std::printf("pick %s: %s committed=[%s]\n", argv[i], ok ? "ok" : "FAILED", committed.c_str());
        PrintMenu(session);
    }
    wmrime::DestroySession(session);
    wmrime::Stop();
    return 0;
}
