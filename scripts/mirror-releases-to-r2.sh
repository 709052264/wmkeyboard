#!/usr/bin/env bash
# Copies the data repository's `models` and `natives` release assets to a
# Cloudflare R2 bucket, under the same names, for the app's download mirror
# (DataMirror in core/common). Keys are `<tag>/<sha256>-<name>`, exactly the
# asset name on GitHub, so the pins built into the app check the mirror's bytes
# the same way they check GitHub's.
#
# Idempotent: an asset already in the bucket at the right size is skipped. Each
# file is fetched from GitHub with 16 ranged connections (aria2c), its SHA-256
# checked against the hash its name starts with, then uploaded.
#
# Upload goes through rclone and R2's S3 API when R2_ACCESS_KEY_ID and
# R2_SECRET_ACCESS_KEY are set (any size); otherwise through wrangler, which
# refuses files over 315 MB, so those are listed at the end as skipped.
#
#   scripts/mirror-releases-to-r2.sh                 # both releases
#   TAGS="models" scripts/mirror-releases-to-r2.sh   # one
#
# Environment:
#   BUCKET                bucket name (default wmkeyboard-data)
#   REPO                  GitHub repo (default wasi-master/wmkeyboard-data)
#   TAGS                  releases to copy (default "models natives")
#   MIRROR_BASE           the bucket's public address, e.g. https://pub-….r2.dev;
#                         used to skip what is already there when uploading with wrangler
#   R2_ACCOUNT_ID         Cloudflare account id (rclone only)
#   R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY   an R2 API token's S3 keys (rclone only)
set -euo pipefail

BUCKET=${BUCKET:-wmkeyboard-data}
REPO=${REPO:-wasi-master/wmkeyboard-data}
TAGS=${TAGS:-models natives}
WRANGLER_LIMIT=$((315 * 1000 * 1000))
CACHE_CONTROL="public, max-age=31536000, immutable"

for tool in gh aria2c shasum; do
  command -v "$tool" >/dev/null || { echo "needs $tool" >&2; exit 1; }
done

use_rclone=0
if [[ -n "${R2_ACCESS_KEY_ID:-}" && -n "${R2_SECRET_ACCESS_KEY:-}" ]]; then
  command -v rclone >/dev/null || { echo "needs rclone for S3 uploads" >&2; exit 1; }
  : "${R2_ACCOUNT_ID:?set R2_ACCOUNT_ID for the S3 endpoint}"
  use_rclone=1
  # An on-the-fly remote: nothing written to rclone.conf.
  export RCLONE_CONFIG_R2_TYPE=s3
  export RCLONE_CONFIG_R2_PROVIDER=Cloudflare
  export RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
  export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
  export RCLONE_CONFIG_R2_ENDPOINT="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
  export RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
skipped=()

# Size of $1 in the bucket, or empty when it is not there.
remote_size() {
  local key=$1
  if (( use_rclone )); then
    rclone lsjson "r2:$BUCKET/$key" 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["Size"] if d else "")' 2>/dev/null || true
  elif [[ -n "${MIRROR_BASE:-}" ]]; then
    curl -sfI "${MIRROR_BASE%/}/$key" | awk 'tolower($1)=="content-length:"{print $2}' | tr -d '\r' || true
  fi
}

upload() {
  local file=$1 key=$2 size=$3
  if (( use_rclone )); then
    rclone copyto "$file" "r2:$BUCKET/$key" \
      --header-upload "Cache-Control: $CACHE_CONTROL" \
      --header-upload "Content-Type: application/octet-stream" \
      --s3-chunk-size 64M --s3-upload-concurrency 4
  elif (( size > WRANGLER_LIMIT )); then
    skipped+=("$key ($size bytes: over wrangler's limit, set the R2_* keys)")
    return
  else
    npx -y wrangler@4 r2 object put "$BUCKET/$key" --file "$file" --remote \
      --content-type application/octet-stream --cache-control "$CACHE_CONTROL"
  fi
}

for tag in $TAGS; do
  gh release view "$tag" -R "$REPO" --json assets -q '.assets[] | "\(.name)\t\(.size)"' >"$work/$tag.tsv"
  while IFS=$'\t' read -r name size; do
    key="$tag/$name"
    have=$(remote_size "$key")
    if [[ "$have" == "$size" ]]; then
      echo "have   $key"
      continue
    fi
    echo "copy   $key ($size bytes)"
    aria2c -q -x 16 -s 16 -k 1M --allow-overwrite=true -d "$work" -o "$name" \
      "https://github.com/$REPO/releases/download/$tag/$name"
    want=${name%%-*}
    got=$(shasum -a 256 "$work/$name" | cut -d' ' -f1)
    if [[ ${#want} -eq 64 && "$got" != "$want" ]]; then
      echo "sha256 mismatch for $key: $got" >&2
      exit 1
    fi
    upload "$work/$name" "$key" "$size"
    rm -f "$work/$name"
  done <"$work/$tag.tsv"
done

if (( ${#skipped[@]} )); then
  echo
  echo "Not uploaded:"
  printf '  %s\n' "${skipped[@]}"
fi
