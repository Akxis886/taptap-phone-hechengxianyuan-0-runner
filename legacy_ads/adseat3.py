"""TapTap 激励视频开满施法席位 —— 全自动、自校验、自动重试。

必须一次跑完的原因：ads.js:176 showRewarded() 120s 后 finish(false) 且忽略
后续 onClose。人工拆步看截图必然超时。

已固化的踩坑：
  * TapAD（tapsdk.tapad / Stub_Standard_Portrait_Activity）显示「奖励已获得」
    但游戏侧 onClose.isEnded=false，席位加不上 -> 检测到就早退、重开一次广告。
  * 穿山甲 TTRewardVideoActivity 才是有效广告。
  * 顶部「去体验N秒可立即领奖 | 跳过/关闭」是提示条不是按钮 -> CTA 只取 y>1100。
  * 点「去体验」会先跳中间跳板(com.miui.securitycenter)再落真正落地页
    (com.dragon.read)，必须持续采样并 force-stop 全部第三方包。
  * 关闭序列可能是「退出并领奖」->「残忍离开」二次确认。
  * 回到游戏后必须 OCR 校验「N位施法」真的 +1，否则重试。

用法：
  python adseat3.py --target 3 --tries 6
"""
import os, sys, time, re, argparse
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dev, core, ocrboard

DIAG = os.path.join(HERE, "_diag")
GAME = "com.taptap/com.taptap.h5game.container.H5GameActivity"
PKG = "com.taptap"
AD_MARK = ("rewardactivity", "portraitad", "stub_standard", "rewardvideo", "adactivity")
TAPAD_MARK = ("tapad", "stub_standard")

XY_SEAT = (862, 898)
XY_CONFIRM = (448, 1828)
BROWSE_SEC = 15.5
HARD_CAP = 100.0
CTA_WORDS = ("去体验", "我要加速", "立即领", "立即下载", "继续试玩", "点击屏幕")
CLOSE_WORDS = ("坚持退出", "残忍离开", "退出并领奖", "领取奖励并关闭", "奖励已领取")


def is_ad(f):
    return f.startswith(PKG + "/") and any(k in f.lower() for k in AD_MARK)


def is_tapad(f):
    return any(k in f.lower() for k in TAPAD_MARK)


def texts_of(eng, img, scale=0.62):
    small = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    res, _ = eng(small)
    out = []
    for bx, t, _c in (res or []):
        q = np.array(bx, np.float32)
        out.append((t, float(q[:, 0].mean()) / scale, float(q[:, 1].mean()) / scale))
    return out


def pick(texts, words, exclude=(), ymin=0):
    best = None
    for t, x, y in texts:
        if y < ymin or any(e in t for e in exclude):
            continue
        for i, w in enumerate(words):
            if w in t:
                score = 100 - i * 10
                if best is None or score > best[0]:
                    best = (score, x, y, t)
                break
    return best


def seat_count(eng, g):
    """只 OCR 席位那一行（y 600..740），快。"""
    try:
        img = g.grab()[600:760]
        ts = texts_of(eng, img, scale=1.6)
        for t, x, y in ts:
            m = re.search(r"(\d)\s*位施法", t)
            if m:
                return int(m.group(1))
    except Exception:
        pass
    return None


def dismiss_ad(eng, g, log, maxn=7):
    """关闭任意广告层，处理「关闭/跳过」->「坚持退出/残忍离开」二次确认。

    TapAD 会拦截 BACK，必须点可见按钮；穿山甲试玩页也有「残忍离开」二次确认。
    """
    for n in range(maxn):
        f = dev.focus()
        if f == GAME:
            return True
        # 广告 CTA 跳到第三方 App：直接 force-stop，回到广告层/游戏
        if "/" in f:
            p = f.split("/", 1)[0]
            if p and p != PKG:
                log("  dismiss force-stop %s" % p)
                dev.sh("am force-stop %s" % p)
                time.sleep(1.6)
                continue
        xy, hit = None, ""
        try:
            ts = texts_of(eng, g.grab(), scale=0.62)
            b = pick(ts, ("坚持退出", "残忍离开", "确认退出", "退出并领奖"))
            if b is None:
                b = pick(ts, ("关闭", "跳过"), exclude=("去体验", "立即领"))
            if b:
                xy, hit = (int(b[1]), int(b[2])), b[3]
        except Exception:
            pass
        if xy:
            log("  dismiss%d %s @%s" % (n, hit, xy))
            dev.tap(*xy)
        else:
            dev.key("KEYCODE_BACK")
        time.sleep(1.3)
    log("  dismiss 未回对局: %s" % dev.focus())
    return dev.focus() == GAME


def back_to_game(eng, g, log):
    return dismiss_ad(eng, g, log)


def one(eng, g, idx, log):
    t0 = time.time()
    f = dev.focus()
    if f != GAME:
        log("[%d] 起点非对局，先 BACK 清理" % idx)
        if not back_to_game(eng, g, log):
            return "not-in-game"

    before = seat_count(eng, g)
    dev.tap(*XY_SEAT)
    time.sleep(1.3)
    dev.tap(*XY_CONFIRM)
    log("[%d] 席位按钮+确认 (%.1fs, 开局席位=%s)" % (idx, time.time() - t0, before))

    ad_f = ""
    for _ in range(14):
        time.sleep(0.6)
        f = dev.focus()
        if is_ad(f):
            ad_f = f
            break
    if not ad_f:
        return "no-ad"
    log("[%d] 广告 %s (%.1fs)" % (idx, ad_f.split(".")[-1], time.time() - t0))

    if is_tapad(ad_f):
        log("[%d] TapAD 假奖励，早退重试" % idx)
        back_to_game(eng, g, log)
        return "tapad-retry"

    seen, cta_done, t_browse, close_round = [], False, None, 0
    last_xy, same = None, 0

    while time.time() - t0 < HARD_CAP:
        f = dev.focus()
        if f == GAME:
            after = seat_count(eng, g)
            log("[%d] 回到对局 (%.1fs) 席位 %s -> %s" %
                (idx, time.time() - t0, before, after))
            return "granted" if (after and before and after > before) else "no-seat"

        if "/" in f:
            p = f.split("/", 1)[0]
            if p and p != PKG and p not in seen:
                seen.append(p)
                log("[%d] 落地页 %s" % (idx, p))

        if not cta_done:
            try:
                ts = texts_of(eng, g.grab())
            except Exception:
                time.sleep(0.6)
                continue
            b = pick(ts, CTA_WORDS, exclude=("奖励已领取",), ymin=1100)
            if b:
                log("[%d] CTA %s @(%d,%d)" % (idx, b[3], b[1], b[2]))
                dev.tap(int(b[1]), int(b[2]))
            else:
                log("[%d] 无下部 CTA，点默认 (720,2040)" % idx)
                dev.tap(720, 2040)
            cta_done, t_browse = True, time.time()
            time.sleep(1.0)
            continue

        if t_browse and time.time() - t_browse < BROWSE_SEC:
            time.sleep(0.9)
            continue
        if t_browse:
            t_browse = None
            if seen:
                log("[%d] force-stop %s (%.1fs)" % (idx, seen, time.time() - t0))
                for p in seen:
                    dev.sh("am force-stop %s" % p)
                seen = []
            time.sleep(1.6)
            continue

        close_round += 1
        if close_round > 9:
            break
        try:
            ts = texts_of(eng, g.grab())
        except Exception:
            time.sleep(0.6)
            continue
        b = pick(ts, CLOSE_WORDS, exclude=("去体验", "继续试玩"))
        if b is None:
            b = pick(ts, ("跳过", "关闭"), exclude=("去体验", "立即领"))
        if b and same < 2:
            xy = (int(b[1]), int(b[2]))
            if xy == last_xy:
                same += 1
            else:
                same, last_xy = 0, xy
            log("[%d] 关闭%d %s @%s" % (idx, close_round, b[3], xy))
            dev.tap(*xy)
        else:
            log("[%d] 关闭%d BACK 兜底" % (idx, close_round))
            dev.key("KEYCODE_BACK")
            same, last_xy = 0, None
        time.sleep(1.5)

    log("[%d] 超时 focus=%s (%.1fs)" % (idx, dev.focus(), time.time() - t0))
    back_to_game(eng, g, log)
    return "timeout"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=3)
    ap.add_argument("--tries", type=int, default=6)
    args = ap.parse_args()
    lines = []

    def log(m):
        lines.append(m)
        try:
            print(m, flush=True)
        except UnicodeEncodeError:
            print(m.encode("ascii", "replace").decode(), flush=True)

    eng = ocrboard.engine()
    g = core.Grabber(crop_to_board=False)
    log("engine ready; focus=%s" % dev.focus())
    back_to_game(eng, g, log)
    cur = seat_count(eng, g)
    log("当前席位 = %s / 目标 %d" % (cur, args.target))
    for i in range(1, args.tries + 1):
        if cur and cur >= args.target:
            log("已达标，停止")
            break
        r = one(eng, g, i, log)
        log("[%d] => %s" % (i, r))
        time.sleep(2.0)
        cur = seat_count(eng, g)
        log("   复核席位 = %s" % cur)
    p = os.path.join(DIAG, "_adseat3_end.png")
    dev.shot(p)
    log("最终截图 %s  席位=%s" % (p, cur))
    with open(os.path.join(DIAG, "adseat3.log"), "a", encoding="utf-8") as fp:
        fp.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
