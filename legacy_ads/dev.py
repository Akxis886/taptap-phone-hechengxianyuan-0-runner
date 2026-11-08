"""通用设备层：纯 adb，不依赖任何调试通道。"""
import os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

ADB = None


def adb_path():
    global ADB
    if ADB:
        return ADB
    cands = [os.environ.get("ADB") or "", "adb",
             os.path.join(HERE, "platform-tools", "adb.exe"),
             os.path.join(HERE, "platform-tools", "adb")]
    for c in cands:
        if not c:
            continue
        try:
            if subprocess.run([c, "version"], capture_output=True).returncode == 0:
                ADB = c
                return ADB
        except Exception:
            continue
    raise RuntimeError("找不到 adb")


def dev_serial():
    a = adb_path()
    r = subprocess.run([a, "devices"], capture_output=True, text=True)
    for ln in r.stdout.splitlines()[1:]:
        if "\tdevice" in ln:
            return ln.split("\t")[0]
    raise RuntimeError("没有已授权设备")


_S = None


def serial():
    global _S
    if not _S:
        _S = dev_serial()
    return _S


def run(args, timeout=40):
    a = adb_path()
    p = subprocess.run([a, "-s", serial()] + args, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def sh(cmd, timeout=40):
    rc, out, err = run(["shell", cmd], timeout)
    return out.decode("utf8", "ignore")


def tap(x, y):
    sh("input tap %d %d" % (int(x), int(y)))


def tapmany(pts, gap=0.05):
    for (x, y) in pts:
        tap(x, y)
        if gap:
            time.sleep(gap)


def swipe(x1, y1, x2, y2, ms=220):
    sh("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms))


def key(code):
    sh("input keyevent %s" % code)


def focus():
    o = sh("dumpsys window | grep -m1 mCurrentFocus")
    m = re.search(r"u0 ([\w.]+)/([\w.$]+)", o)
    return ("%s/%s" % (m.group(1), m.group(2))) if m else o.strip()[:80]


def where():
    f = focus()
    if "h5game" in f or "H5Game" in f:
        return "game"
    if "miui.home" in f or "launcher" in f.lower():
        return "home"
    if "com.taptap" in f:
        return "taptap"
    return "other:" + f


def shot(path=None, timeout=60):
    """整屏截图，返回 png bytes（同时落盘）。"""
    path = path or os.path.join(HERE, "_diag", "_now.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    a = adb_path()
    p = subprocess.run([a, "-s", serial(), "exec-out", "screencap", "-p"],
                       capture_output=True, timeout=timeout)
    data = p.stdout
    if not data.startswith(b"\x89PNG"):
        # 极少数情况下 \r\n 被转换，过滤一下
        i = data.find(b"\x89PNG")
        data = data[i:] if i >= 0 else data
    with open(path, "wb") as f:
        f.write(data)
    return data


def devtools_socks():
    o = sh("cat /proc/net/unix")
    names = set()
    for ln in o.splitlines():
        if "devtools" in ln:
            names.add(ln.strip().split()[-1].lstrip("@"))
    return sorted(names)


def pid_of(pkg):
    o = sh("pidof %s" % pkg).strip()
    return o.split()[0] if o else ""


def launch_taptap():
    sh("monkey -p com.taptap -c android.intent.category.LAUNCHER 1")


if __name__ == "__main__":
    print("serial:", serial())
    print("focus :", focus())
    print("where :", where())
    print("socks :", devtools_socks() or "(无)")
    print("h5pid :", pid_of("com.taptap:h5_game"))
    if "-q" not in sys.argv:
        shot()
        print("shot  : _diag/_now.png")
