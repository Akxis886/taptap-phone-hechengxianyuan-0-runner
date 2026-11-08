#!/usr/bin/env bash
set -euo pipefail
# Set to the published, immutable commit's raw directory. No unverified default URL.
: "${BOT_BASE_URL:?Set BOT_BASE_URL to the published raw directory or use the downloaded bundle launch.sh}"
case "$BOT_BASE_URL" in https://raw.githubusercontent.com/*) ;; *) echo 'Expected a GitHub raw HTTPS directory' >&2; exit 2;; esac
BOT_INSTALL_DIR=${BOT_INSTALL_DIR:-"$HOME/.local/share/taptap-phone"}
mkdir -p "$BOT_INSTALL_DIR/dist"
for file in launch.sh run.sh SHA256SUMS dist/classes.dex dist/labels.bin; do
  curl --fail --show-error --location --proto '=https' --tlsv1.2 "$BOT_BASE_URL/$file" -o "$BOT_INSTALL_DIR/$file.new"
  mv -- "$BOT_INSTALL_DIR/$file.new" "$BOT_INSTALL_DIR/$file"
done
cd "$BOT_INSTALL_DIR"
sha256sum -c SHA256SUMS
exec bash ./launch.sh "$@"
