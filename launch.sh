#!/usr/bin/env bash
# Run in the phone's DSH Ubuntu/Termux terminal, using its authorized local ADB client.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v adb >/dev/null || { echo 'ADB missing in THIS DSH terminal. Install adb, then pair/connect its client to this phone. DSH App pairing may use a separate key.' >&2; exit 2; }
if [[ -n ${DSH_ADB_ENDPOINT:-} ]]; then
  [[ $DSH_ADB_ENDPOINT =~ ^[a-zA-Z0-9.:-]+$ ]] || { echo 'Invalid ADB endpoint' >&2; exit 2; }
  adb connect "$DSH_ADB_ENDPOINT"
fi
if [[ -n ${ANDROID_SERIAL:-} ]]; then
  serial=$ANDROID_SERIAL
else
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ ${#devices[@]} -eq 1 ]] || { echo 'Need exactly one authorized device in adb devices. Set ANDROID_SERIAL if several are connected.' >&2; exit 2; }
  serial=${devices[0]}
fi
bridge=(adb -s "$serial")
action=${1:-start}
[[ $# -eq 0 ]] || shift
case "$action" in start|check|read|ads-only|stop|status) ;; *) echo 'Unknown action' >&2; exit 2;; esac
# Keep shell arguments numeric or fixed flags; never interpolate arbitrary remote shell code.
for arg in "$@"; do [[ $arg =~ ^(--steps|--seconds|--skip-ads|[0-9]+)$ ]] || { echo "Unsupported argument: $arg" >&2; exit 2; }; done
if [[ $action != stop && $action != status ]]; then
  if [[ $action == start ]] && "${bridge[@]}" shell 'test -f /data/local/tmp/taptap-phone/run.sh && sh /data/local/tmp/taptap-phone/run.sh status' 2>/dev/null | grep -q '^RUNNING '; then
    echo 'Already running locally. Use launch.sh stop before replacing the runtime.'
    exit 0
  fi
  sha256sum -c SHA256SUMS
  "${bridge[@]}" shell mkdir -p /data/local/tmp/taptap-phone
  "${bridge[@]}" push dist/classes.dex dist/labels.bin run.sh /data/local/tmp/taptap-phone/
fi
"${bridge[@]}" shell sh /data/local/tmp/taptap-phone/run.sh "$action" "$@"
