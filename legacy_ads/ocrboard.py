"""用 RapidOCR 一次读出整块棋盘的「境界名+数字」标签，按文字框中心归位 4x4。

不再需要模板匹配 / 聚类 / 人工标注，也不受角色浮动动画影响。
"""
import os, sys, time, json, re
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core, dev

DIAG = os.path.join(HERE, "_diag")
NAMES = {"练气": 2, "筑基": 4, "金丹": 8, "元婴": 16, "化神": 32, "炼虚": 64,
         "合体": 128, "大乘": 256, "渡劫": 512, "真仙": 1024, "金仙": 2048,
         "太乙": 4096}

_ENGINE = None


def engine():
    global _ENGINE
    if _ENGINE is None:
        from rapidocr_onnxruntime import RapidOCR
        t = time.time()
        _ENGINE = RapidOCR()
        print("ocr engine loaded %.1fs" % (time.time() - t), flush=True)
    return _ENGINE


def parse(text):
    t = text.replace(" ", "").replace("，", "").strip()
    for n, v in NAMES.items():
        if n in t:
            return v, n
    m = re.search(r"(2|4|8|16|32|64|128|256|512|1024|2048|4096)", t)
    if m:
        v = int(m.group(1))
        return v, {val: k for k, val in NAMES.items()}.get(v, "?")
    return None, None


def ocr_region(img, y0=980, y1=2520, scale=1.0, debug=False):
    """返回 (items, dt)。items = [(text, cx, cy, value, name)]"""
    sub = img[y0:y1, :]
    if scale != 1.0:
        sub = cv2.resize(sub, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    t = time.time()
    res, _ = engine()(sub)
    dt = time.time() - t
    items = []
    if res:
        for box, txt, conf in res:
            pts = np.array(box, dtype=np.float32)
            cx = float(pts[:, 0].mean()) / scale
            cy = float(pts[:, 1].mean()) / scale + y0
            v, nm = parse(txt)
            items.append((txt, cx, cy, v, nm, float(conf)))
    if debug:
        vis = img.copy()
        for txt, cx, cy, v, nm, conf in items:
            cv2.circle(vis, (int(cx), int(cy)), 6, (0, 0, 255), -1)
            cv2.putText(vis, txt[:8], (int(cx), int(cy) - 12),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
        cv2.imwrite(os.path.join(DIAG, "_ocr_vis.png"), vis)
    return items, dt


def board_from_items(items, colcenters=None, rowcenters=None):
    """把标签中心归位到 4x4；行中心由文字 y 自动聚类。"""
    tags = [it for it in items if it[3] is not None]
    if colcenters is None:
        colcenters = [core.X0 + core.CW * (c + 0.5) for c in range(4)]
    if rowcenters is None:
        ys = sorted(it[2] for it in tags)
        # 按 y 做最多 4 组的一维聚类
        groups = []
        for y in ys:
            if groups and y - np.mean(groups[-1]) < 120:
                groups[-1].append(y)
            else:
                groups.append([y])
        rowcenters = [float(np.mean(g)) for g in groups]
    b = [0] * 16
    for txt, cx, cy, v, nm, conf in tags:
        if not rowcenters:
            continue
        ci = int(np.argmin([abs(cx - x) for x in colcenters]))
        ri = int(np.argmin([abs(cy - y) for y in rowcenters]))
        if 0 <= ri < 4 and 0 <= ci < 4:
            b[ri * 4 + ci] = v
    return b, rowcenters


if __name__ == "__main__":
    p = os.path.join(DIAG, "_ocr_full.png")
    dev.shot(p)
    img = cv2.imread(p)
    print("full", img.shape)
    for scale in (1.0, 1.5):
        items, dt = ocr_region(img, scale=scale, debug=(scale == 1.0))
        print("\n== scale %.1f  %.2fs  %d texts ==" % (scale, dt, len(items)))
        for txt, cx, cy, v, nm, conf in items:
            print("   %-14s (%4.0f,%4.0f) v=%-5s %-4s conf=%.2f" %
                  (txt[:14], cx, cy, v, nm, conf))
        b, rows = board_from_items(items)
        print("   rowcenters", [round(x) for x in rows])
        for r in range(4):
            print("   ", b[r * 4:(r + 1) * 4])
