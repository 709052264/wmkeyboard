// librime for the keyboard: setup, deployment and sessions through the
// public C API, and the menu through librime's own classes, which are the
// only place a candidate says how much of the input it covers. That span is
// what the keyboard's prefix commit needs (Composer.consumedFor): picking 你
// for `nihao` writes 你 and keeps `hao` composing.
#include "rime_bridge.h"

#include <mutex>

#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/deployer.h>
#include <rime/menu.h>
#include <rime/segmentation.h>
#include <rime/service.h>
#include <rime/setup.h>

namespace wmrime {
namespace {

RimeApi* api() {
    static RimeApi* instance = rime_get_api();
    return instance;
}

// RimeTraits points at these, and librime copies them during setup; kept
// alive anyway, since the deployer reads its paths again on every task.
std::string g_shared;
std::string g_user;
std::string g_version;
bool g_started = false;
std::mutex g_mutex;

void FillTraits(RimeTraits* traits, const Paths& paths) {
    g_shared = paths.shared_dir;
    g_user = paths.user_dir;
    g_version = paths.version;
    traits->shared_data_dir = g_shared.c_str();
    traits->user_data_dir = g_user.c_str();
    traits->distribution_name = "WM Keyboard";
    traits->distribution_code_name = "wmkeyboard";
    traits->distribution_version = g_version.c_str();
    traits->app_name = "rime.wmkeyboard";
    // No glog in this build; these only keep librime from asking for it.
    traits->min_log_level = 3;
    traits->log_dir = "";
}

void StopLocked() {
    if (!g_started) return;
    api()->finalize();
    g_started = false;
}

}  // namespace

bool Start(const Paths& paths) {
    std::lock_guard<std::mutex> lock(g_mutex);
    StopLocked();
    RIME_STRUCT(RimeTraits, traits);
    FillTraits(&traits, paths);
    api()->setup(&traits);
    api()->initialize(&traits);
    g_started = true;
    // The installation id the user dictionaries are keyed by. Nothing else of
    // the maintenance pass: schemas are compiled by an explicit deploy, never
    // behind the keyboard's back.
    rime::LoadModules(rime::kDeployerModules);
    rime::Service::instance().deployer().RunTask("installation_update");
    return true;
}

void Stop() {
    std::lock_guard<std::mutex> lock(g_mutex);
    StopLocked();
}

bool Started() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_started;
}

void BeginDeploy(const Paths& paths) {
    std::lock_guard<std::mutex> lock(g_mutex);
    StopLocked();
    RIME_STRUCT(RimeTraits, traits);
    FillTraits(&traits, paths);
    api()->setup(&traits);
    api()->deployer_initialize(&traits);
    rime::Service::instance().deployer().RunTask("installation_update");
}

bool DeploySchema(const std::string& schema_file) {
    return api()->deploy_schema(schema_file.c_str());
}

bool DeployConfig(const std::string& file_name, const std::string& version_key) {
    return api()->deploy_config_file(file_name.c_str(), version_key.c_str());
}

std::vector<std::pair<std::string, std::string>> SchemaList() {
    std::vector<std::pair<std::string, std::string>> out;
    RimeSchemaList list;
    if (!api()->get_schema_list(&list)) return out;
    for (size_t i = 0; i < list.size; ++i) {
        out.emplace_back(list.list[i].schema_id ? list.list[i].schema_id : "",
                         list.list[i].name ? list.list[i].name : "");
    }
    api()->free_schema_list(&list);
    return out;
}

uint64_t CreateSession() {
    return api()->create_session();
}

void DestroySession(uint64_t session) {
    api()->destroy_session(session);
}

bool SelectSchema(uint64_t session, const std::string& schema_id) {
    return api()->select_schema(session, schema_id.c_str());
}

void SetOption(uint64_t session, const std::string& option, bool value) {
    api()->set_option(session, option.c_str(), value ? True : False);
}

bool SetInput(uint64_t session, const std::string& input) {
    api()->clear_composition(session);
    return api()->set_input(session, input.c_str());
}

std::string Input(uint64_t session) {
    const char* input = api()->get_input(session);
    return input ? input : "";
}

bool GetMenu(uint64_t session, size_t limit, Menu* menu) {
    menu->start = 0;
    menu->texts.clear();
    menu->comments.clear();
    menu->ends.clear();
    rime::an<rime::Session> s = rime::Service::instance().GetSession(session);
    if (!s) return false;
    rime::Context* ctx = s->context();
    if (!ctx) return false;
    rime::Composition& composition = ctx->composition();
    if (composition.empty()) return true;
    // What the earlier picks of this input chose ends here; everything from
    // there on is still the user's to choose.
    const size_t chosen = composition.GetConfirmedPosition();
    menu->start = chosen;
    rime::Segment& segment = composition.back();
    if (!segment.menu) return true;
    // Segments between the last pick and the one being composed (a
    // punctuation key inside the input splits it) commit as their best guess
    // along with whatever is picked here, so each candidate carries them.
    std::string guessed;
    for (const rime::Segment& seg : composition) {
        if (&seg == &segment || seg.start < chosen || seg.status >= rime::Segment::kSelected) continue;
        if (auto cand = seg.GetSelectedCandidate()) guessed += cand->text();
    }
    const size_t count = segment.menu->Prepare(limit);
    for (size_t i = 0; i < count && i < limit; ++i) {
        rime::an<rime::Candidate> cand = segment.menu->GetCandidateAt(i);
        if (!cand) break;
        menu->texts.push_back(guessed + cand->text());
        menu->comments.push_back(cand->comment());
        menu->ends.push_back(static_cast<int32_t>(cand->end()));
    }
    return true;
}

bool Select(uint64_t session, size_t index, std::string* committed) {
    committed->clear();
    rime::an<rime::Session> s = rime::Service::instance().GetSession(session);
    if (!s) return false;
    rime::Context* ctx = s->context();
    if (!ctx) return false;
    s->ResetCommitText();
    if (!ctx->Select(index)) return false;
    // An express editor commits as soon as the input is all chosen; a fluid
    // one leaves it confirmed and waits for a key. The keyboard has no such
    // key, so it commits here, which is also what makes the user dictionary
    // learn the phrase.
    if (s->commit_text().empty() && !ctx->input().empty() &&
        ctx->composition().GetConfirmedPosition() >= ctx->input().length()) {
        s->CommitComposition();
    }
    *committed = s->commit_text();
    if (!committed->empty()) s->ResetCommitText();
    return true;
}

void Clear(uint64_t session) {
    api()->clear_composition(session);
}

std::string Version() {
    const char* version = api()->get_version();
    return version ? version : "";
}

}  // namespace wmrime
