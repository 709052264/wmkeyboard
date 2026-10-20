// The part of Mozc WM Keyboard talks to: one engine, one converter state,
// driven by whole readings rather than keystrokes. Kept free of JNI so the
// host test drives exactly what the phone runs (host_test.cc).
//
// Readings are hiragana (the keyboard transliterates romaji itself, so its
// spans stay its own), and every length is in Unicode characters of that
// reading, as Mozc's own Util::CharsLen counts them.
#pragma once

#include <string>
#include <vector>

namespace wmmozc {

struct Candidate {
  std::string value;
  // The converter's index of this candidate in its segment; what a commit
  // passes back.
  int id = 0;
  // Reading characters the candidate converts, from the start of the reading.
  int consumed = 0;
};

struct Segment {
  std::string key;    // its reading
  std::string value;  // its best conversion
};

struct Menu {
  std::vector<Candidate> candidates;
  // Conversion only: the reading as Mozc split it, and which segment the
  // candidates are for.
  std::vector<Segment> segments;
  int focus = 0;
};

// Segment operations a conversion replays, in order, from a fresh one.
enum Op : int {
  kExpand = 1,       // the focused segment takes one more character
  kShrink = -1,      // the focused segment gives one back
  kFocusRight = 2,
  kFocusLeft = -2,
};

// Opens Mozc on its system dictionary (the mozc.data file) with the user's
// learning in user_dir. False with a reason in *error when the data does not
// load; nothing else works until this succeeds.
bool Start(const std::string& data_file, const std::string& user_dir,
           std::string* error);

// Saves what was learnt and closes the engine.
void Stop();

bool Started();

std::string DataVersion();

// Whether か also finds が and っ (Mozc's kana modifier insensitive
// conversion), the keyboard's "Match kana without marks".
void SetKanaModifierInsensitive(bool on);

// The list the keyboard shows while a reading is typed: Mozc's mobile
// prediction, whole-reading conversions and the partial ones that cover only
// its start. `preceding` is the text before the caret, for context.
bool Suggest(const std::string& reading, const std::string& preceding,
             int limit, Menu* out);

// The reading converted by segments, with `ops` applied, and the candidates
// of the focused segment. A candidate there consumes every segment up to and
// including the focused one.
bool Convert(const std::string& reading, const std::string& preceding,
             const std::vector<int>& ops, int limit, Menu* out);

// What could follow `preceding` with nothing typed (zero-query suggestion).
bool Predict(const std::string& preceding, int limit, Menu* out);

// Commits candidate `id` of what Suggest/Convert/Predict answered for the
// same arguments, so Mozc learns it. Returns the reading characters it
// consumed, or -1 when there was no such candidate.
int CommitSuggestion(const std::string& reading, const std::string& preceding,
                     int id);
int CommitConversion(const std::string& reading, const std::string& preceding,
                     const std::vector<int>& ops, int id);
int CommitPrediction(const std::string& preceding, int id);

// Forgets the conversion context (the caret moved, the field changed).
void ResetContext();

// Writes the learnt history to user_dir.
bool Sync();

// Forgets every learnt conversion and prediction.
bool ClearHistory();

}  // namespace wmmozc
