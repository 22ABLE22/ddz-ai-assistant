#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
斗地主中局蒙特卡洛仿真搜索
- 采样未知手牌，用预训练模型把残局打到终局
- 支持 WP / ADP / 加权融合目标
- 支持多进程并行仿真
"""

from __future__ import annotations

import os
import random
import sys
from collections import Counter
from copy import deepcopy

import numpy as np
import torch

# 保证多进程 spawn 时子进程也能找到 DouZero
_HERE = os.path.dirname(os.path.abspath(__file__))
_DZ = os.path.join(_HERE, "DouZero")
if _DZ not in sys.path:
    sys.path.insert(0, _DZ)

from douzero.env.env import get_obs
from douzero.env.game import GameEnv, EnvCard2RealCard
from douzero.dmc.models import model_dict

FULL_DECK = []
for _r in range(3, 15):
    FULL_DECK.extend([_r] * 4)
FULL_DECK.extend([17] * 4)
FULL_DECK.extend([20, 30])

POSITIONS = ("landlord", "landlord_up", "landlord_down")

# 进程池 worker 全局状态（spawn 下每个子进程一份）
_WORKER: dict = {}
# 跨次推荐复用的进程池（避免每次都重建 8 个 worker / 重复加载模型）
_POOL_CACHE: dict = {}


def _get_or_create_pool(ckpt_root: str, model_type: str, douzero_path: str, n_workers: int):
    import multiprocessing as mp

    try:
        mp.freeze_support()
    except Exception:
        pass

    key = (os.path.abspath(ckpt_root), model_type, int(n_workers))
    pool = _POOL_CACHE.get(key)
    if pool is not None:
        return pool, True

    # 配置变化时清理旧池，避免白占内存
    for old_key in list(_POOL_CACHE.keys()):
        if old_key[0] == key[0] and (old_key[1] != key[1] or old_key[2] != key[2]):
            try:
                _POOL_CACHE.pop(old_key).terminate()
            except Exception:
                pass

    ctx = mp.get_context("spawn")
    pool = ctx.Pool(
        processes=int(n_workers),
        initializer=_init_worker,
        initargs=(ckpt_root, model_type, douzero_path),
    )
    _POOL_CACHE[key] = pool
    return pool, False


def shutdown_pools():
    for pool in list(_POOL_CACHE.values()):
        try:
            pool.terminate()
        except Exception:
            pass
    _POOL_CACHE.clear()


class ModelAgent:
    """用单个位置模型做贪心决策。"""

    def __init__(self, model):
        self.model = model

    def act(self, infoset):
        actions = infoset.legal_actions
        if len(actions) == 1:
            return actions[0]
        obs = get_obs(infoset)
        z_batch = torch.from_numpy(obs["z_batch"]).float()
        x_batch = torch.from_numpy(obs["x_batch"]).float()
        with torch.no_grad():
            y_pred = self.model.forward(z_batch, x_batch, return_value=True)["values"]
        y_pred = y_pred.detach().cpu().numpy().flatten()
        return actions[int(np.argmax(y_pred))]


class FixedFirstAgent:
    """首步强制某一着，其后交给 ModelAgent。"""

    def __init__(self, model_agent: ModelAgent, first_action):
        self.model_agent = model_agent
        self.first_action = first_action
        self._used = False

    def act(self, infoset):
        if not self._used:
            self._used = True
            fa_sorted = sorted(self.first_action)
            for a in infoset.legal_actions:
                if sorted(a) == fa_sorted:
                    return a
        return self.model_agent.act(infoset)


def _card_display(cards):
    return " ".join(EnvCard2RealCard[c] for c in cards) if cards else "不出"


def _load_model(position, model_path):
    model = model_dict[position]()
    model_state_dict = model.state_dict()
    try:
        pretrained = torch.load(model_path, map_location="cpu", weights_only=True)
    except Exception:
        pretrained = torch.load(model_path, map_location="cpu")
    pretrained = {k: v for k, v in pretrained.items() if k in model_state_dict}
    model_state_dict.update(pretrained)
    model.load_state_dict(model_state_dict)
    model.eval()
    return model


def load_models_from_ckpt(ckpt_root, model_type: str) -> dict:
    models = {}
    model_dir = os.path.join(ckpt_root, f"douzero_{model_type}")
    for position in POSITIONS:
        models[position] = _load_model(
            position, os.path.join(model_dir, f"{position}.ckpt")
        )
    return models


def snapshot_state(assistant) -> dict:
    """把助手局面压成可 pickle 的 dict，供子进程使用。"""
    return {
        "my_position": assistant.my_position,
        "my_hand_cards": list(assistant.my_hand_cards),
        "three_cards": list(assistant.three_cards),
        "card_play_action_seq": deepcopy(assistant.card_play_action_seq),
        "played_cards": deepcopy(assistant.played_cards),
        "last_move_dict": deepcopy(assistant.last_move_dict),
        "last_move": list(assistant.last_move),
        "last_pid": assistant.last_pid,
        "bomb_num": assistant.bomb_num,
        "acting_player_position": assistant.acting_player_position,
        "num_cards_left": deepcopy(assistant.num_cards_left),
    }


def sample_full_hands_from_state(state: dict, rng: random.Random):
    my_pos = state["my_position"]
    my_hand = list(state["my_hand_cards"])
    played = {p: list(state["played_cards"][p]) for p in POSITIONS}
    all_played = played["landlord"] + played["landlord_up"] + played["landlord_down"]

    remaining = Counter(FULL_DECK)
    for c in my_hand + all_played:
        remaining[c] -= 1

    pool = []
    for card, cnt in remaining.items():
        if cnt < 0:
            raise ValueError(f"牌面计数异常: {card} x{cnt}")
        pool.extend([card] * cnt)

    n_ll = state["num_cards_left"]["landlord"]
    n_up = state["num_cards_left"]["landlord_up"]
    n_down = state["num_cards_left"]["landlord_down"]

    three_left = []
    if my_pos != "landlord":
        for c in state["three_cards"]:
            if c not in all_played and c not in my_hand:
                three_left.append(c)
        for c in three_left:
            if c in pool:
                pool.remove(c)

    rng.shuffle(pool)
    hands = {p: [] for p in POSITIONS}
    idx = 0

    if my_pos == "landlord":
        hands["landlord"] = sorted(my_hand)
        hands["landlord_down"] = sorted(pool[idx:idx + n_down])
        idx += n_down
        hands["landlord_up"] = sorted(pool[idx:idx + n_up])
    elif my_pos == "landlord_up":
        hands["landlord_up"] = sorted(my_hand)
        ll_rest = max(0, n_ll - len(three_left))
        hands["landlord"] = sorted(three_left + pool[idx:idx + ll_rest])
        idx += ll_rest
        hands["landlord_down"] = sorted(pool[idx:idx + n_down])
    else:
        hands["landlord_down"] = sorted(my_hand)
        ll_rest = max(0, n_ll - len(three_left))
        hands["landlord"] = sorted(three_left + pool[idx:idx + ll_rest])
        idx += ll_rest
        hands["landlord_up"] = sorted(pool[idx:idx + n_up])

    three_env = [c for c in state["three_cards"] if c not in played["landlord"]]
    return hands, three_env


def sample_full_hands(assistant, rng: random.Random):
    return sample_full_hands_from_state(snapshot_state(assistant), rng)


def _inject_state(env: GameEnv, state: dict, hands, three_env):
    for p in POSITIONS:
        env.info_sets[p].player_hand_cards = list(hands[p])
    env.three_landlord_cards = list(three_env)
    env.card_play_action_seq = deepcopy(state["card_play_action_seq"])
    env.played_cards = deepcopy(state["played_cards"])
    env.last_move_dict = deepcopy(state["last_move_dict"])
    env.last_move = list(state["last_move"])
    env.last_pid = state["last_pid"]
    env.bomb_num = state["bomb_num"]
    env.game_over = False
    env.player_utility_dict = None
    env.acting_player_position = state["acting_player_position"]
    env.game_infoset = env.get_infoset()


def rollout(models: dict, state: dict, hands, three_env, first_action, agents=None):
    if agents is None:
        agents = {p: ModelAgent(models[p]) for p in POSITIONS}
    players = {}
    for p in POSITIONS:
        if p == state["acting_player_position"]:
            players[p] = FixedFirstAgent(agents[p], first_action)
        else:
            players[p] = agents[p]

    env = GameEnv(players)
    _inject_state(env, state, hands, three_env)

    if env.game_over:
        return _utility_for_me(state["my_position"], env)
    env.step()

    guard = 0
    while not env.game_over and guard < 200:
        env.step()
        guard += 1

    return _utility_for_me(state["my_position"], env)


def _utility_for_me(my_pos, env: GameEnv):
    if not env.game_over or env.player_utility_dict is None:
        return False, 0.0
    if my_pos == "landlord":
        win = env.player_utility_dict["landlord"] > 0
        return win, float(env.player_utility_dict["landlord"])
    win = env.player_utility_dict["farmer"] > 0
    return win, float(env.player_utility_dict["farmer"])


def normalize_util(util: float, my_pos: str) -> float:
    """把 utility 映射到约 [0,1]，便于与胜率加权。"""
    if my_pos == "landlord":
        return max(0.0, min(1.0, (float(util) + 2.0) / 4.0))
    return max(0.0, min(1.0, (float(util) + 1.0) / 2.0))


def _score_vector(model, infoset):
    obs = get_obs(infoset)
    z_batch = torch.from_numpy(obs["z_batch"]).float()
    x_batch = torch.from_numpy(obs["x_batch"]).float()
    with torch.no_grad():
        y_pred = model.forward(z_batch, x_batch, return_value=True)["values"]
    return y_pred.detach().cpu().numpy().flatten()


def _minmax_norm(arr: np.ndarray) -> np.ndarray:
    lo, hi = float(np.min(arr)), float(np.max(arr))
    if hi - lo < 1e-9:
        return np.zeros_like(arr)
    return (arr - lo) / (hi - lo)


def model_rank_actions(
    models: dict,
    assistant,
    infoset,
    top_k: int,
    models_adp: dict | None = None,
    wp_weight: float = 1.0,
):
    """
    给合法着排序取 top_k。
    若同时提供 WP 与 ADP 模型，则按 wp_weight 融合两套打分后再排序。
    """
    actions = infoset.legal_actions
    if len(actions) <= top_k:
        return list(actions)

    y_wp = _score_vector(models[assistant.my_position], infoset)
    if models_adp is not None and 0.0 < wp_weight < 1.0:
        y_adp = _score_vector(models_adp[assistant.my_position], infoset)
        fused = wp_weight * _minmax_norm(y_wp) + (1.0 - wp_weight) * _minmax_norm(y_adp)
        order = np.argsort(fused)[::-1]
    else:
        order = np.argsort(y_wp)[::-1]
    return [actions[i] for i in order[:top_k]]


# ---------- 多进程 worker ----------

def _init_worker(ckpt_root: str, model_type: str, douzero_path: str):
    torch.set_num_threads(1)
    if douzero_path and douzero_path not in sys.path:
        sys.path.insert(0, douzero_path)
    models = load_models_from_ckpt(ckpt_root, model_type)
    _WORKER["models"] = models
    _WORKER["agents"] = {p: ModelAgent(models[p]) for p in POSITIONS}


def _run_sim_batch(payload):
    """
    payload = (state, candidates, base_seed)
    返回 list[dict]: 每个候选的 wins/utils
    """
    state, candidates, base_seed = payload
    models = _WORKER.get("models")
    agents = _WORKER.get("agents")
    if models is None:
        return []

    rng = random.Random(base_seed)
    hands, three_env = sample_full_hands_from_state(state, rng)
    out = []
    for a in candidates:
        try:
            win, util = rollout(models, state, hands, three_env, a, agents=agents)
        except Exception:
            win, util = False, 0.0
        out.append({"win": bool(win), "util": float(util)})
    return out


def _resolve_objective_wp_weight(objective: str, wp_weight: float) -> float:
    obj = (objective or "FUSE").upper()
    if obj == "WP":
        return 1.0
    if obj == "ADP":
        return 0.0
    return float(wp_weight)


def search_best_move(
    models: dict,
    assistant,
    infoset,
    num_simulations: int = 40,
    top_k: int = 6,
    objective: str = "FUSE",
    wp_weight: float = 0.7,
    models_adp: dict | None = None,
    ckpt_root: str | None = None,
    num_workers: int = 1,
    seed: int | None = None,
    verbose: bool = True,
):
    """
    仿真搜索最佳着。

    objective:
      - WP:   最大化胜率
      - ADP:  最大化平均分差 utility
      - FUSE: score = wp_weight * 胜率 + (1-wp_weight) * 归一化分差
    wp_weight: FUSE 时 WP 权重，1.0 等价纯 WP，0.0 等价纯 ADP

    num_workers>1 时用多进程并行（Windows spawn），需要 ckpt_root。
    models 为残局策略模型（当前 model_type）；models_adp 仅用于候选融合排序。
    """
    actions = infoset.legal_actions
    if not actions:
        return [], {"error": "no legal actions"}

    w = _resolve_objective_wp_weight(objective, wp_weight)
    obj_label = objective.upper() if objective else "FUSE"

    if len(actions) == 1:
        return actions[0], {
            "action": actions[0],
            "win_rate": None,
            "avg_util": None,
            "fused": None,
            "sims": 0,
            "wp_weight": w,
            "candidates": [],
        }

    candidates = model_rank_actions(
        models, assistant, infoset, top_k,
        models_adp=models_adp, wp_weight=w,
    )
    model_order = {tuple(a): i for i, a in enumerate(candidates)}
    state = snapshot_state(assistant)
    my_pos = state["my_position"]
    keys = [tuple(a) for a in candidates]
    stats = {k: {"wins": 0, "utils": [], "action": a} for k, a in zip(keys, candidates)}

    if verbose:
        mode = f"workers={num_workers}" if num_workers and num_workers > 1 else "single"
        print(f"[搜索] 候选 {len(candidates)} 个，仿真 {num_simulations} 次，"
              f"目标 {obj_label} (WP权重={w:.2f})，{mode}")

    if num_workers and num_workers > 1 and ckpt_root:
        # 多进程：复用进程池，按仿真次数切批
        import atexit

        rollout_type = assistant.model_type
        douzero_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "DouZero")
        n_workers = max(1, min(int(num_workers), int(num_simulations)))
        try:
            pool, reused = _get_or_create_pool(ckpt_root, rollout_type, douzero_path, n_workers)
            atexit.register(shutdown_pools)
            if verbose and reused:
                print("[搜索] 复用已有进程池")
            payloads = [
                (state, candidates, (seed if seed is not None else 0) + i * 9973)
                for i in range(num_simulations)
            ]
            done = 0
            for batch_result in pool.imap_unordered(_run_sim_batch, payloads):
                for a, res in zip(candidates, batch_result or []):
                    key = tuple(a)
                    stats[key]["wins"] += int(res["win"])
                    stats[key]["utils"].append(res["util"])
                done += 1
                if verbose and done % max(1, num_simulations // 4) == 0:
                    print(f"[搜索] 进度 {done}/{num_simulations}")
        except Exception as e:
            if verbose:
                print(f"[搜索] 多进程失败，回退单进程: {e}")
            shutdown_pools()
            stats = {k: {"wins": 0, "utils": [], "action": a}
                     for k, a in zip(keys, candidates)}
            num_workers = 0

    if not (num_workers and num_workers > 1 and ckpt_root):
        rng = random.Random(seed)
        agents = {p: ModelAgent(models[p]) for p in POSITIONS}
        for i in range(num_simulations):
            try:
                hands, three_env = sample_full_hands_from_state(state, rng)
            except Exception as e:
                if verbose:
                    print(f"[搜索] 采样失败: {e}")
                continue
            for a in candidates:
                try:
                    win, util = rollout(models, state, hands, three_env, a, agents=agents)
                except Exception:
                    continue
                key = tuple(a)
                stats[key]["wins"] += int(win)
                stats[key]["utils"].append(util)
            if verbose and (i + 1) % max(1, num_simulations // 4) == 0:
                print(f"[搜索] 进度 {i + 1}/{num_simulations}")

    report_rows = []
    for key, st in stats.items():
        n = len(st["utils"])
        wr = st["wins"] / n if n else 0.0
        au = float(np.mean(st["utils"])) if n else 0.0
        norm = float(np.mean([normalize_util(u, my_pos) for u in st["utils"]])) if n else 0.0
        fused = w * wr + (1.0 - w) * norm
        report_rows.append({
            "action": st["action"],
            "display": _card_display(st["action"]),
            "win_rate": wr,
            "avg_util": au,
            "fused": fused,
            "n": n,
            "model_rank": model_order.get(key, 999),
        })

    if not report_rows:
        best = ModelAgent(models[my_pos]).act(infoset)
        return best, {"action": best, "win_rate": None, "avg_util": None,
                      "fused": None, "sims": 0, "wp_weight": w, "candidates": []}

    # 主目标：融合分；次级：胜率、分差、模型排名
    report_rows.sort(key=lambda r: (-r["fused"], -r["win_rate"], -r["avg_util"], r["model_rank"]))

    best = report_rows[0]["action"]
    return best, {
        "action": best,
        "win_rate": report_rows[0]["win_rate"],
        "avg_util": report_rows[0]["avg_util"],
        "fused": report_rows[0]["fused"],
        "sims": num_simulations,
        "wp_weight": w,
        "candidates": report_rows,
    }
