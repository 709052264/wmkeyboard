#!/usr/bin/env bash
# The old name of build-fdroid-libs.sh, from when F-Droid built a `lite`
# edition. fdroiddata's bot writes each new recipe entry by copying the last
# one, `build: ../native/build-lite-libs.sh` included, so this name has to keep
# working until a merge request there switches it (fdroid/README.md).
exec "$(dirname "$0")/build-fdroid-libs.sh" "$@"
