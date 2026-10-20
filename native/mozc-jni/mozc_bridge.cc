// See mozc_bridge.h. This sits on Mozc's converter (ConverterInterface)
// rather than its session or its EngineConverter: those keep a candidate
// window and a keymap state machine the keyboard has its own versions of,
// and neither tells how much of the reading a partial suggestion covers.
// The converter's Segments do (Candidate::consumed_key_size), and its
// segment and commit calls are the ones EngineConverter makes, in the same
// order (engine/engine_converter.cc).
//
// Every call starts from the reading it is given, as RIME's bridge does:
// the keyboard hands its composer the whole reading each time. Only the
// history (what was committed before) carries over between calls, in the
// one Segments object, which is what Mozc's bigram context and its learning
// read.
#include "mozc_bridge.h"

#include <algorithm>
#include <cstddef>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "absl/strings/ascii.h"
#include "absl/strings/match.h"
#include "absl/strings/string_view.h"
#include "base/system_util.h"
#include "base/util.h"
#include "composer/composer.h"
#include "composer/table.h"
#include "config/config_handler.h"
#include "converter/attribute.h"  // through //converter:segments
#include "converter/candidate.h"  // through //converter:segments
#include "converter/converter_interface.h"
#include "converter/segments.h"
#include "data_manager/data_manager.h"
#include "engine/engine.h"
#include "protocol/commands.pb.h"
#include "protocol/config.pb.h"
#include "request/conversion_request.h"

namespace wmmozc {
namespace {

using ::mozc::ConversionRequest;
using ::mozc::ConversionRequestBuilder;
using MSegment = ::mozc::Segment;
using ::mozc::Segments;
using ::mozc::Util;
using ::mozc::converter::Attribute;
using MCandidate = ::mozc::converter::Candidate;

struct State {
  std::unique_ptr<mozc::Engine> engine;
  std::shared_ptr<const mozc::ConverterInterface> converter;
  std::shared_ptr<mozc::commands::Request> request;
  std::shared_ptr<mozc::config::Config> config;
  std::shared_ptr<const mozc::composer::Table> table;
  Segments segments;
};

std::mutex g_mutex;
std::unique_ptr<State> g;

// The request Gboard's Japanese keyboard sends Mozc (request_test_util's
// FillMobileRequest), less its flick table: the keyboard composes kana
// itself and hands Mozc the result.
std::shared_ptr<mozc::commands::Request> MobileRequest() {
  auto request = std::make_shared<mozc::commands::Request>();
  request->set_zero_query_suggestion(true);
  request->set_mixed_conversion(true);
  request->set_update_input_mode_from_surrounding_text(false);
  request->set_kana_modifier_insensitive_conversion(true);
  request->set_auto_partial_suggestion(true);
  request->set_language_aware_input(mozc::commands::Request::NO_LANGUAGE_AWARE_INPUT);
  return request;
}

// The reading as a composer holds it. Kana go in as typed kana. Romaji the
// keyboard has not turned into kana yet (the `h` of `kyouh`) go in as
// romaji, through Mozc's own table, so its prediction expands them the way
// it does for its own keyboard (h to は, ひ, ふ…) instead of reading a
// literal letter.
mozc::composer::Composer MakeComposer(const State& s, absl::string_view reading) {
  mozc::composer::Composer composer(s.table, s.request, s.config);
  size_t tail = reading.size();
  while (tail > 0 && absl::ascii_isalpha(static_cast<unsigned char>(reading[tail - 1]))) --tail;
  if (tail > 0) composer.InsertCharacterPreedit(reading.substr(0, tail));
  for (size_t i = tail; i < reading.size(); ++i) {
    composer.InsertCharacter(std::string(1, absl::ascii_tolower(static_cast<unsigned char>(reading[i]))));
  }
  return composer;
}

ConversionRequest MakeRequest(const State& s, const mozc::composer::Composer& composer,
                              const mozc::commands::Context& context,
                              ConversionRequest::RequestType type, int limit) {
  ConversionRequest::Options options;
  options.request_type = type;
  options.enable_user_history_for_conversion = true;
  if (type != ConversionRequest::CONVERSION) {
    options.use_actual_converter_for_realtime_conversion = true;
    options.create_partial_candidates = s.request->auto_partial_suggestion();
    options.max_dictionary_prediction_candidates_size =
        std::max(options.max_dictionary_prediction_candidates_size, limit);
  }
  return ConversionRequestBuilder()
      .SetComposer(composer)
      .SetRequestView(*s.request)
      .SetContextView(context)
      .SetConfigView(*s.config)
      .SetOptions(std::move(options))
      .Build();
}

// What the history segments spell, the text Mozc believes precedes the caret.
std::string HistoryText(const Segments& segments) {
  std::string text;
  for (const MSegment& segment : segments.history_segments()) {
    if (segment.candidates_size() > 0) text += segment.candidate(0).value;
  }
  return text;
}

// Keeps Mozc's context only while the text before the caret still ends with
// it; a caret moved elsewhere, or a different field, starts afresh.
void SyncContext(State& s, absl::string_view preceding) {
  // The keyboard keeps only the last few characters of what it committed
  // (CjkContext), and Mozc a few segments, so either may be the longer.
  const std::string history = HistoryText(s.segments);
  if (history.empty()) return;
  if (!preceding.empty() &&
      (absl::EndsWith(preceding, history) || absl::EndsWith(history, preceding))) {
    return;
  }
  s.converter->ResetConversion(&s.segments);
}

int Chars(absl::string_view text) { return static_cast<int>(Util::CharsLen(text)); }

void FillFlat(const MSegment& segment, int reading_chars, int limit, Menu* out) {
  std::set<std::pair<std::string, int>> seen;
  for (size_t i = 0; i < segment.candidates_size(); ++i) {
    if (static_cast<int>(out->candidates.size()) >= limit) break;
    const MCandidate& c = segment.candidate(i);
    if (c.attributes & Attribute::COMMAND_CANDIDATE) continue;
    if (c.value.empty()) continue;
    int consumed = reading_chars;
    if (c.attributes & Attribute::PARTIALLY_KEY_CONSUMED) {
      consumed = std::clamp(static_cast<int>(c.consumed_key_size), 1, std::max(1, reading_chars));
    }
    if (!seen.insert({c.value, consumed}).second) continue;
    out->candidates.push_back({c.value, static_cast<int>(i), consumed});
  }
}

bool SuggestLocked(State& s, const std::string& reading, const std::string& preceding,
                   int limit, Menu* out) {
  SyncContext(s, preceding);
  s.converter->CancelConversion(&s.segments);
  const mozc::composer::Composer composer = MakeComposer(s, reading);
  mozc::commands::Context context;
  context.set_preceding_text(preceding);
  const ConversionRequest request =
      MakeRequest(s, composer, context, ConversionRequest::PREDICTION, limit);
  if (!s.converter->StartPrediction(request, &s.segments) ||
      s.segments.conversion_segments_size() == 0) {
    s.converter->CancelConversion(&s.segments);
    return false;
  }
  if (out) FillFlat(s.segments.conversion_segment(0), reading.empty() ? 0 : Chars(reading), limit, out);
  return true;
}

// Converts `reading` and replays `ops`; returns the focused segment.
bool ConvertLocked(State& s, const std::string& reading, const std::string& preceding,
                   const std::vector<int>& ops, int* focus) {
  SyncContext(s, preceding);
  s.converter->CancelConversion(&s.segments);
  if (reading.empty()) return false;
  const mozc::composer::Composer composer = MakeComposer(s, reading);
  mozc::commands::Context context;
  context.set_preceding_text(preceding);
  const ConversionRequest request =
      MakeRequest(s, composer, context, ConversionRequest::CONVERSION, 0);
  if (!s.converter->StartConversion(request, &s.segments) ||
      s.segments.conversion_segments_size() == 0) {
    s.converter->CancelConversion(&s.segments);
    return false;
  }
  // Resizing builds its own request, without the context, as
  // EngineConverter::ResizeSegmentWidth does.
  const ConversionRequest resize = ConversionRequestBuilder()
                                       .SetComposer(composer)
                                       .SetRequestView(*s.request)
                                       .SetConfigView(*s.config)
                                       .Build();
  int at = 0;
  for (int op : ops) {
    const int count = static_cast<int>(s.segments.conversion_segments_size());
    switch (op) {
      case kExpand:
      case kShrink:
        // A refused resize (the last character, a one-character segment)
        // leaves the segments as they were, which is what the user sees.
        (void)s.converter->ResizeSegment(&s.segments, resize, at, op);
        break;
      case kFocusRight:
        at = std::min(at + 1, count - 1);
        break;
      case kFocusLeft:
        at = std::max(at - 1, 0);
        break;
      default:
        break;
    }
  }
  *focus = std::min(at, static_cast<int>(s.segments.conversion_segments_size()) - 1);
  return true;
}

// Reading characters of conversion segments [0, through].
int KeyChars(const Segments& segments, int through) {
  int chars = 0;
  for (int i = 0; i <= through && i < static_cast<int>(segments.conversion_segments_size()); ++i) {
    chars += static_cast<int>(segments.conversion_segment(i).key_len());
  }
  return chars;
}

// Learns what the conversion segments now hold, and keeps them as the
// context for what is typed next.
void Finish(State& s, const std::string& reading, const std::string& preceding) {
  const mozc::composer::Composer composer = MakeComposer(s, reading);
  mozc::commands::Context context;
  context.set_preceding_text(preceding);
  const ConversionRequest request = ConversionRequestBuilder()
                                        .SetComposer(composer)
                                        .SetRequestView(*s.request)
                                        .SetContextView(context)
                                        .SetConfigView(*s.config)
                                        .Build();
  s.converter->FinishConversion(request, &s.segments);
}

}  // namespace

bool Start(const std::string& data_file, const std::string& user_dir, std::string* error) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g) return true;
  mozc::SystemUtil::SetUserProfileDirectory(user_dir);
  auto data = mozc::DataManager::CreateFromFile(data_file);
  if (!data.ok()) {
    if (error) *error = std::string(data.status().message());
    return false;
  }
  auto engine = mozc::Engine::CreateEngine(*std::move(data));
  if (!engine.ok()) {
    if (error) *error = std::string(engine.status().message());
    return false;
  }
  auto s = std::make_unique<State>();
  s->engine = *std::move(engine);
  s->converter = s->engine->GetConverter();
  s->request = MobileRequest();
  s->config = std::make_shared<mozc::config::Config>(mozc::config::ConfigHandler::DefaultConfig());
  // Kana modifier insensitive conversion needs the config's switch as well
  // as the request's; the request's is the one the keyboard toggles.
  s->config->set_use_kana_modifier_insensitive_conversion(true);
  s->table = mozc::composer::Table::GetSharedDefaultTable();
  g = std::move(s);
  return true;
}

void Stop() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g) return;
  g->engine->Sync();
  g->engine->Wait();
  g.reset();
}

bool Started() {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g != nullptr;
}

std::string DataVersion() {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g ? std::string(g->engine->GetDataVersion()) : std::string();
}

void SetKanaModifierInsensitive(bool on) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g || g->request->kana_modifier_insensitive_conversion() == on) return;
  auto request = std::make_shared<mozc::commands::Request>(*g->request);
  request->set_kana_modifier_insensitive_conversion(on);
  g->request = std::move(request);
}

bool Suggest(const std::string& reading, const std::string& preceding, int limit, Menu* out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g || reading.empty()) return false;
  return SuggestLocked(*g, reading, preceding, limit, out);
}

bool Convert(const std::string& reading, const std::string& preceding,
             const std::vector<int>& ops, int limit, Menu* out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g) return false;
  int focus = 0;
  if (!ConvertLocked(*g, reading, preceding, ops, &focus)) return false;
  const Segments& segments = g->segments;
  for (const MSegment& segment : segments.conversion_segments()) {
    out->segments.push_back({std::string(segment.key()),
                             segment.candidates_size() > 0 ? segment.candidate(0).value
                                                           : std::string(segment.key())});
  }
  out->focus = focus;
  const int consumed = KeyChars(segments, focus);
  const MSegment& focused = segments.conversion_segment(focus);
  std::set<std::string> seen;
  for (size_t i = 0; i < focused.candidates_size(); ++i) {
    if (static_cast<int>(out->candidates.size()) >= limit) break;
    const MCandidate& c = focused.candidate(i);
    if (c.attributes & Attribute::COMMAND_CANDIDATE) continue;
    if (c.value.empty() || !seen.insert(c.value).second) continue;
    out->candidates.push_back({c.value, static_cast<int>(i), consumed});
  }
  return true;
}

bool Predict(const std::string& preceding, int limit, Menu* out) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g) return false;
  return SuggestLocked(*g, "", preceding, limit, out);
}

int CommitSuggestion(const std::string& reading, const std::string& preceding, int id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g || reading.empty()) return -1;
  State& s = *g;
  if (!SuggestLocked(s, reading, preceding, 0, nullptr)) return -1;
  const MSegment& segment = s.segments.conversion_segment(0);
  if (id < 0 || id >= static_cast<int>(segment.candidates_size())) return -1;
  const MCandidate& c = segment.candidate(id);
  const int chars = Chars(reading);
  int consumed = chars;
  if (c.attributes & Attribute::PARTIALLY_KEY_CONSUMED) {
    consumed = std::clamp(static_cast<int>(c.consumed_key_size), 1, chars);
  }
  if (consumed < chars) {
    // EngineConverter's partial-suggestion commit: the picked part becomes a
    // SUBMITTED segment ahead of a new one holding the rest. Mozc learns a
    // SUBMITTED segment only when the whole reading is finished; the
    // keyboard converts the rest as a reading of its own, so it is finished
    // here, with the rest dropped.
    if (!s.converter->CommitPartialSuggestionSegmentValue(
            &s.segments, 0, id, Util::Utf8SubString(reading, 0, consumed),
            Util::Utf8SubString(reading, consumed))) {
      s.converter->CancelConversion(&s.segments);
      return -1;
    }
    s.converter->CancelConversion(&s.segments);
  } else if (!s.converter->CommitSegmentValue(&s.segments, 0, id)) {
    s.converter->CancelConversion(&s.segments);
    return -1;
  }
  Finish(s, reading, preceding);
  return consumed;
}

int CommitConversion(const std::string& reading, const std::string& preceding,
                     const std::vector<int>& ops, int id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g) return -1;
  State& s = *g;
  int focus = 0;
  if (!ConvertLocked(s, reading, preceding, ops, &focus)) return -1;
  const MSegment& focused = s.segments.conversion_segment(focus);
  if (id < 0 || id >= static_cast<int>(focused.candidates_size())) return -1;
  const int consumed = KeyChars(s.segments, focus);
  // Bracket pairing and the like, as a candidate move in the session does.
  (void)s.converter->FocusSegmentValue(&s.segments, focus, id);
  const int count = static_cast<int>(s.segments.conversion_segments_size());
  if (focus + 1 >= count) {
    for (int i = 0; i < count; ++i) {
      (void)s.converter->CommitSegmentValue(&s.segments, i, i == focus ? id : 0);
    }
  } else {
    // The segments before the focus keep their best candidate, as they do
    // on screen; CommitSegments submits each one, and the rest is the
    // keyboard's next reading.
    std::vector<size_t> ids(focus + 1, 0);
    ids[focus] = static_cast<size_t>(id);
    if (!s.converter->CommitSegments(&s.segments, ids)) {
      s.converter->CancelConversion(&s.segments);
      return -1;
    }
    s.converter->CancelConversion(&s.segments);
  }
  Finish(s, reading, preceding);
  return consumed;
}

int CommitPrediction(const std::string& preceding, int id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g) return -1;
  State& s = *g;
  if (!SuggestLocked(s, "", preceding, 0, nullptr)) return -1;
  const MSegment& segment = s.segments.conversion_segment(0);
  if (id < 0 || id >= static_cast<int>(segment.candidates_size())) return -1;
  if (!s.converter->CommitSegmentValue(&s.segments, 0, id)) {
    s.converter->CancelConversion(&s.segments);
    return -1;
  }
  Finish(s, "", preceding);
  return 0;
}

void ResetContext() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g) g->converter->ResetConversion(&g->segments);
}

bool Sync() {
  std::lock_guard<std::mutex> lock(g_mutex);
  return g && g->engine->Sync();
}

bool ClearHistory() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g) return false;
  g->converter->ResetConversion(&g->segments);
  const bool history = g->engine->ClearUserHistory();
  const bool prediction = g->engine->ClearUserPrediction();
  g->engine->Sync();
  return history && prediction;
}

}  // namespace wmmozc
