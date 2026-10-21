# librime-jni: the RIME engine for Chinese

[librime](https://github.com/rime/librime) is the conversion engine behind
Weasel, Squirrel, ibus-rime, fcitx5-rime and Trime. This directory builds it
into `libwmrime.so`, one shared library with a small JNI bridge, which the
keyboard uses as an optional engine for Chinese and Cantonese. Kotlin entry
point: `com.wasimaster.wmkeyboard.core.input.rime.RimeNative` (in
`core/input/src/main`), driven by `RimeRuntime` and `RimeComposer`.

The built library is committed under `core/input/src/main/jniLibs/`, so the
app builds without an NDK. It never ships inside an APK, though: Play builds
deliver it as the on-demand `:feature:rime` split, and the GitHub and F-Droid
builds pin it in `assets/native/pins.tsv` under the `rime` gate and fetch it
from the data repository's `natives` release when someone picks RIME for a
language. F-Droid deletes the committed copies and builds them with this
script (through `native/build-fdroid-libs.sh`), and so does the release
workflow for the reference APK. Rebuild after you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/librime-jni/build.sh
```

It needs git, curl, CMake and Ninja on `PATH`. It fetches librime at the
pinned tag with the four submodules it uses, and Boost's release tarball,
checked against its SHA-256, unless `RIME_SRC` and `BOOST_SRC` point at them.
One ABI takes about four minutes on an M-series Mac; `WMKB_ABIS` narrows the
build. The library is linked with 16 KB pages, exports only the
`Java_…_RimeNative_*` functions, and the build maps every path on the build
machine out of it.

## Provenance

| Component | Version | Licence |
|---|---|---|
| librime | `1.17.0`, commit `33e78140250125871856cdc5b42ddc6a5fcd3cd4` | BSD-3-Clause |
| LevelDB | librime's submodule, `99b3c03b3284f5886f9ef9a4ef703d57373e61be` | BSD-3-Clause |
| marisa-trie | librime's submodule, `3e87d53b78e15f2f43783d5e376561a8c9722051` | BSD-2-Clause or LGPL-2.1+, used under BSD-2-Clause |
| OpenCC | librime's submodule, `556ed22496d650bd0b13b6c163be9814637970ae` | Apache-2.0 |
| yaml-cpp | librime's submodule, `2f86d13775d119edbb69af52e5f566fd65c6953b` | MIT |
| Boost | `1.92.0` (`boost-1.92.0-cmake.tar.xz`, SHA-256 `9bed7612…088d`), headers only | BSL-1.0 |

All unmodified. Left out on purpose: glog (librime logs nothing in this
build), snappy (user dictionaries are small and never leave this library),
and every librime plugin, Lua included, so schemas that call Lua components
(Rime Ice's date, calculator and correction scripts) skip them. librime-lua
(`hchunhui/librime-lua` 6da5d6c with Lua 5.4.7) was measured linked in: the
arm64 library grows from 2,871,624 to 3,837,288 bytes, a third. It stays
out: what Rime Ice's scripts add the built-in engine mostly has already
(date and time shortcuts, Chinese numerals), and Lua runs with its `io` and `os`
libraries in the keyboard's process, so any imported schema zip would run
code that sees every keystroke. The
licences of all of it are in the app as
`app/src/main/assets/licenses/rime-third-party.txt`.

Trime (GPL-3.0) and fcitx5-android (LGPL-2.1+) were read as references for
the dependency chain and for which Boost libraries librime needs. No code
from either is used here. Unlike Trime, this build compiles OpenCC's library
only and ships OpenCC's tables as plain text, which OpenCC reads directly,
so no host tool has to run during a cross build.

The schemas are not bundled. The keyboard downloads them as zips from the
data repository (`cjk/rime/`, built by `scripts/build_rime_packs.py` there
from upstream at pinned commits) and checks each against the SHA-256 in
`RimeSchemaCatalog`. Each zip carries its upstream licence under
`licenses/`: the RIME project's schemas are LGPL-3.0, Rime Ice is GPL-3.0,
rime-cantonese is CC BY 4.0 with ODbL for its data.

## Contract with Kotlin

Strings cross as UTF-16 both ways, never through `NewStringUTF`, so
characters outside the Basic Multilingual Plane survive. Offsets come back
as UTF-8 byte offsets into the input, as librime counts them.

- `nativeBeginDeploy(shared, user, version)`, `nativeDeploySchema(file)`,
  `nativeDeployConfig(name, key)`: compile schemas with the service stopped.
- `nativeStart(shared, user, version)` / `nativeStop()`: run the service on
  what was compiled. Start also writes the installation id the user
  dictionaries are keyed by, and nothing else: schemas are only ever compiled
  by an explicit deploy.
- `nativeCreateSession()`, `nativeSelectSchema`, `nativeSetOption`,
  `nativeSetInput(session, input)`: one session, given the whole reading.
- `nativeMenu(session, limit, spans, comments)`: candidates of the segment
  being composed. `spans[0]` is where that segment starts, `spans[i + 1]`
  where candidate `i` ends. This is librime's own `Candidate::end()`, read
  through its C++ classes because the C API does not expose it, and it is
  what makes the keyboard's prefix commit exact: picking 你 for `nihao`
  consumes `ni`. `comments`, when not null, gets each candidate's comment,
  which in a schema with `spelling_hints` is the spelling it was found under:
  the T9 pad reads its syllables from there (`xi an` for 西安 typed `94'26`).
- `nativeSelect(session, index)`: picks candidate `index` the way a tap does.
  When the pick finishes the input, the session commits and its user
  dictionary learns the phrase.

## Testing on a desktop

`host-test.sh` builds `rime_host_test`, which drives `rime_bridge.cc` against
real packs, and the same JNI library for a desktop JVM. The second is how the
Kotlin side was checked end to end: deploy, spans, prefix picks, learning,
Simplified and Traditional, Zhuyin and Jyutping.

```sh
rime_host_test shared/ user/ luna_pinyin nihaoshijie 1 0
```

prints the menu for `nihaoshijie`, picks 你好 (index 1), shows the next
segment starting at byte 5, picks 世界 and reports the commit. Each run
deploys every schema in `shared/` first, which takes over a minute with Rime
Ice; `RIME_SKIP_DEPLOY=1` starts on what the last run compiled into `user/`.
