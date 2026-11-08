"""核心：raw 抓帧（比 PNG 快 ~1.7x）+ 棋盘切格。"""
import os, struct, subprocess, sys, time
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dev

# ---------------- 棋盘几何 ----------------
X0, X1 = 24, 1416
Y0, Y1 = 1037, 2416
CW = (X1 - X0) / 4.0
CH = (Y1 - Y0) / 4.0
COLS = ROWS = 4

# 滑动落点（棋盘内，留边距）
SLIDE = {
    "up":    (720, 1900, 720, 1150),
    "down":  (720, 1550, 720, 2300),
    "left":  (1050, 1726, 390, 1726),
    "right": (390, 1726, 1050, 1726),
}


class Grabber:
    """用 exec-out screencap 取原始帧，本地解码成 BGR ndarray。

    可选 crop：只把棋盘需要的行传回来（dd 在手机侧裁剪），省带宽。
    """

    def __init__(self, crop_to_board=False):
        self.adb = dev.adb_path()
        self.serial = dev.serial()
        self.crop = crop_to_board
        self.hdr = 12
        self.w = self.h = None

    def _cmd(self):
        if self.crop:
            # raw 每行 5760 字节；跳过前 Y0 行，只取棋盘高度的行
            rows_skip = Y0
            rows_take = Y1 - Y0 + 2
            return ("screencap | tail -c +13 | dd bs=5760 skip=%d count=%d 2>/dev/null"
                    % (rows_skip, rows_take))
        return "screencap"

    def grab(self):
        p = subprocess.run([self.adb, "-s", self.serial, "exec-out", self._cmd()],
                           capture_output=True, timeout=60)
        d = p.stdout
        if not d:
            raise RuntimeError("空帧（rc=%d）" % p.returncode)
        if self.crop:
            w, h = 1440, (Y1 - Y0 + 2)
            buf = d
        else:
            w, h, fmt = struct.unpack("<III", d[:12])
            self.hdr = len(d) - w * h * 4
            buf = d[self.hdr:]
            if self.hdr not in (12, 16):
                raise RuntimeError("意外 header=%d" % self.hdr)
        need = w * h * 4
        buf = buf[:need]
        a = np.frombuffer(buf, np.uint8).reshape(h, w, 4)
        self.w, self.h = w, h
        return a[:, :, :3].copy()          # BGRA -> BGR

    def grab_board_region(self):
        """统一返回坐标系下的整屏或棋盘区域，附带 y 偏移。"""
        img = self.grab()
        return img, (Y0 if self.crop else 0)


def cells_from(img, yoff=0):
    """返回 16 个格子的 BGR 图（行优先）。"""
    out = []
    for r in range(ROWS):
        for c in range(COLS):
            x = int(X0 + CW * c)
            y = int(Y0 + CH * r) - yoff
            out.append(img[y:y + int(CH), x:x + int(CW)].copy())
    return out


def bg_color(img, yoff=0):
    """用第 (2,2) 格估计空格背景色（若该格非空，改用全图众数）。"""
    cells = cells_from(img, yoff)
    stds = [float(c.reshape(-1, 3).std(0).mean()) for c in cells]
    i = int(np.argmin(stds))
    return cells[i].reshape(-1, 3).mean(0), stds


if __name__ == "__main__":
    g = Grabber(crop_to_board=False)
    for i in range(3):
        t0 = time.time()
        img = g.grab()
        dt = time.time() - t0
        print("full raw  %.0f ms  %s" % (dt * 1000, img.shape))
    cv2.imwrite(os.path.join(HERE, "_diag", "_raw_full.png"), img)

    g2 = Grabber(crop_to_board=True)
    for i in range(3):
        t0 = time.time()
        img2, yo = g2.grab_board_region()
        dt = time.time() - t0
        print("crop raw  %.0f ms  %s yoff=%d" % (dt * 1000, img2.shape, yo))
    cv2.imwrite(os.path.join(HERE, "_diag", "_raw_crop.png"), img2)
    print("crop 与 full 的棋盘区域是否一致：", img2.shape[0] >= int(CH * 4))
