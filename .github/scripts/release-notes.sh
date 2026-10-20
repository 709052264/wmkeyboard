#!/usr/bin/env bash
# Builds the body of a GitHub release: highlights, a download grid with real
# file sizes, and a grouped changelog of every commit since the previous tag.
#
#   release-notes.sh <version> <versionCode> <tag> <dist-dir> [<owner/repo>]
#
# Writes markdown to stdout. Needs the repo checked out with full history
# (fetch-depth: 0) so the previous tag can be found.
set -euo pipefail

# A release body keeps every single newline as a line break, so the 80-column
# wrapping in release-notes/*.md and the prose below would render as a narrow
# column. Run the whole script through a filter that rejoins paragraphs.
if [ -z "${WMKB_NOTES_UNWRAPPED:-}" ]; then
  WMKB_NOTES_UNWRAPPED=1 "$0" "$@" | python3 "$(dirname "$0")/unwrap-markdown.py"
  exit 0
fi

version=${1:?version}
code=${2:?versionCode}
tag=${3:?tag}
dist=${4:?dist directory}
repo=${5:-${GITHUB_REPOSITORY:-wasi-master/wmkeyboard}}

base="https://github.com/${repo}/releases/download/${tag}"
prefix="wmkeyboard-${version}-vc${code}"

# ---------------------------------------------------------------- helpers

# Human size for a file in dist/, or "not built" when the build did not
# produce it (a release cut before native symbols existed, for one).
size_of() {
  local f="$dist/$1"
  [ -f "$f" ] || { printf 'not built'; return; }
  # stat's flags differ between BSD and GNU; wc -c is the same everywhere.
  local bytes
  bytes=$(wc -c < "$f" | tr -d ' ')
  awk -v b="$bytes" 'BEGIN {
    if (b >= 1073741824) printf "%.1f GB", b / 1073741824
    else if (b >= 1048576) printf "%.0f MB", b / 1048576
    else if (b >= 1024) printf "%.0f KB", b / 1024
    else printf "%d B", b
  }'
}

# A download cell: a linked size, or plain "not built" when the file is
# absent, so a missing artifact says so instead of offering a broken link.
cell() {
  local file="$1" emphasis="${2:-}" sz
  sz=$(size_of "$file")
  if [ "$sz" = 'not built' ]; then printf 'not built'; return; fi
  printf '%s[%s](%s/%s)%s' "$emphasis" "$sz" "$base" "$file" "$emphasis"
}

# ---------------------------------------------------------------- highlights

# Hand-written notes for this version win. Without them, fall back to the
# store changelog, which every release has to write anyway.
notes_file="release-notes/${version}.md"
store_changelog="fastlane/metadata/android/en-US/changelogs/${code}.txt"

# Open by default: a reader who wants the prose gets it without a click, and
# one who came for the download can fold it away.
echo '<details open>'
echo "<summary><h2>What's new in ${version}</h2></summary>"
echo
if [ -f "$notes_file" ]; then
  cat "$notes_file"
else
  if [ -f "$store_changelog" ]; then
    cat "$store_changelog"
  else
    echo "_No notes were written for this release._"
  fi
  echo
fi
echo
echo '</details>'
echo

# ---------------------------------------------------------------- downloads

# A release with no APKs attached, such as a tag cut before the build
# pipeline existed, gets notes and nothing else rather than an empty grid.
have_apk=false
for f in "$dist"/*.apk; do
  [ -e "$f" ] && have_apk=true && break
done

if [ "$have_apk" = true ]; then
cat <<EOF
## Download

Two builds. **Standard** is the one almost everyone wants. **Offline** is for
a keyboard that can never reach the network.

**Standard** fetches what you use. The interface is English, and on Android 11
or later the app downloads any other of its 48 languages when you pick it
(about 1.3 MB each). Features that bring their own code, like offline voice
typing, grammar checking, the scanner, handwriting, on-device translation and
on-device AI, download it the first time you use them. Every download is
checked against a checksum built into the app. What you can *type* is the same
either way: every layout and every dictionary is there.

Almost every phone sold since 2017 takes an **arm64-v8a** build, so start here:

| | arm64-v8a |
|:---|:---|
| **⭐ Standard** | **$(cell "${prefix}-full-en-arm64-v8a.apk")** |

<details>
<summary><b>Other architectures</b> (older 32-bit phones, emulators, ChromeOS, or if the above refuses to install)</summary>

| Architecture | Standard |
|:---|:---|
| armeabi-v7a<br><sub>Older 32-bit phones and watches</sub> | $(cell "${prefix}-full-en-armeabi-v7a.apk") |
| x86_64<br><sub>Emulators, ChromeOS, x86 tablets</sub> | $(cell "${prefix}-full-en-x86_64.apk") |
| universal<br><sub>All three in one file. Only if the others refuse to install</sub> | $(cell "${prefix}-full-en-universal.apk") |

</details>

EOF

# The no-internet build, from the `offline` job. Releases before it existed
# carry neither file, and get no section rather than one saying "not built".
if [ -f "$dist/${prefix}-full-intl-nointernet-arm64-v8a.apk" ] \
  || [ -f "$dist/${prefix}-full-intl-nointernet-universal.apk" ]; then
cat <<EOF
<details>
<summary><b>Offline</b> (a keyboard that cannot reach the network at all)</summary>

The standard build with \`android.permission.INTERNET\` taken out of the
manifest, so Android refuses every connection the app tries to make, and with
everything it would otherwise download built in: all 48 interface languages and
every feature library. Typing, prediction, gesture typing, the clipboard, grammar
checking, the scanners and a local AI model imported from a file all work.
Anything that downloads (dictionary packs, voice and handwriting models,
translation languages, GIFs, cloud backups) fails as it would with the phone
offline, and the app cannot check for its own updates, so come back here for new
versions. Language packs, voice models, OCR data and offline GIF packs fetched
on another device can be imported instead
([how](https://wmkeyboard.pages.dev/privacy/offline/)).
Signed with the same key as the standard build, so either installs over the
other without losing a setting.

| | arm64-v8a | universal |
|:---|:---|:---|
| **Offline** | $(cell "${prefix}-full-intl-nointernet-arm64-v8a.apk") | $(cell "${prefix}-full-intl-nointernet-universal.apk") |

</details>

EOF
fi

# The interface language packs the standard build downloads, for importing on
# a phone that cannot fetch them itself. Listed, not described one by one.
packs=$(cd "$dist" && ls ${prefix}-lang-*.apk 2>/dev/null | wc -l | tr -d ' ')
if [ "$packs" -gt 0 ]; then
pack_list=$(cd "$dist" && for f in ${prefix}-lang-*.apk; do echo "- [\`$f\`](${base}/$f) · $(size_of "$f")"; done)
cat <<EOF
<details>
<summary><b>Interface language packs</b> ($packs, for importing by hand)</summary>

The standard build downloads these itself when you pick a language. Each is
signed with the release key and only works with this version.

$pack_list

</details>

EOF
fi

# The extras block lists only what this release actually carries: mappings and
# native symbols were first attached in 0.5.8, so an older release has nothing
# under the checksums but the checksums.
extras=''
row() { # <file> <what it is>
  [ -f "$dist/$1" ] || return 0
  extras="${extras}| [\`$1\`](${base}/$1) | $(size_of "$1") | $2 |
"
}
row SHA256SUMS.txt 'Checksums for everything above'
row "${prefix}-fdroid.apk" 'The F-Droid build, **all languages, no Google libraries**. F-Droid rebuilds it from source and publishes it only if the two match, so it is the same APK F-Droid serves, byte for byte. Installs over an F-Droid install from 0.5.10 on'
# From 0.6.2 one per ABI, named by the full versionCode (release * 10 + ABI
# digit), since that is all F-Droid's `Binaries:` pattern can name them by.
fdroid_row() { # <abi> <digit> <who>
  row "wmkeyboard-${version}-vc$((code * 10 + $2))-fdroid.apk" "The F-Droid build for **$1**, **all languages, no Google libraries**. F-Droid rebuilds it from source and publishes it only if the two match, so it is the same APK F-Droid serves $3, byte for byte. Installs over an F-Droid install from 0.5.10 on"
}
fdroid_row arm64-v8a 2 'to most phones'
fdroid_row armeabi-v7a 1 'to 32-bit phones'
fdroid_row x86_64 3 'to x86 Chromebooks and tablets'
row "${prefix}-full-en-mapping.txt.gz" 'R8 mapping, **standard build**. Retraces a stack trace'
row "${prefix}-full-intl-mapping.txt.gz" 'R8 mapping, **offline build** (full · all languages, which it is apart from its manifest)'
row "${prefix}-full-intl-nointernet-mapping.txt.gz" 'R8 mapping, **offline build**. Only attached when it differs from full · all languages, which otherwise serves both'
row "${prefix}-play-mapping.txt.gz" 'R8 mapping, **Play build**. The Play channel flags change what R8 renames, so this is the only one that reads a crash from a Play install'
row "${prefix}-full-intl-native-symbols.zip" 'Native debug symbols, **full · all languages**'
row "${prefix}-full-en-native-symbols.zip" 'Native debug symbols, **standard build**'

if [ -n "$extras" ]; then
  # Name only what is in the block. Mappings and symbols were first attached in
  # 0.5.8, so an older release's block is checksums alone.
  if [ -f "$dist/${prefix}-full-intl-mapping.txt.gz" ]; then
    summary='Checksums, R8 mappings and native symbols'
    blurb='For verifying a download, and for reading a crash report from these APKs.'
  else
    summary='Checksums'
    blurb='For verifying what you downloaded.'
  fi
  echo '<details>'
  echo "<summary><b>${summary}</b></summary>"
  echo
  echo "$blurb"
  echo
  echo '| File | Size | What it is |'
  echo '|:---|:---|:---|'
  printf '%s' "$extras"
  echo
  if [ -f "$dist/SHA256SUMS.txt" ]; then
    cat <<'EOF'
Verify a download before installing it:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
```

EOF
  fi
  if [ -f "$dist/${prefix}-full-intl-mapping.txt.gz" ]; then
    cat <<EOF
Retrace a crash with the mapping from the same build and version. The
flavour has to match on both axes, because R8 renames the two language
builds independently:

\`\`\`sh
gunzip -k ${prefix}-full-intl-mapping.txt.gz
retrace ${prefix}-full-intl-mapping.txt stacktrace.txt
\`\`\`

A crash from the Play build takes \`${prefix}-play-mapping.txt.gz\` instead:
that build is compiled with different flags, so R8 renames it differently and
none of the four above fit it.

Or paste the crash into a bug report and let the
[retrace workflow](https://github.com/${repo}/blob/main/.github/workflows/retrace.yml) do all
of this for you — it reads the \`version:\` line, fetches the matching mapping
from this release, and comments the retraced trace on the issue.

EOF
  fi
  echo '</details>'
  echo
fi
fi

# ---------------------------------------------------------------- footer

# The grouped commit list that used to live here is gone: the prose above is
# the changelog, and the compare view is a better place to read every commit
# than a wall of subjects in the release body.
prev=$(git describe --tags --abbrev=0 "${tag}^" 2>/dev/null || true)
if [ -n "$prev" ]; then
  count=$(git log --no-merges --oneline "${prev}..${tag}" | wc -l | tr -d ' ')
  echo "${count} commits since [${prev#v}](https://github.com/${repo}/releases/tag/${prev}). [See every one](https://github.com/${repo}/compare/${prev}...${tag})."
fi
