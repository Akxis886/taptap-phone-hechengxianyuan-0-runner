#!/system/bin/sh
set -eu
BASE=/data/local/tmp/taptap-phone
cd "$BASE"
alive() {
  [ -f "$BASE/pid" ] || return 1
  pid=$(cat "$BASE/pid")
  case "$pid" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' < "/proc/$pid/cmdline" | grep -q PhoneBot
}
action=${1:-start}
[ "$#" -eq 0 ] || shift
case "$action" in
  start)
    if alive; then echo "Already running: $(cat "$BASE/pid")"; exit 0; fi
    rm -f "$BASE/STOP"
    export CLASSPATH="$BASE/classes.dex"
    nohup /system/bin/app_process -Xmx256m /system/bin PhoneBot "$@" >> "$BASE/run.log" 2>&1 < /dev/null &
    echo "$!" > "$BASE/pid"
    sleep 1
    if alive; then echo "Started locally. Log: $BASE/run.log"; else tail -20 "$BASE/run.log"; exit 1; fi
    ;;
  check|read|ads-only)
    export CLASSPATH="$BASE/classes.dex"
    exec /system/bin/app_process -Xmx256m /system/bin PhoneBot "--$action" "$@"
    ;;
  stop) touch "$BASE/STOP"; echo "Stop requested; current capture/UI call will finish before exit.";;
  status) if alive; then echo "RUNNING $(cat "$BASE/pid")"; else echo STOPPED; fi; tail -8 "$BASE/run.log" 2>/dev/null || true;;
  *) echo "Use: run.sh start|stop|status|check|read|ads-only [options]" >&2; exit 2;;
esac
