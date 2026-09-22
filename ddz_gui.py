#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
斗地主AI出牌助手 - GUI版本
暗色牌桌主题，按钮化操作
"""

import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import subprocess
import threading
import queue
import os
import sys
import re


# ---------- 主题色 ----------
C = {
    "bg":        "#0B1F17",
    "panel":     "#123528",
    "panel2":    "#0F2C21",
    "surface":   "#163D2E",
    "border":    "#1F5A42",
    "gold":      "#E4C158",
    "gold_dim":  "#A8923D",
    "cream":     "#F3EDE0",
    "ink":       "#E8F0EA",
    "muted":     "#7FA693",
    "red":       "#E25C5C",
    "blue":      "#6EB5FF",
    "green":     "#3DBF7C",
    "orange":    "#E39A4A",
    "card_bg":   "#F7F2E7",
    "card_ink":  "#1A1A1A",
}

ROLE_MAP = {"1": "landlord_up", "2": "landlord", "3": "landlord_down"}
ROLE_NAME = {
    "landlord": "地主",
    "landlord_up": "地主上家",
    "landlord_down": "地主下家",
}
RANK_ORDER = ["3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "X", "D"]
# 每种牌总张数（记牌器）
RANK_MAX = {r: 4 for r in RANK_ORDER}
RANK_MAX["X"] = 1
RANK_MAX["D"] = 1


def parse_hand_display(s: str):
    """把手牌字符串拆成可显示的牌面列表。"""
    if not s:
        return []
    t = s.replace(",", " ").replace("，", " ").upper()
    # 先抽出 10，再处理单张
    tokens = re.findall(r"10|[2-9JQKAXD]|1", t)
    cards = []
    for tok in tokens:
        if tok == "1":
            cards.append("10")
        else:
            cards.append(tok)
    def key(c):
        try:
            return RANK_ORDER.index(c)
        except ValueError:
            return 99
    return sorted(cards, key=key)


class CardChip(tk.Canvas):
    """单张牌的小卡片控件。"""

    W, H = 44, 62

    def __init__(self, master, rank, **kw):
        super().__init__(master, width=self.W, height=self.H,
                         highlightthickness=0, bg=C["panel"], **kw)
        self.rank = rank
        self.draw()

    def draw(self):
        self.delete("all")
        w, h = self.W, self.H
        # 阴影
        self.create_rectangle(3, 3, w - 1, h - 1, fill="#0A1812", outline="")
        # 牌面
        self.create_rectangle(1, 1, w - 3, h - 3, fill=C["card_bg"],
                              outline=C["gold_dim"], width=1)
        ink = C["card_ink"]
        if self.rank in ("2", "D"):
            ink = "#C0392B"
        elif self.rank == "X":
            ink = "#2471A3"
        elif self.rank in ("J", "Q", "K", "A"):
            ink = "#1F4E3A"

        label = self.rank
        font_size = 18 if len(label) == 1 else 14
        self.create_text(w // 2 - 1, h // 2 - 1, text=label,
                         fill=ink, font=("Segoe UI", font_size, "bold"))


class HandStrip(tk.Frame):
    """手牌条：若干 CardChip。"""

    def __init__(self, master, **kw):
        super().__init__(master, bg=C["panel"], **kw)
        self._chips = []

    def set_cards(self, cards):
        for w in self._chips:
            w.destroy()
        self._chips = []
        for c in cards:
            chip = CardChip(self, c)
            chip.pack(side=tk.LEFT, padx=2, pady=2)
            self._chips.append(chip)


class CardCounter(tk.Frame):
    """记牌器：显示尚未出现的牌张。"""

    def __init__(self, master, **kw):
        super().__init__(master, bg=C["panel"], **kw)
        self._tiles = {}  # rank -> label widget
        self._build()
        self.update_counts({r: RANK_MAX[r] for r in RANK_ORDER})

    def _build(self):
        for rank in RANK_ORDER:
            tile = tk.Frame(self, bg=C["panel2"], highlightthickness=2,
                            highlightbackground=C["panel2"])
            tile.pack(side=tk.LEFT, padx=2, pady=2)
            top = tk.Label(tile, text=rank, bg=C["panel2"], fg=C["ink"],
                           font=("Segoe UI", 9, "bold"), width=3)
            top.pack(padx=4, pady=(3, 0))
            num = tk.Label(tile, text=str(RANK_MAX[rank]), bg=C["panel2"],
                           fg=C["ink"], font=("Consolas", 11, "bold"), width=3)
            num.pack(padx=4, pady=(0, 3))
            self._tiles[rank] = (tile, top, num)

    def update_counts(self, unseen: dict):
        """unseen: rank -> 剩余未出现张数。"""
        # 王炸提示：小王大王都还没出现
        jokers_hot = (unseen.get("X", 0) >= 1 and unseen.get("D", 0) >= 1)
        for rank, (tile, top, num) in self._tiles.items():
            left = int(unseen.get(rank, 0))
            num.config(text=str(left))
            if left <= 0:
                # 灰显
                tile.config(bg="#1A2420", highlightbackground="#2A3530")
                top.config(bg="#1A2420", fg=C["muted"])
                num.config(bg="#1A2420", fg=C["muted"])
            elif rank in ("X", "D"):
                # 王：仅当大小王都未见（可能王炸）才红框；只缺一张不标红
                if jokers_hot:
                    tile.config(bg=C["panel2"], highlightbackground=C["red"])
                    top.config(bg=C["panel2"], fg=C["ink"])
                    num.config(bg=C["panel2"], fg=C["red"])
                else:
                    tile.config(bg=C["panel2"], highlightbackground=C["border"])
                    top.config(bg=C["panel2"], fg=C["ink"])
                    num.config(bg=C["panel2"], fg=C["gold"])
            elif left >= RANK_MAX[rank]:
                # 红框：该点数仍满张，可能四炸
                tile.config(bg=C["panel2"], highlightbackground=C["red"])
                top.config(bg=C["panel2"], fg=C["ink"])
                num.config(bg=C["panel2"], fg=C["red"])
            else:
                tile.config(bg=C["panel2"], highlightbackground=C["border"])
                top.config(bg=C["panel2"], fg=C["ink"])
                num.config(bg=C["panel2"], fg=C["gold"])


class ModernButton(tk.Button):
    def __init__(self, master, text, command=None, kind="ghost", **kw):
        styles = {
            "primary": dict(bg=C["gold"], fg="#1A1408", activebackground="#F0D070",
                            activeforeground="#1A1408"),
            "danger":  dict(bg="#8B3A3A", fg=C["cream"], activebackground="#A64B4B",
                            activeforeground=C["cream"]),
            "solid":   dict(bg=C["surface"], fg=C["ink"], activebackground=C["border"],
                            activeforeground=C["ink"]),
            "ghost":   dict(bg=C["panel2"], fg=C["muted"], activebackground=C["surface"],
                            activeforeground=C["ink"]),
        }
        base = dict(
            relief=tk.FLAT, bd=0, padx=10, pady=6,
            font=("Microsoft YaHei UI", 10),
            cursor="hand2", activebackground=C["surface"],
            highlightthickness=0,
        )
        base.update(styles.get(kind, styles["ghost"]))
        base.update(kw)
        super().__init__(master, text=text, command=command, **base)


class DDZGui:
    def __init__(self, root):
        self.root = root
        self.root.title("斗地主 AI 出牌助手")
        self.root.geometry("1080x780")
        self.root.minsize(960, 700)
        self.root.configure(bg=C["bg"])

        self.process = None
        self.output_queue = queue.Queue()
        self.my_role = tk.StringVar(value="2")  # 默认地主
        self.model_type = tk.StringVar(value="WP")  # WP胜率 / ADP分差
        self._last_recommend = ""
        self._started = False
        self._cmd_ready = False      # 后端是否已进入命令循环
        self._pending_cmds = []      # 暂存 search/model，避免打乱 setup 输入

        self._setup_style()
        self.create_widgets()
        self.start_process()
        self.read_output()

        self.root.protocol("WM_DELETE_WINDOW", self.quit_program)

    # ---------- 样式 ----------
    def _setup_style(self):
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure(".", background=C["bg"], foreground=C["ink"],
                        fieldbackground=C["panel2"], bordercolor=C["border"],
                        troughcolor=C["panel2"], font=("Microsoft YaHei UI", 10))
        style.configure("TFrame", background=C["bg"])
        style.configure("Panel.TFrame", background=C["panel"])
        style.configure("TLabel", background=C["bg"], foreground=C["ink"])
        style.configure("Panel.TLabel", background=C["panel"], foreground=C["ink"])
        style.configure("Muted.TLabel", background=C["panel"], foreground=C["muted"])
        style.configure("Title.TLabel", background=C["bg"], foreground=C["gold"],
                        font=("Microsoft YaHei UI", 16, "bold"))
        style.configure("Sub.TLabel", background=C["panel"], foreground=C["muted"],
                        font=("Microsoft YaHei UI", 9))
        style.configure("TEntry", fieldbackground=C["panel2"], foreground=C["ink"],
                        insertcolor=C["ink"])
        style.configure("TRadiobutton", background=C["panel"], foreground=C["ink"],
                        selectcolor=C["surface"])
        style.map("TRadiobutton",
                  background=[("active", C["panel"])],
                  foreground=[("active", C["gold"])])
        style.configure("TLabelframe", background=C["panel"], foreground=C["muted"])
        style.configure("TLabelframe.Label", background=C["panel"], foreground=C["muted"],
                        font=("Microsoft YaHei UI", 9))

    # ---------- 界面 ----------
    def create_widgets(self):
        pad = {"padx": 12, "pady": 6}

        # 顶栏
        header = tk.Frame(self.root, bg=C["bg"], height=56)
        header.pack(fill=tk.X, padx=12, pady=(12, 4))
        header.pack_propagate(False)

        tk.Label(header, text="♠ 斗地主 AI 出牌助手", bg=C["bg"], fg=C["gold"],
                 font=("Microsoft YaHei UI", 18, "bold")).pack(side=tk.LEFT)
        tk.Label(header, text="DouZero", bg=C["bg"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(side=tk.LEFT, padx=(12, 0), pady=(8, 0))

        self.status_pill = tk.Label(
            header, text="● 就绪", bg=C["panel"], fg=C["green"],
            font=("Microsoft YaHei UI", 10, "bold"), padx=12, pady=4)
        self.status_pill.pack(side=tk.RIGHT)

        # 身份 / 开局
        setup = tk.Frame(self.root, bg=C["panel"], highlightbackground=C["border"],
                         highlightthickness=1)
        setup.pack(fill=tk.X, **pad)

        tk.Label(setup, text="我的身份", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).grid(row=0, column=0, padx=(12, 4), pady=10, sticky="w")

        for i, (val, name) in enumerate([("1", "地主上家"), ("2", "地主"), ("3", "地主下家")]):
            tk.Radiobutton(
                setup, text=name, variable=self.my_role, value=val,
                bg=C["panel"], fg=C["ink"], selectcolor=C["surface"],
                activebackground=C["panel"], activeforeground=C["gold"],
                font=("Microsoft YaHei UI", 10),
                command=self._on_role_change,
            ).grid(row=0, column=1 + i, padx=6, pady=10, sticky="w")

        tk.Label(setup, text="手牌", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).grid(row=0, column=4, padx=(20, 4), pady=10, sticky="w")
        self.hand_entry = tk.Entry(
            setup, width=28, bg=C["panel2"], fg=C["ink"], insertbackground=C["ink"],
            relief=tk.FLAT, font=("Consolas", 10))
        self.hand_entry.insert(0, "3 4 5 6 7 8 9 10 J Q K A 2 X D")
        self.hand_entry.grid(row=0, column=5, padx=4, pady=10, sticky="w")

        tk.Label(setup, text="底牌", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).grid(row=0, column=6, padx=(12, 4), pady=10, sticky="w")
        self.three_entry = tk.Entry(
            setup, width=10, bg=C["panel2"], fg=C["ink"], insertbackground=C["ink"],
            relief=tk.FLAT, font=("Consolas", 10))
        self.three_entry.insert(0, "3 4 5")
        self.three_entry.grid(row=0, column=7, padx=4, pady=10, sticky="w")

        self.btn_start = ModernButton(setup, "开始对局", command=self.start_new_game, kind="primary")
        self.btn_start.grid(row=0, column=8, padx=(12, 12), pady=10)

        setup.columnconfigure(5, weight=1)

        # 手牌可视化
        hand_frame = tk.Frame(self.root, bg=C["panel"], highlightbackground=C["border"],
                              highlightthickness=1)
        hand_frame.pack(fill=tk.X, **pad)

        tk.Label(hand_frame, text="当前手牌", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(anchor="w", padx=12, pady=(8, 0))
        self.hand_strip = HandStrip(hand_frame)
        self.hand_strip.pack(fill=tk.X, padx=8, pady=(4, 6))
        self.hand_strip.set_cards(parse_hand_display(self.hand_entry.get()))

        tk.Label(hand_frame, text="记牌器（未出现，红框=可能成炸）", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(anchor="w", padx=12, pady=(2, 0))
        self.card_counter = CardCounter(hand_frame)
        self.card_counter.pack(fill=tk.X, padx=8, pady=(4, 10))

        self.hand_entry.bind("<KeyRelease>", self._sync_hand_strip)

        # 中部：推荐 + 日志
        mid = tk.Frame(self.root, bg=C["bg"])
        mid.pack(fill=tk.BOTH, expand=True, **pad)

        # 左：推荐
        rec = tk.Frame(mid, bg=C["panel"], highlightbackground=C["border"],
                       highlightthickness=1)
        rec.pack(side=tk.LEFT, fill=tk.BOTH, expand=False, padx=(0, 6))
        rec.configure(width=320)
        rec.pack_propagate(False)

        tk.Label(rec, text="AI 推荐出牌", bg=C["panel"], fg=C["gold"],
                 font=("Microsoft YaHei UI", 12, "bold")).pack(anchor="w", padx=14, pady=(12, 2))

        # 模型切换 WP / ADP
        model_row = tk.Frame(rec, bg=C["panel"])
        model_row.pack(fill=tk.X, padx=12, pady=(0, 4))
        tk.Label(model_row, text="模型", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(side=tk.LEFT)
        for val, label in (("WP", "WP 胜率"), ("ADP", "ADP 分差")):
            tk.Radiobutton(
                model_row, text=label, variable=self.model_type, value=val,
                bg=C["panel"], fg=C["ink"], selectcolor=C["surface"],
                activebackground=C["panel"], activeforeground=C["gold"],
                font=("Microsoft YaHei UI", 9),
                command=self._on_model_change,
            ).pack(side=tk.LEFT, padx=6)

        # 仿真搜索
        search_row = tk.Frame(rec, bg=C["panel"])
        search_row.pack(fill=tk.X, padx=12, pady=(0, 2))
        self.search_on = tk.BooleanVar(value=True)
        tk.Checkbutton(
            search_row, text="深度搜索", variable=self.search_on,
            bg=C["panel"], fg=C["gold"], selectcolor=C["surface"],
            activebackground=C["panel"], activeforeground=C["gold"],
            font=("Microsoft YaHei UI", 9),
            command=self._on_search_toggle,
        ).pack(side=tk.LEFT)
        tk.Label(search_row, text="仿真", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(side=tk.LEFT, padx=(10, 2))
        self.search_sims_var = tk.StringVar(value="200")
        sims_spin = tk.Spinbox(
            search_row, from_=8, to=300, increment=4, width=5,
            textvariable=self.search_sims_var,
            bg=C["panel2"], fg=C["ink"], buttonbackground=C["surface"],
            font=("Consolas", 9))
        sims_spin.pack(side=tk.LEFT)
        sims_spin.bind("<FocusOut>", lambda e: self._on_search_toggle())
        sims_spin.bind("<Return>", lambda e: self._on_search_toggle())

        search_row2 = tk.Frame(rec, bg=C["panel"])
        search_row2.pack(fill=tk.X, padx=12, pady=(0, 4))
        tk.Label(search_row2, text="WP权重", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(side=tk.LEFT)
        self.search_wp_w = tk.StringVar(value="0.70")
        w_spin = tk.Spinbox(
            search_row2, from_=0.0, to=1.0, increment=0.05, width=5,
            textvariable=self.search_wp_w,
            bg=C["panel2"], fg=C["ink"], buttonbackground=C["surface"],
            font=("Consolas", 9))
        w_spin.pack(side=tk.LEFT, padx=(4, 10))
        w_spin.bind("<FocusOut>", lambda e: self._on_search_toggle())
        w_spin.bind("<Return>", lambda e: self._on_search_toggle())
        tk.Label(search_row2, text="并行", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(side=tk.LEFT)
        self.search_workers_var = tk.StringVar(value="8")
        wkr_spin = tk.Spinbox(
            search_row2, from_=0, to=16, increment=1, width=4,
            textvariable=self.search_workers_var,
            bg=C["panel2"], fg=C["ink"], buttonbackground=C["surface"],
            font=("Consolas", 9))
        wkr_spin.pack(side=tk.LEFT, padx=4)
        wkr_spin.bind("<FocusOut>", lambda e: self._on_search_toggle())
        wkr_spin.bind("<Return>", lambda e: self._on_search_toggle())

        self.rec_cards = HandStrip(rec)
        self.rec_cards.pack(anchor="w", padx=10, pady=4)

        self.rec_text = tk.Label(
            rec, text="等待推荐…", bg=C["panel2"], fg=C["ink"],
            font=("Consolas", 12), anchor="w", padx=12, pady=10)
        self.rec_text.pack(fill=tk.X, padx=12, pady=4)

        btn_row = tk.Frame(rec, bg=C["panel"])
        btn_row.pack(fill=tk.X, padx=12, pady=6)
        ModernButton(btn_row, "一键采用", command=self.adopt_recommend, kind="primary"
                     ).pack(side=tk.LEFT, padx=(0, 6))
        ModernButton(btn_row, "不出", command=lambda: self._send_and_clear_play("pass"),
                     kind="solid").pack(side=tk.LEFT, padx=0)
        ModernButton(btn_row, "刷新推荐", command=lambda: self.send_cmd("recommend"),
                     kind="ghost").pack(side=tk.LEFT, padx=6)

        tk.Label(rec, text="其他候选", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(anchor="w", padx=14, pady=(10, 2))
        self.cand_list = tk.Listbox(
            rec, bg=C["panel2"], fg=C["muted"], relief=tk.FLAT,
            font=("Consolas", 10), height=6, highlightthickness=0,
            selectbackground=C["surface"], selectforeground=C["gold"])
        self.cand_list.pack(fill=tk.BOTH, expand=True, padx=12, pady=(0, 12))

        # 右：日志
        logf = tk.Frame(mid, bg=C["panel"], highlightbackground=C["border"],
                        highlightthickness=1)
        logf.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(6, 0))

        tk.Label(logf, text="程序输出", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).pack(anchor="w", padx=12, pady=(10, 4))

        self.output_text = scrolledtext.ScrolledText(
            logf, wrap=tk.WORD, height=16,
            bg=C["panel2"], fg=C["ink"], insertbackground=C["ink"],
            relief=tk.FLAT, font=("Consolas", 10),
            padx=10, pady=8, highlightthickness=0)
        self.output_text.pack(fill=tk.BOTH, expand=True, padx=8, pady=(0, 8))
        self.output_text.config(state=tk.DISABLED)
        self._config_log_tags()

        # 记录他人出牌
        rec_frame = tk.Frame(self.root, bg=C["panel"], highlightbackground=C["border"],
                             highlightthickness=1)
        rec_frame.pack(fill=tk.X, **pad)

        tk.Label(rec_frame, text="记录出牌（空=不出）", bg=C["panel"], fg=C["muted"],
                 font=("Microsoft YaHei UI", 9)).grid(row=0, column=0, columnspan=7,
                                                      sticky="w", padx=12, pady=(8, 2))

        self.entries = {}
        for col, (key, label) in enumerate([
            ("landlord", "地主"),
            ("landlord_down", "地主下家"),
            ("landlord_up", "地主上家"),
        ]):
            base = col * 3
            tk.Label(rec_frame, text=label, bg=C["panel"], fg=C["ink"],
                     font=("Microsoft YaHei UI", 10)).grid(
                row=1, column=base, padx=(12 if col == 0 else 8, 4), pady=8, sticky="w")
            e = tk.Entry(rec_frame, width=16, bg=C["panel2"], fg=C["ink"],
                         insertbackground=C["ink"], relief=tk.FLAT,
                         font=("Consolas", 10))
            e.grid(row=1, column=base + 1, padx=2, pady=8, sticky="we")
            e.bind("<Return>", lambda ev, k=key: self.record_player(k))
            ModernButton(rec_frame, "记录", command=lambda k=key: self.record_player(k),
                         kind="solid").grid(row=1, column=base + 2, padx=(2, 8), pady=8)
            self.entries[key] = e

        rec_frame.columnconfigure(1, weight=1)
        rec_frame.columnconfigure(4, weight=1)
        rec_frame.columnconfigure(7, weight=1)

        # 我的出牌 + 其它命令
        playf = tk.Frame(self.root, bg=C["panel"], highlightbackground=C["border"],
                         highlightthickness=1)
        playf.pack(fill=tk.X, padx=12, pady=(0, 4))

        tk.Label(playf, text="我的出牌", bg=C["panel"], fg=C["ink"],
                 font=("Microsoft YaHei UI", 10)).pack(side=tk.LEFT, padx=(12, 6), pady=10)
        self.play_entry = tk.Entry(playf, width=24, bg=C["panel2"], fg=C["ink"],
                                   insertbackground=C["ink"], relief=tk.FLAT,
                                   font=("Consolas", 11))
        self.play_entry.pack(side=tk.LEFT, padx=4, pady=10)
        self.play_entry.bind("<Return>", lambda e: self.play_cards())
        ModernButton(playf, "出牌", command=self.play_cards, kind="primary"
                     ).pack(side=tk.LEFT, padx=4, pady=10)
        ModernButton(playf, "不出", command=lambda: self._send_and_clear_play("pass"),
                     kind="solid").pack(side=tk.LEFT, padx=4, pady=10)

        ModernButton(playf, "新对局", command=self.start_new_game,
                     kind="solid").pack(side=tk.LEFT, padx=(20, 4), pady=10)
        ModernButton(playf, "查看状态", command=lambda: self.send_cmd("status"),
                     kind="ghost").pack(side=tk.LEFT, padx=4, pady=10)
        ModernButton(playf, "帮助", command=lambda: self.send_cmd("help"),
                     kind="ghost").pack(side=tk.LEFT, padx=4, pady=10)
        ModernButton(playf, "退出", command=self.quit_program, kind="danger"
                     ).pack(side=tk.RIGHT, padx=12, pady=10)

        # 底部状态栏
        self.footer = tk.Label(
            self.root, text="程序启动中…", bg=C["panel2"], fg=C["muted"],
            font=("Microsoft YaHei UI", 9), anchor="w", padx=12)
        self.footer.pack(fill=tk.X, side=tk.BOTTOM, ipady=4)

    def _config_log_tags(self):
        t = self.output_text
        t.tag_configure("gold", foreground=C["gold"])
        t.tag_configure("red", foreground=C["red"])
        t.tag_configure("green", foreground=C["green"])
        t.tag_configure("blue", foreground=C["blue"])
        t.tag_configure("muted", foreground=C["muted"])
        t.tag_configure("orange", foreground=C["orange"])

    def _on_role_change(self):
        role = ROLE_MAP.get(self.my_role.get(), "")
        if role == "landlord":
            self.footer.config(text="地主：手牌请含3张底牌共20张；也可只填17张并填写底牌，程序会自动合并")
        else:
            self.footer.config(text=f"当前身份：{ROLE_NAME.get(role, role)} · 手牌通常17张")

    def _on_model_change(self):
        mtype = self.model_type.get()
        tip = "WP：更关注胜率" if mtype == "WP" else "ADP：更关注分差"
        if self.search_on.get():
            tip += " · 深度搜索中"
        cmd = f"model {mtype}"
        if not getattr(self, "_cmd_ready", False):
            self._pending_cmds.append(cmd)
        else:
            self.send_cmd(cmd)
        self.footer.config(text=f"推荐模型已切换为 {mtype} · {tip}")

    def _on_search_toggle(self):
        on = bool(self.search_on.get())
        try:
            n = max(8, int(self.search_sims_var.get()))
        except Exception:
            n = 200
            self.search_sims_var.set("200")
        try:
            w = max(0.0, min(1.0, float(self.search_wp_w.get())))
        except Exception:
            w = 0.70
            self.search_wp_w.set("0.70")
        try:
            workers = max(0, int(self.search_workers_var.get()))
        except Exception:
            workers = 8
            self.search_workers_var.set("8")
        cmd = f"search {'on' if on else 'off'} n={n} w={w:.2f} workers={workers} obj=FUSE"
        # setup_game 的 input() 尚未走完时不能下发，否则会被当成身份/手牌
        if not getattr(self, "_cmd_ready", False):
            self._pending_cmds.append(cmd)
            if on:
                extra = f" · 并行 x{workers}" if workers > 1 else ""
                self.footer.config(
                    text=f"深度搜索开启 · 仿真 {n} 次 · WP权重 {w:.2f}{extra}")
            else:
                self.footer.config(text="深度搜索已关闭 · 单步模型推荐")
            return
        self.send_cmd(cmd)
        if on:
            extra = f" · 并行 x{workers}" if workers > 1 else ""
            self.footer.config(
                text=f"深度搜索开启 · 仿真 {n} 次 · WP权重 {w:.2f}{extra}")
        else:
            self.footer.config(text="深度搜索已关闭 · 单步模型推荐")

    def _flush_pending_cmds(self):
        pending = list(getattr(self, "_pending_cmds", []))
        self._pending_cmds = []
        for cmd in pending:
            self.send_cmd(cmd)

    def _sync_hand_strip(self, _event=None):
        self.hand_strip.set_cards(parse_hand_display(self.hand_entry.get()))

    # ---------- 启动子进程 ----------
    def start_process(self):
        try:
            script_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                       "ddz_assistant.py")
            env = os.environ.copy()
            env["PYTHONIOENCODING"] = "utf-8"
            env["PYTHONUTF8"] = "1"

            self.process = subprocess.Popen(
                [sys.executable, "-X", "utf8", "-u", script_path],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0,
                env=env,
                cwd=os.path.dirname(os.path.abspath(__file__)),
            )

            self.reader_thread = threading.Thread(target=self._reader_loop, daemon=True)
            self.reader_thread.start()

            self.append_output("程序已启动，模型加载中…\n", tag="muted")
            self._set_status("加载中…", C["orange"])
            self.footer.config(text="子进程运行中 · 请先选择身份并输入手牌后点「开始对局」")
        except Exception as e:
            self.append_output(f"启动程序失败: {e}\n", tag="red")
            self._set_status("启动失败", C["red"])

    def _reader_loop(self):
        try:
            while self.process and self.process.poll() is None:
                raw = self.process.stdout.readline()
                if not raw:
                    break
                text = raw.decode("utf-8", errors="replace")
                self.output_queue.put(text)
        except Exception:
            pass
        finally:
            self.output_queue.put(None)
            self.output_queue.put("<<PROCESS_ENDED>>\n")

    # ---------- 命令通道 ----------
    def send_cmd(self, cmd):
        if self.process and self.process.poll() is None:
            try:
                self.process.stdin.write((cmd + "\n").encode("utf-8"))
                self.process.stdin.flush()
                self.append_output(f"> {cmd}\n", tag="muted")
            except Exception as e:
                self.append_output(f"发送命令失败: {e}\n", tag="red")
        else:
            self.append_output("程序未运行\n", tag="red")
            self._set_status("未运行", C["red"])

    def send_lines(self, lines):
        if not (self.process and self.process.poll() is None):
            self.append_output("程序未运行\n", tag="red")
            return
        try:
            data = "".join(line + "\n" for line in lines).encode("utf-8")
            self.process.stdin.write(data)
            self.process.stdin.flush()
            for line in lines:
                self.append_output(f"> {line}\n", tag="muted")
        except Exception as e:
            self.append_output(f"发送命令失败: {e}\n", tag="red")

    def start_new_game(self):
        role = self.my_role.get()
        hand = self.hand_entry.get().strip()
        three = self.three_entry.get().strip()
        if not hand:
            messagebox.showwarning("提示", "请先输入手牌")
            return
        lines = [role, hand, three]
        # reset 若已有对局则 CLI 会先要求 reset；首次启动时 setup_game 已在等待
        # 首次：直接发 身份/手牌/底牌；之后：先 reset
        if getattr(self, "_started", False):
            self.send_lines(["reset"] + lines)
        else:
            self.send_lines(lines)
            self._started = True
        # 确保后端模型与界面一致（setup 行之后入队，待命令循环处理）
        self.send_cmd(f"model {self.model_type.get()}")
        self._on_search_toggle()
        self._set_status("对局中", C["green"])
        self.btn_start.config(text="新对局")
        self.footer.config(text="新对局已提交 · 等待开局后按顺序记录出牌")
        self.rec_text.config(text="等待推荐…")
        self.cand_list.delete(0, tk.END)
        self.rec_cards.set_cards([])
        self._last_recommend = ""
        # 记牌器先恢复满张，待后端记牌器行刷新
        self.card_counter.update_counts({r: RANK_MAX[r] for r in RANK_ORDER})

    def record_player(self, role):
        e = self.entries[role]
        cards = e.get().strip()
        if cards:
            self.send_cmd(f"record {role} {cards}")
            e.delete(0, tk.END)
        else:
            self.send_cmd(f"record {role} pass")

    def play_cards(self):
        cards = self.play_entry.get().strip()
        # 允许在出牌框直接敲 CLI 命令（如 reset/quit）
        low = cards.lower()
        if low == "reset":
            # reset 后端会立刻 setup_game()，必须带上身份/手牌/底牌
            self.play_entry.delete(0, tk.END)
            self.start_new_game()
            return
        if low in ("quit", "exit", "status", "help", "recommend", "model", "search"):
            self.send_cmd(low)
            self.play_entry.delete(0, tk.END)
            return
        if cards:
            self.send_cmd(f"play {cards}")
            self.play_entry.delete(0, tk.END)
        else:
            self.send_cmd("play pass")

    def _send_and_clear_play(self, cards):
        self.send_cmd(f"play {cards}" if cards else "play pass")
        self.play_entry.delete(0, tk.END)

    def adopt_recommend(self):
        text = getattr(self, "_last_recommend", "") or ""
        if not text:
            # 兼容：从显示区剥离分数后缀
            raw = self.rec_text.cget("text") or ""
            text = re.sub(r"\s*\[[^\]]*\]\s*$", "", raw).strip()
        if not text or text.startswith("等待") or text.startswith("现在"):
            self.append_output("暂无推荐，请先点「刷新推荐」\n", tag="orange")
            return
        if text.strip() in ("不出", "pass"):
            self.play_entry.delete(0, tk.END)
            self.send_cmd("play pass")
        else:
            self.play_entry.delete(0, tk.END)
            self.play_entry.insert(0, text.strip())
            self.send_cmd(f"play {text.strip()}")

    # ---------- 输出 ----------
    def read_output(self):
        while not self.output_queue.empty():
            text = self.output_queue.get()
            if text is None:
                continue
            if text == "<<PROCESS_ENDED>>\n":
                self._set_status("已退出", C["red"])
                self.footer.config(text="子进程已退出")
                self.append_output("子进程已退出\n", tag="red")
                continue
            self._handle_output_line(text)

        self.root.after(80, self.read_output)

    def _parse_counter_line(self, text):
        """解析 '记牌器: 3:2 4:0 ...' 并刷新记牌器。"""
        body = re.split(r"[:：]", text, maxsplit=1)[-1]
        # 默认 0，仅用行内数值覆盖；缺失点数视为已见完，而不是满张
        counts = {r: 0 for r in RANK_ORDER}
        seen_any = False
        for tok in body.replace("，", " ").replace(",", " ").split():
            parts = re.split(r"[:：]", tok)
            if len(parts) == 2 and parts[0] in counts:
                try:
                    counts[parts[0]] = max(0, min(RANK_MAX[parts[0]], int(parts[1])))
                    seen_any = True
                except ValueError:
                    pass
        if not seen_any:
            return
        self.card_counter.update_counts(counts)

    def _handle_output_line(self, text):
        tag = None
        if "推荐出牌:" in text or "推荐出牌：" in text:
            tag = "gold"
            m = re.search(r"推荐出牌[:：]\s*(.*)", text)
            if m:
                rec = m.group(1).strip()
                # 去掉尾部 [WP 胜率≈..] / [ADP 分差≈..]
                rec_core = re.sub(r"\s*\[[^\]]*\]\s*$", "", rec).strip()
                score_m = re.search(r"\[([^\]]+)\]", rec)
                score_txt = score_m.group(1) if score_m else ""
                show = rec_core if rec_core else "不出"
                if score_txt:
                    self.rec_text.config(text=f"{show}    {score_txt}")
                else:
                    self.rec_text.config(text=show)
                cards = parse_hand_display("" if show == "不出" else show)
                self.rec_cards.set_cards(cards)
                self.cand_list.delete(0, tk.END)
                # 一键采用使用纯牌面
                self._last_recommend = show
        elif "其他候选出牌" in text:
            tag = "blue"
        elif re.match(r"\s+\d+\.\s+", text):
            # 候选行
            tag = "muted"
            self.cand_list.insert(tk.END, text.strip())
            if self.cand_list.size() > 12:
                self.cand_list.delete(0)
        elif "错误" in text or "失败" in text or "无效" in text:
            tag = "red"
        elif "游戏结束" in text or "获胜" in text:
            tag = "green"
            self._set_status("已结束", C["gold"])
            self.btn_start.config(text="新对局")
            self.footer.config(
                text="本局已结束 · 点顶部「新对局」或底部「新对局」，填好手牌/底牌后即可重开")
        elif "游戏开始" in text or "模型加载完成" in text:
            tag = "orange"
            if "模型加载完成" in text:
                self._set_status("就绪", C["green"])
            # 后端已进入命令循环后，才允许下发 search/model
            if "游戏开始" in text:
                self._cmd_ready = True
                self._flush_pending_cmds()
                self.btn_start.config(text="新对局")
        elif "推荐出牌" in text:
            tag = "gold"
        elif "记牌器" in text:
            tag = "muted"
            self._parse_counter_line(text)
        elif text.startswith(">"):
            tag = "muted"

        self.append_output(text, tag=tag)

        # 更新手牌显示
        if "剩余手牌:" in text:
            m = re.search(r"剩余手牌[:：]\s*(.+?)(?:\s*\(|$)", text)
            if m:
                self.hand_entry.delete(0, tk.END)
                self.hand_entry.insert(0, m.group(1).strip())
                self._sync_hand_strip()
        if "我的手牌:" in text:
            m = re.search(r"我的手牌[:：]\s*(.+?)(?:\s*\(|$)", text)
            if m:
                self.hand_entry.delete(0, tk.END)
                self.hand_entry.insert(0, m.group(1).strip())
                self._sync_hand_strip()

    def append_output(self, text, tag=None):
        self.output_text.config(state=tk.NORMAL)
        if tag:
            self.output_text.insert(tk.END, text, tag)
        else:
            self.output_text.insert(tk.END, text)
        self.output_text.see(tk.END)
        self.output_text.config(state=tk.DISABLED)

        # 防止无限膨胀
        lines = int(self.output_text.index("end-1c").split(".")[0])
        if lines > 2000:
            self.output_text.config(state=tk.NORMAL)
            self.output_text.delete("1.0", "500.0")
            self.output_text.config(state=tk.DISABLED)

    def _set_status(self, text, color):
        self.status_pill.config(text=f"● {text}", fg=color, bg=C["panel"])

    def quit_program(self):
        if self.process and self.process.poll() is None:
            try:
                self.process.stdin.write(b"quit\n")
                self.process.stdin.flush()
                self.process.wait(timeout=2)
            except Exception:
                try:
                    self.process.terminate()
                    self.process.wait(timeout=2)
                except Exception:
                    self.process.kill()
        self.root.destroy()


def main():
    try:
        from ctypes import windll
        windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    root = tk.Tk()
    DDZGui(root)
    root.mainloop()


if __name__ == "__main__":
    main()
