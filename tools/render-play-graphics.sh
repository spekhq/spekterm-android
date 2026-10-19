#!/usr/bin/env bash
# Renders the Play listing's icon (512 × 512, 32-bit PNG) and feature graphics (1024 × 500, 24-bit PNG, one per locale)
# from play/source/ with headless Google Chrome, then strips the feature graphics' alpha with ImageMagick (`play-release`
# D6). Look at the results before committing them.
set -euo pipefail
cd "$(dirname "$0")/.."
src="$PWD/play/source"
chrome="${CHROME:-google-chrome}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

shot() { # url width height out
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --default-background-color=00000000 --window-size="$2,$3" --screenshot="$4" "$1" >/dev/null 2>&1
}

printf '<!doctype html><style>html,body{margin:0;background:transparent}</style><img src="%s/icon.svg" width="512" height="512">' "$src" > "$tmp/icon.html"
for locale in en-US zh-TW; do
  out="play/listings/$locale/graphics"
  mkdir -p "$out/icon" "$out/feature-graphic"
  shot "file://$tmp/icon.html" 512 512 "$tmp/icon.png"
  convert "$tmp/icon.png" "PNG32:$out/icon/icon.png"
  shot "file://$src/feature.html?lang=$locale" 1024 500 "$tmp/feature.png"
  convert "$tmp/feature.png" -background '#0b0d10' -alpha remove -alpha off "PNG24:$out/feature-graphic/feature.png"
done
file play/listings/*/graphics/*/*.png
