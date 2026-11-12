# -*- coding: utf-8 -*-
"""
ddz_capture.py — 欢乐斗地主截图自动识别（模板匹配版）

职责：
  1. 定位游戏窗口（腾讯欢乐斗地主，Chrome_WidgetWin_0）
  2. PrintWindow 抓图（支持被遮挡窗口，不依赖前台、不模拟操作）
  3. 分割手牌 rank 字形
  4. 模板匹配识别（比 OCR 稳，斗地主 rank 只有 15 种，字形固定）

只读：不注入进程、不模拟点击、不修改游戏内存。
"""
from __future__ import annotations

import ctypes
import os
from ctypes import wintypes
from dataclasses import dataclass, field

import cv2
import numpy as np
import win32gui
import win32ui

_user32 = ctypes.windll.user32
_user32.PrintWindow.argtypes = [wintypes.HWND, wintypes.HDC, wintypes.UINT]
_user32.PrintWindow.restype = wintypes.BOOL

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(BASE_DIR, ".ddz_cache")
TEMPLATE_PATH = os.path.join(BASE_DIR, "rank_templates.npz")

# 斗地主全部 15 种 rank（X=小王, D=大王）
ALL_RANKS = ["3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "X", "D"]

# 字形级标签：'10' 在游戏里是 '1' + '0' 两个连通域
GLYPH_LABELS = ["3", "4", "5", "6", "7", "8", "9", "1", "0", "J", "Q", "K", "A", "2", "X", "D"]

# 必须靠模板匹配识别的字形。X / D 是竖排 "JOKER" 文字，
# 由 detect_jokers() 按墨迹剖面单独判别，不参与模板匹配。
REQUIRED_GLYPHS = [g for g in GLYPH_LABELS if g not in ("X", "D")]

NORM_SIZE = (32, 32)


@dataclass
class Card:
    rank: str
    x: int = 0
    y: int = 0
    w: int = 0
    h: int = 0
    conf: float = 1.0

    def __str__(self) -> str:
        return self.rank


@dataclass
class Capture:
    hand: list[Card] = field(default_factory=list)
    bottom: list[Card] = field(default_factory=list)
    image: np.ndarray | None = None
    error: str = ""
    debug: dict = field(default_factory=dict)

    @property
    def hand_str(self) -> str:
        return " ".join(c.rank for c in self.hand)

    @property
    def bottom_str(self) -> str:
        return " ".join(c.rank for c in self.bottom)


# ---------------------------------------------------------------- 窗口

GAME_TITLES = ("腾讯欢乐斗地主", "欢乐斗地主", "斗地主")
# 标题里出现这些词的一律排除（浏览器标签、本工具窗口等）
EXCLUDE_WORDS = ("DeepSeek", "Harness", "—", "Git", "助手", "Akari", "OP.GG")


def find_game_window() -> int:
    """
    返回游戏窗口 hwnd；找不到返回 0。
    严格匹配：标题必须精确等于 GAME_TITLES 之一，且排除浏览器标签/本工具窗口。
    """
    found = []

    def cb(hwnd, _):
        if not win32gui.IsWindowVisible(hwnd):
            return
        title = win32gui.GetWindowText(hwnd).strip()
        if not title:
            return
        if title not in GAME_TITLES:
            return
        if any(w in title for w in EXCLUDE_WORDS):
            return
        cls = win32gui.GetClassName(hwnd)
        if "Chrome_WidgetWin" not in cls:
            return
        l, t, r, b = win32gui.GetWindowRect(hwnd)
        w, h = r - l, b - t
        if w < 400 or h < 300:
            return
        # Chrome_WidgetWin_0 是游戏客户端，_1 是浏览器标签，优先 _0
        bonus = 10_000_000 if cls.endswith("_0") else 0
        found.append((bonus + w * h, hwnd))

    win32gui.EnumWindows(cb, None)
    if not found:
        return 0
    found.sort(reverse=True)
    return found[0][1]


def grab_window(hwnd: int) -> np.ndarray | None:
    """PrintWindow(flag=2) 抓图，返回 BGR 数组。支持被遮挡窗口。"""
    l, t, r, b = win32gui.GetWindowRect(hwnd)
    w, h = r - l, b - t
    if w <= 0 or h <= 0:
        return None
    hwnd_dc = win32gui.GetWindowDC(hwnd)
    mfc_dc = win32ui.CreateDCFromHandle(hwnd_dc)
    save_dc = mfc_dc.CreateCompatibleDC()
    bmp = win32ui.CreateBitmap()
    try:
        bmp.CreateCompatibleBitmap(mfc_dc, w, h)
        save_dc.SelectObject(bmp)
        if not _user32.PrintWindow(hwnd, save_dc.GetSafeHdc(), 2):
            return None
        arr = np.frombuffer(bmp.GetBitmapBits(True), dtype=np.uint8)
        arr = arr.reshape(h, w, 4)
        return cv2.cvtColor(arr, cv2.COLOR_BGRA2BGR)
    finally:
        win32gui.DeleteObject(bmp.GetHandle())
        save_dc.DeleteDC()
        mfc_dc.DeleteDC()
        win32gui.ReleaseDC(hwnd, hwnd_dc)


# ---------------------------------------------------------------- 分割

def _rank_band(img: np.ndarray) -> tuple[np.ndarray, int, int]:
    """
    取底部手牌区。
    返回 (band, 牌排顶边 y, 牌高 card_h)。

    牌高用 rank 字形高度反推：字形高 ≈ 牌高 * 0.28（跨窗口尺寸实测稳定）。
    """
    H, W = img.shape[:2]
    hand = img[int(H * 0.66):, :]
    hh, hw = hand.shape[:2]
    gray = cv2.cvtColor(hand, cv2.COLOR_BGR2GRAY)
    white = (gray > 165).astype(np.uint8)
    row_frac = white.sum(axis=1) / max(hw, 1)
    top = None
    for y in range(hh):
        if row_frac[y] > 0.45:
            top = y
            break
    if top is None:
        top = int(hh * 0.4)

    # 从 top 往下 60px 内找 rank 字形，用中位高度反推牌高
    card_h = 0
    y0 = top + 2
    y1 = min(top + 60, hh)
    if y1 > y0:
        strip = hand[y0:y1, :]
        sg = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
        b = strip[:, :, 0].astype(int)
        g = strip[:, :, 1].astype(int)
        r = strip[:, :, 2].astype(int)
        dark = (sg < 120).astype(np.uint8)
        red = ((r > 120) & (g < 110) & (b < 110)).astype(np.uint8)
        mask = ((dark | red) * 255).astype(np.uint8)
        n, _l, stats, _c = cv2.connectedComponentsWithStats(mask, 8)
        hs = [stats[i][3] for i in range(1, n)
              if 12 <= stats[i][3] <= 70 and stats[i][2] >= 5]
        if hs:
            card_h = int(float(np.median(hs)) / 0.28)

    if card_h < 60:
        card_h = int(hh * 0.72)
    card_h = min(card_h, hh - top)
    return hand, top, card_h


def segment_hand(img: np.ndarray) -> tuple[list[tuple[int, int, int, int]], np.ndarray]:
    """返回 (字形框列表, rank 条带图)。框按 x 升序。比例自适应。"""
    hand, top, card_h = _rank_band(img)
    hh, hw = hand.shape[:2]
    # rank 带：牌顶向下 3%~40% 牌高（避开边缘、覆盖数字/字母）
    off0 = max(2, int(card_h * 0.03))
    off1 = max(off0 + 12, int(card_h * 0.40))
    sy0, sy1 = top + off0, min(top + off1, hh)
    strip = hand[sy0:sy1, :]
    if strip.size == 0:
        return [], strip

    sg = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    b = strip[:, :, 0].astype(int)
    g = strip[:, :, 1].astype(int)
    r = strip[:, :, 2].astype(int)

    dark = (sg < 120).astype(np.uint8)
    red = ((r > 120) & (g < 110) & (b < 110)).astype(np.uint8)
    mask = ((dark | red) * 255).astype(np.uint8)

    # 尺寸阈值也按牌高比例
    h_min = max(10, int(card_h * 0.28))
    h_max = max(h_min + 6, int(card_h * 0.75))
    w_min = max(4, int(card_h * 0.08))

    n, _l, stats, _c = cv2.connectedComponentsWithStats(mask, 8)
    boxes = []
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        if bh < h_min or bw < w_min or area < 40 or bh > h_max:
            continue
        # 过滤贴图像左边缘的伪字形（窗口裁切/桌面背景残留）
        if x <= 2 and bh >= (sy1 - sy0) - 2:
            continue
        boxes.append((int(x), int(y), int(bw), int(bh)))
    boxes.sort(key=lambda t: t[0])
    return boxes, strip


def _norm_glyph(strip: np.ndarray, box: tuple[int, int, int, int]) -> np.ndarray:
    x, y, w, h = box
    crop = strip[y:y + h, x:x + w]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop
    norm = cv2.resize(gray, NORM_SIZE, interpolation=cv2.INTER_AREA)
    norm = cv2.normalize(norm, None, 0, 255, cv2.NORM_MINMAX)
    return norm.astype(np.float32)


# ---------------------------------------------------------------- 模板

_templates: dict[str, np.ndarray] | None = None


def load_templates(path: str = TEMPLATE_PATH) -> dict[str, np.ndarray]:
    global _templates
    if _templates is not None:
        return _templates
    if not os.path.exists(path):
        _templates = {}
        return _templates
    data = np.load(path, allow_pickle=False)
    _templates = {k: data[k].astype(np.float32) for k in data.files}
    return _templates


def missing_glyphs(tpl: dict[str, np.ndarray] | None = None) -> list[str]:
    """返回模板库里缺失的字形标签（按 GLYPH_LABELS 顺序）。

    之前用「样本数 >= 12」判断模板是否可用是错的：
    同一字形的多个样本会凑满 12，但 3/7/K 一个都没有也能通过。
    缺字形时最近邻会静默匹配到别的字形，输出看似正常的错误答案。
    """
    if tpl is None:
        tpl = load_templates()
    have = {k.split("#", 1)[0] for k in tpl}
    return [g for g in REQUIRED_GLYPHS if g not in have]


def templates_ready() -> bool:
    """模板库必须覆盖全部 16 个字形（14 个 rank，10 拆成 1 和 0）才可用。"""
    tpl = load_templates()
    return len(tpl) > 0 and not missing_glyphs(tpl)


def build_templates_from_labeled(frame_path: str, labels: list[str],
                                 out_path: str = TEMPLATE_PATH) -> dict[str, list[np.ndarray]]:
    """
    从一张已知手牌的帧构建模板库。labels 按 x 顺序，字形级（10 拆成 1 和 0）。
    保留全部样本（不做均值），匹配时用最近邻。
    """
    img = cv2.imread(frame_path)
    if img is None:
        raise FileNotFoundError(frame_path)
    boxes, strip = segment_hand(img)
    if len(boxes) != len(labels):
        raise ValueError(f"字形数 {len(boxes)} != 标注数 {len(labels)}")
    buckets: dict[str, list[np.ndarray]] = {}
    for box, lab in zip(boxes, labels):
        buckets.setdefault(lab, []).append(_norm_glyph(strip, box))
    # 每类最多保留 8 个样本，存成 tpl0, tpl1... 形式
    flat: dict[str, np.ndarray] = {}
    for lab, arrs in buckets.items():
        for k, a in enumerate(arrs[:8]):
            flat[f"{lab}#{k}"] = a
    np.savez(out_path, **flat)
    global _templates
    _templates = flat
    return buckets


def _match_glyph(norm: np.ndarray, tpl: dict[str, np.ndarray]) -> tuple[str, float]:
    """
    最近邻匹配。tpl 的 key 形如 'J#0'，取 '#' 前的标签。
    返回 (最佳标签, MSE)。
    """
    best, best_score = "", float("inf")
    for key, t in tpl.items():
        if t.shape != norm.shape:
            continue
        s = float(np.mean((t - norm) ** 2))
        if s < best_score:
            best, best_score = key.split("#", 1)[0], s
    return best, best_score


# ---------------------------------------------------------------- 识别

def _boxes_to_ranks(glyphs: list[tuple[str, float]], max_mse: float = 2200.0) -> list[str]:
    """
    字形标签序列 -> 牌 rank 序列。
    规则：'1' 紧跟 '0' 合并为 '10'；低置信度丢弃。
    """
    ranks: list[str] = []
    i = 0
    while i < len(glyphs):
        lab, mse = glyphs[i]
        if not lab or mse > max_mse:
            i += 1
            continue
        if lab == "1" and i + 1 < len(glyphs):
            nxt, nmse = glyphs[i + 1]
            if nxt == "0" and nmse <= max_mse:
                ranks.append("10")
                i += 2
                continue
        ranks.append(lab)
        i += 1
    return ranks


def detect_jokers(img: np.ndarray, boxes: list[tuple[int, int, int, int]]) -> list[tuple[int, str]]:
    """
    检测手牌中的 JOKER（竖排 "JOKER" 五个字母占满整张牌）。

    返回 [(x_center, rank)]，rank 为 'D'(大王,红) 或 'X'(小王,黑)。

    判别原理（实测最可靠）：
      普通牌：左侧窄带内，rank 字形在上、花色符号在下，两者之间有 15+ 行空白。
      JOKER ：竖排 JOKER 从牌顶一直排到牌底，左带内每一行都有墨迹（无长空白段）。
    因此「最长连续空白行数」是最强特征，配合墨迹行占比。
    """
    hand, top, card_h = _rank_band(img)
    hh, hw = hand.shape[:2]
    if not boxes:
        return []

    gray = cv2.cvtColor(hand, cv2.COLOR_BGR2GRAY)
    b = hand[:, :, 0].astype(int)
    g = hand[:, :, 1].astype(int)
    r = hand[:, :, 2].astype(int)
    mask = (((gray < 120) | ((r > 120) & (g < 110) & (b < 110)))).astype(np.uint8)

    # 估算牌宽：用 rank 字形框的间距
    xs = sorted(bx[0] for bx in boxes)
    gaps = [xs[i + 1] - xs[i] for i in range(len(xs) - 1)]
    gaps = [gp for gp in gaps if gp > 15]
    card_w = int(np.median(gaps)) if gaps else int(card_h * 0.5)
    card_w = max(card_w, 20)

    region = mask[top:, :]
    rh = region.shape[0]
    if rh < 40:
        return []

    out: list[tuple[int, str]] = []
    for (bx, by, bw, bh) in boxes:
        # 采样窗口以字形中心为基准，避免窗口左边缘裁切导致偏移到牌外
        cx = bx + bw // 2
        x0 = max(0, cx - int(card_w * 0.22))
        x1 = min(hw, cx + int(card_w * 0.28))
        if x1 - x0 < 6:
            continue
        col = region[:, x0:x1]
        rows = col.any(axis=1)
        if not rows.any():
            continue
        n_ink = int(rows.sum())
        frac = n_ink / rh
        # 最长连续空白段（首个墨迹行之后）
        first = int(np.argmax(rows))
        run = longest = 0
        for y in range(first, len(rows)):
            if not rows[y]:
                run += 1
                if run > longest:
                    longest = run
            else:
                run = 0
        # JOKER：几乎每行都有墨迹，且没有 8 行以上的空白段。
        # 普通牌 rank 与花色之间必有 15+ 行空白，因此 longest 是决定性特征。
        if not (frac > 0.72 and longest <= 8):
            continue
        # 判红黑：直接数红/黑像素（比平均色稳，不受背景少量污染影响）
        sub = hand[top:, x0:x1]
        B = sub[:, :, 0].astype(int)
        G = sub[:, :, 1].astype(int)
        R = sub[:, :, 2].astype(int)
        red_px = int(((R > 120) & (G < 110) & (B < 110)).sum())
        blk_px = int(((R < 110) & (G < 110) & (B < 110)).sum())
        is_red = red_px > blk_px
        # 只 append 一次。此前用两个不同坐标各 append 一次（cx 与 bx+card_w/2），
        # 靠后面的 x 距离去重兜底；两坐标差值可能超过 card_w*0.5 而漏去重，
        # 导致同一张 JOKER 被算成两张。
        out.append((int(cx), "D" if is_red else "X"))

    # 去重
    out.sort(key=lambda t: t[0])
    dedup: list[tuple[int, str]] = []
    for xc, rk in out:
        if dedup and abs(xc - dedup[-1][0]) < card_w * 0.5:
            continue
        dedup.append((xc, rk))
    return dedup


def recognize_hand(img: np.ndarray) -> tuple[list[str], list[tuple], np.ndarray]:
    """识别手牌 rank 序列，返回 (ranks, boxes, strip)。含 JOKER。"""
    boxes, strip = segment_hand(img)
    tpl = load_templates()
    if not tpl:
        return [], boxes, strip

    # 普通牌字形
    glyphs: list[tuple[str, float]] = []
    for box in boxes:
        norm = _norm_glyph(strip, box)
        lab, mse = _match_glyph(norm, tpl)
        glyphs.append((lab, mse))

    ranks_with_x: list[tuple[int, str]] = []
    i = 0
    while i < len(glyphs):
        lab, mse = glyphs[i]
        box = boxes[i] if i < len(boxes) else (0, 0, 0, 0)
        xc = box[0] + box[2] // 2
        if not lab or mse > 2200.0:
            i += 1
            continue
        if lab == "1" and i + 1 < len(glyphs):
            nxt, nmse = glyphs[i + 1]
            if nxt == "0" and nmse <= 2200.0:
                nb = boxes[i + 1] if i + 1 < len(boxes) else (0, 0, 0, 0)
                ranks_with_x.append((xc, "10"))
                i += 2
                continue
        ranks_with_x.append((xc, lab))
        i += 1

    # JOKER
    for xc, rk in detect_jokers(img, boxes):
        ranks_with_x.append((xc, rk))

    ranks_with_x.sort(key=lambda t: t[0])
    return [r for _x, r in ranks_with_x], boxes, strip


# ---------------------------------------------------------------- 底牌

def segment_bottom(img: np.ndarray) -> list[tuple[int, int, int, int]]:
    """右上角 3 张底牌区域。"""
    H, W = img.shape[:2]
    roi = img[int(H * 0.06):int(H * 0.16), int(W * 0.55):int(W * 0.78)]
    if roi.size == 0:
        return []
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    white = cv2.inRange(hsv, (0, 0, 150), (180, 70, 255))
    white = cv2.morphologyEx(white, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    cnts, _ = cv2.findContours(white, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    out = []
    for c in cnts:
        x, y, bw, bh = cv2.boundingRect(c)
        if bh > roi.shape[0] * 0.5 and bw > 20:
            out.append((int(x), int(y), int(bw), int(bh)))
    out.sort(key=lambda b: b[0])
    return out


# ---------------------------------------------------------------- 对外

def capture() -> Capture:
    """抓一帧并识别手牌。"""
    tpl = load_templates()
    if not tpl:
        return Capture(error=f"模板库缺失：{TEMPLATE_PATH}（需先运行 build_templates）")
    miss = missing_glyphs(tpl)
    if miss:
        return Capture(error=(
            f"模板库不完整，缺字形 {miss}。\n"
            f"缺失字形会被最近邻静默匹配成别的牌，结果不可信。\n"
            f"请补建模板：先用一张已知手牌的截图运行 build_templates_from_labeled()。"))
    hwnd = find_game_window()
    if not hwnd:
        return Capture(error="未找到斗地主窗口（请先打开腾讯欢乐斗地主）")
    img = grab_window(hwnd)
    if img is None:
        return Capture(error="窗口截图失败（PrintWindow 返回 0）")

    ranks, boxes, strip = recognize_hand(img)
    hand = [Card(rank=r) for r in ranks]
    err = "" if hand else "未识别到手牌（牌局未开始或手牌区不可见）"
    return Capture(hand=hand, image=img, error=err,
                   debug={"boxes": boxes, "strip": strip, "hwnd": hwnd})


if __name__ == "__main__":
    cap = capture()
    print("error :", cap.error or "(none)")
    print("hand  :", cap.hand_str, f"({len(cap.hand)} 张)")
    if cap.image is not None:
        os.makedirs(CACHE_DIR, exist_ok=True)
        out = os.path.join(CACHE_DIR, "last_capture.png")
        cv2.imwrite(out, cap.image)
        print("saved :", out)
        strip = cap.debug.get("strip")
        if strip is not None and strip.size:
            sp = os.path.join(CACHE_DIR, "last_strip.png")
            cv2.imwrite(sp, cv2.resize(strip, None, fx=3, fy=3,
                                       interpolation=cv2.INTER_CUBIC))
            print("strip :", sp)
