// The keyboard's view of librime, with no JNI in it: rime_jni.cc wraps these
// for Kotlin, and host_test.cc drives them on a desktop against real schema
// data. See README.md for the contract.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace wmrime {

struct Paths {
    std::string shared_dir;  // schema packs, unpacked
    std::string user_dir;    // user dictionaries, installation.yaml, build/
    std::string version;     // the app's version, for installation.yaml
};

// The candidates of the segment being composed. Offsets are in bytes of the
// UTF-8 input: [start] is where the segment begins (the input before it is
// already chosen), [ends] where each candidate stops.
struct Menu {
    size_t start = 0;
    std::vector<std::string> texts;
    std::vector<std::string> comments;
    std::vector<int32_t> ends;
};

// Starts the service on deployed data and runs the quick maintenance pass
// (installation id, user dictionary upgrades). False when it could not.
bool Start(const Paths& paths);
// Stops the service and drops every session. Safe when not started.
void Stop();
bool Started();

// Deployment, with the service stopped: [BeginDeploy] once, then
// [DeploySchema] per schema file (absolute path) and [DeployConfig] for
// default.yaml, then [Start].
void BeginDeploy(const Paths& paths);
bool DeploySchema(const std::string& schema_file);
bool DeployConfig(const std::string& file_name, const std::string& version_key);

// Schema ids and names of the deployed schema list.
std::vector<std::pair<std::string, std::string>> SchemaList();

uint64_t CreateSession();
void DestroySession(uint64_t session);
bool SelectSchema(uint64_t session, const std::string& schema_id);
void SetOption(uint64_t session, const std::string& option, bool value);

// Replaces whatever was composing with [input], unconverted.
bool SetInput(uint64_t session, const std::string& input);
// The raw input of the session, chosen part included.
std::string Input(uint64_t session);
// Up to [limit] candidates of the segment being composed.
bool GetMenu(uint64_t session, size_t limit, Menu* menu);
// Chooses candidate [index] of the segment being composed, the way a tap
// does: the user dictionary learns it once the whole input is chosen. When
// that choice finishes the input, the session commits and [committed] gets
// the text; otherwise it stays empty and the next segment composes.
bool Select(uint64_t session, size_t index, std::string* committed);
void Clear(uint64_t session);

std::string Version();

}  // namespace wmrime
