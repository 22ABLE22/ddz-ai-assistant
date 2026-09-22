#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
斗地主AI出牌助手 - 命令行版本
基于DouZero预训练模型
"""

import sys
import os
import torch
import numpy as np
from collections import Counter

# 添加DouZero到Python路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'DouZero'))

from douzero.env.game import GameEnv, RealCard2EnvCard, EnvCard2RealCard, bombs
from douzero.env.env import get_obs, _cards2array, _get_one_hot_bomb, _action_seq_list2array, _process_action_seq
from douzero.dmc.models import model_dict


MODEL_TYPES = ('WP', 'ADP')  # WP=胜率, ADP=平均分差


class DDZAssistant:
    """斗地主AI助手"""

    def __init__(self, ckpt_root):
        """
        初始化助手
        ckpt_root: checkpoints 根目录（其下应有 douzero_WP / douzero_ADP）
        """
        self.ckpt_root = ckpt_root
        self.models = {}  # models[模型类型][位置]
        self.model_type = 'WP'
        self.my_position = None

        # 仿真搜索（与 GUI 默认一致）
        self.search_enabled = True
        self.search_sims = 200
        self.search_top_k = 6
        self.search_objective = 'FUSE'  # WP | ADP | FUSE
        self.search_wp_weight = 0.7     # FUSE 时 WP 权重
        self.search_workers = 8         # 0=单进程; >1 多进程

        # 游戏状态
        self.my_hand_cards = []  # 我的手牌
        self.three_cards = []  # 三张底牌
        self.card_play_action_seq = []  # 出牌历史
        self.played_cards = {'landlord': [], 'landlord_up': [], 'landlord_down': []}  # 各位置已出的牌
        self.last_move_dict = {'landlord': [], 'landlord_up': [], 'landlord_down': []}  # 各位置最后一次出牌
        self.last_move = []  # 最后一次有效出牌
        self.last_pid = 'landlord'  # 最后一次出牌的位置
        self.bomb_num = 0  # 炸弹数
        self.acting_player_position = 'landlord'  # 当前出牌位置
        self.game_over = False  # 游戏是否结束

        # 各位置剩余手牌数
        self.num_cards_left = {'landlord': 20, 'landlord_up': 17, 'landlord_down': 17}

        # 记牌器：已出现（见过）的牌
        self.seen_cards = Counter()

        # 加载 WP / ADP 两套模型
        print("正在加载AI模型 (WP + ADP)...")
        for mtype in MODEL_TYPES:
            self.models[mtype] = {}
            model_dir = os.path.join(ckpt_root, f'douzero_{mtype}')
            if not os.path.isdir(model_dir):
                raise FileNotFoundError(f'找不到模型目录: {model_dir}')
            for position in ['landlord', 'landlord_up', 'landlord_down']:
                model_path = os.path.join(model_dir, f'{position}.ckpt')
                self.models[mtype][position] = self._load_model(position, model_path)
        print("模型加载完成! 默认推荐模型: WP（胜率）\n")
    
    def _load_model(self, position, model_path):
        """加载单个位置的模型"""
        model = model_dict[position]()
        model_state_dict = model.state_dict()
        try:
            pretrained = torch.load(model_path, map_location='cpu', weights_only=True)
        except Exception:
            pretrained = torch.load(model_path, map_location='cpu')
        pretrained = {k: v for k, v in pretrained.items() if k in model_state_dict}
        model_state_dict.update(pretrained)
        model.load_state_dict(model_state_dict)
        model.eval()
        return model

    def set_model_type(self, mtype):
        """切换推荐模型: WP 或 ADP"""
        key = mtype.strip().upper()
        if key not in self.models:
            print(f"错误: 未知模型类型 {mtype}，可选: WP / ADP")
            return False
        self.model_type = key
        desc = '胜率优先' if key == 'WP' else '平均分差'
        print(f"推荐模型已切换: {key} ({desc})")
        return True

    def set_search(self, enabled=None, sims=None, top_k=None,
                   wp_weight=None, workers=None, objective=None):
        """配置仿真搜索。"""
        if enabled is not None:
            self.search_enabled = bool(enabled)
        if sims is not None:
            self.search_sims = max(4, int(sims))
        if top_k is not None:
            self.search_top_k = max(2, int(top_k))
        if wp_weight is not None:
            self.search_wp_weight = max(0.0, min(1.0, float(wp_weight)))
        if workers is not None:
            self.search_workers = max(0, int(workers))
            if self.search_workers <= 1:
                # 切到单进程时立刻回收并行池
                try:
                    from ddz_search import shutdown_pools
                    shutdown_pools()
                except Exception:
                    pass
        if objective is not None:
            obj = objective.strip().upper()
            if obj not in ('WP', 'ADP', 'FUSE', 'MIX'):
                print("错误: objective 只能是 WP / ADP / FUSE")
                return False
            if obj == 'MIX':
                obj = 'FUSE'
            self.search_objective = obj
        state = '开启' if self.search_enabled else '关闭'
        wtxt = f"WP权重={self.search_wp_weight:.2f}"
        ptxt = f"workers={self.search_workers}" if self.search_workers > 1 else "单进程"
        print(f"仿真搜索: {state} | n={self.search_sims} k={self.search_top_k} "
              f"目标={self.search_objective} {wtxt} {ptxt}")
        return True

    def _format_score(self, value):
        """格式化模型打分，便于阅读"""
        if self.model_type == 'WP':
            # WP 输出接近胜率
            pct = max(0.0, min(1.0, float(value))) * 100.0
            return f"胜率≈{pct:.1f}%"
        return f"分差≈{float(value):+.3f}"

    def parse_cards(self, card_str):
        """
        解析用户输入的牌字符串
        支持格式: "3 4 5 6 7" 或 "34567" 或 "3,4,5,6,7" 或任意顺序如 "J J J 3"
        自动排序为内部顺序: 3,4,5,6,7,8,9,10,J,Q,K,A,2,X,D
        单独的'1'会被当作'10'处理
        """
        # 移除空格和逗号
        card_str = card_str.replace(' ', '').replace(',', '').upper()
        
        # 转换为内部牌编码
        cards = []
        i = 0
        while i < len(card_str):
            char = card_str[i]
            
            # 处理10: "10" 或单独的 "1" 都当作10
            if char == '1':
                if i + 1 < len(card_str) and card_str[i+1] == '0':
                    cards.append(RealCard2EnvCard['10'])
                    i += 2
                else:
                    # 单独的'1'也当作'10'
                    cards.append(RealCard2EnvCard['10'])
                    i += 1
                continue
            
            # 处理特殊牌
            if char in ['X', 'x']:  # 小王
                cards.append(RealCard2EnvCard['X'])
            elif char in ['D', 'd']:  # 大王
                cards.append(RealCard2EnvCard['D'])
            elif char in RealCard2EnvCard:
                cards.append(RealCard2EnvCard[char])
            else:
                raise ValueError(f"无法识别的牌: {char}")
            
            i += 1
        
        # 自动排序（按内部编码顺序）
        return sorted(cards)
    
    def cards_to_display(self, cards):
        """将内部编码转换为可显示的牌"""
        return ' '.join([EnvCard2RealCard[card] for card in cards])
    
    def start_game(self, position, hand_cards_str, three_cards_str=None):
        """
        开始游戏
        position: 'landlord', 'landlord_up', 或 'landlord_down'
        hand_cards_str: 手牌字符串
        three_cards_str: 三张底牌字符串 (所有玩家都可以输入)
        """
        self.my_position = position
        
        # 解析手牌：必须正好 17 张（发牌手牌；地主再并入底牌）
        hand_cards = self.parse_cards(hand_cards_str)
        if len(hand_cards) != 17:
            raise ValueError(
                f"手牌必须是17张，当前解析到 {len(hand_cards)} 张。"
                f"请只输入起始手牌，不要把底牌算进去。"
            )
        three_cards = self.parse_cards(three_cards_str) if three_cards_str else []

        # 地主：17 张起始手牌 + 3 张底牌 → 20 张
        if position == 'landlord':
            if len(three_cards) != 3:
                raise ValueError("地主需要正好 3 张底牌，以便合并成 20 张手牌")
            hand_cards = sorted(hand_cards + three_cards)
        elif len(three_cards) not in (0, 3):
            raise ValueError("底牌需为 3 张（可留空）")
        
        # 保存状态
        self.my_hand_cards = hand_cards
        self.three_cards = three_cards

        # 记牌器：初始化为「已见」自己的手牌（+农民的底牌）
        self.seen_cards = Counter()
        for c in hand_cards:
            self.seen_cards[c] += 1
        if position != 'landlord':
            for c in three_cards:
                self.seen_cards[c] += 1
        
        # 如果是地主，底牌已经包含在手牌中
        if position == 'landlord':
            self.num_cards_left['landlord'] = len(hand_cards)
        else:
            # 农民初始17张牌
            self.num_cards_left['landlord'] = 20
            self.num_cards_left[position] = len(hand_cards)
        
        print(f"我的手牌: {self.cards_to_display(hand_cards)} ({len(hand_cards)}张)")
        if three_cards:
            print(f"底牌: {self.cards_to_display(three_cards)}")
        
        print(f"\n游戏开始! 我的位置: {self._get_position_name(position)}")
        print("请逐次输入其他玩家的出牌,轮到我时我会推荐最佳出牌。\n")
        print(f"记牌器(未出现): {self._unseen_display()}\n")

    # ---------- 记牌器 ----------
    def _note_seen(self, cards):
        """记录已出现的牌（手牌/底牌/任何出牌）。"""
        for c in cards or []:
            self.seen_cards[c] += 1

    def get_unseen_counts(self):
        """返回 {显示牌面: 未出现张数}。"""
        max_map = {3: 4, 4: 4, 5: 4, 6: 4, 7: 4, 8: 4, 9: 4, 10: 4,
                   11: 4, 12: 4, 13: 4, 14: 4, 17: 4, 20: 1, 30: 1}
        out = {}
        for card_id, total in max_map.items():
            left = total - int(self.seen_cards.get(card_id, 0))
            out[EnvCard2RealCard[card_id]] = max(0, left)
        return out

    def _unseen_display(self):
        u = self.get_unseen_counts()
        # 始终输出全部点数（含 0），避免界面把“省略”误当成满张
        return ' '.join(f"{k}:{v}" for k, v in u.items())
    
    def _get_position_name(self, position):
        """获取位置的中文名称"""
        names = {
            'landlord': '地主',
            'landlord_up': '地主上家',
            'landlord_down': '地主下家'
        }
        return names[position]
    
    def _get_next_position(self, position):
        """获取下一个出牌位置"""
        if position == 'landlord':
            return 'landlord_down'
        elif position == 'landlord_down':
            return 'landlord_up'
        else:
            return 'landlord'
    
    def _build_infoset(self):
        """
        构建InfoSet对象用于模型推理
        这是关键方法：根据当前状态构建模型需要的输入
        """
        from douzero.env.game import InfoSet
        from douzero.env.move_generator import MovesGener
        from douzero.env.move_detector import get_move_type
        from douzero.env.move_selector import (
            filter_type_1_single, filter_type_2_pair, filter_type_3_triple,
            filter_type_4_bomb, filter_type_6_3_1, filter_type_7_3_2,
            filter_type_8_serial_single, filter_type_9_serial_pair,
            filter_type_10_serial_triple, filter_type_11_serial_3_1,
            filter_type_12_serial_3_2, filter_type_13_4_2, filter_type_14_4_22
        )
        from douzero.env.move_detector import (
            TYPE_0_PASS, TYPE_1_SINGLE, TYPE_2_PAIR, TYPE_3_TRIPLE,
            TYPE_4_BOMB, TYPE_5_KING_BOMB, TYPE_6_3_1, TYPE_7_3_2,
            TYPE_8_SERIAL_SINGLE, TYPE_9_SERIAL_PAIR, TYPE_10_SERIAL_TRIPLE,
            TYPE_11_SERIAL_3_1, TYPE_12_SERIAL_3_2, TYPE_13_4_2, TYPE_14_4_22
        )
        
        # 创建InfoSet
        infoset = InfoSet(self.my_position)
        
        # 我的手牌
        infoset.player_hand_cards = self.my_hand_cards.copy()
        
        # 剩余手牌数
        infoset.num_cards_left_dict = self.num_cards_left.copy()
        
        # 三张底牌
        infoset.three_landlord_cards = self.three_cards.copy()
        
        # 出牌历史
        infoset.card_play_action_seq = self.card_play_action_seq.copy()
        
        # 计算对手剩余手牌的并集
        # 总牌数54张，减去已知的牌（我的手牌 + 所有已出的牌 + 底牌）
        all_known_cards = self.my_hand_cards.copy()
        for pos in ['landlord', 'landlord_up', 'landlord_down']:
            all_known_cards.extend(self.played_cards[pos])
        all_known_cards.extend(self.three_cards)
        
        # 计算对手手牌并集
        other_hand_cards = []
        for card_id in [3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30]:
            if card_id in [20, 30]:  # 王
                max_count = 1
            elif card_id == 17:  # 2
                max_count = 4
            else:
                max_count = 4
            
            known_count = all_known_cards.count(card_id)
            remaining = max_count - known_count
            other_hand_cards.extend([card_id] * remaining)
        
        infoset.other_hand_cards = other_hand_cards
        
        # 最后一次出牌
        infoset.last_move = self.last_move.copy()
        
        # 各位置最后一次出牌
        infoset.last_move_dict = {
            'landlord': self.last_move_dict['landlord'].copy(),
            'landlord_up': self.last_move_dict['landlord_up'].copy(),
            'landlord_down': self.last_move_dict['landlord_down'].copy()
        }
        
        # 各位置已出的牌
        infoset.played_cards = {
            'landlord': self.played_cards['landlord'].copy(),
            'landlord_up': self.played_cards['landlord_up'].copy(),
            'landlord_down': self.played_cards['landlord_down'].copy()
        }
        
        # 炸弹数
        infoset.bomb_num = self.bomb_num
        
        # 计算合法出牌
        mg = MovesGener(self.my_hand_cards)
        
        # 获取对手最后一次出牌的类型
        # 如果上一次有效出牌是我自己，说明其他两家都pass了，我可以自由出牌
        if self.last_pid == self.my_position:
            rival_move = []
        else:
            rival_move = self.last_move.copy()
        rival_type_info = get_move_type(rival_move)
        rival_type = rival_type_info['type']
        rival_len = rival_type_info.get('len', 1)
        
        moves = []
        
        # 根据对手出牌类型生成合法出牌
        if rival_type == TYPE_0_PASS or len(rival_move) == 0:
            # 对手没出牌或pass，我可以出任何牌
            moves = mg.gen_moves()
        elif rival_type == TYPE_1_SINGLE:
            all_moves = mg.gen_type_1_single()
            moves = filter_type_1_single(all_moves, rival_move)
        elif rival_type == TYPE_2_PAIR:
            all_moves = mg.gen_type_2_pair()
            moves = filter_type_2_pair(all_moves, rival_move)
        elif rival_type == TYPE_3_TRIPLE:
            all_moves = mg.gen_type_3_triple()
            moves = filter_type_3_triple(all_moves, rival_move)
        elif rival_type == TYPE_4_BOMB:
            all_moves = mg.gen_type_4_bomb() + mg.gen_type_5_king_bomb()
            moves = filter_type_4_bomb(all_moves, rival_move)
        elif rival_type == TYPE_5_KING_BOMB:
            moves = []
        elif rival_type == TYPE_6_3_1:
            all_moves = mg.gen_type_6_3_1()
            moves = filter_type_6_3_1(all_moves, rival_move)
        elif rival_type == TYPE_7_3_2:
            all_moves = mg.gen_type_7_3_2()
            moves = filter_type_7_3_2(all_moves, rival_move)
        elif rival_type == TYPE_8_SERIAL_SINGLE:
            all_moves = mg.gen_type_8_serial_single(repeat_num=rival_len)
            moves = filter_type_8_serial_single(all_moves, rival_move)
        elif rival_type == TYPE_9_SERIAL_PAIR:
            all_moves = mg.gen_type_9_serial_pair(repeat_num=rival_len)
            moves = filter_type_9_serial_pair(all_moves, rival_move)
        elif rival_type == TYPE_10_SERIAL_TRIPLE:
            all_moves = mg.gen_type_10_serial_triple(repeat_num=rival_len)
            moves = filter_type_10_serial_triple(all_moves, rival_move)
        elif rival_type == TYPE_11_SERIAL_3_1:
            all_moves = mg.gen_type_11_serial_3_1(repeat_num=rival_len)
            moves = filter_type_11_serial_3_1(all_moves, rival_move)
        elif rival_type == TYPE_12_SERIAL_3_2:
            all_moves = mg.gen_type_12_serial_3_2(repeat_num=rival_len)
            moves = filter_type_12_serial_3_2(all_moves, rival_move)
        elif rival_type == TYPE_13_4_2:
            all_moves = mg.gen_type_13_4_2()
            moves = filter_type_13_4_2(all_moves, rival_move)
        elif rival_type == TYPE_14_4_22:
            all_moves = mg.gen_type_14_4_22()
            moves = filter_type_14_4_22(all_moves, rival_move)
        
        # 如果不是pass、炸弹、王炸，可以加炸弹和王炸
        if rival_type not in [TYPE_0_PASS, TYPE_4_BOMB, TYPE_5_KING_BOMB]:
            moves = moves + mg.gen_type_4_bomb() + mg.gen_type_5_king_bomb()
        
        # 如果对手出了牌（不是pass），可以加pass
        if len(rival_move) != 0:
            moves = moves + [[]]
        
        # 排序所有出牌
        for m in moves:
            m.sort()
        
        infoset.legal_actions = moves
        
        return infoset
    
    def record_move(self, position, cards_str):
        """
        记录出牌
        position: 出牌玩家的位置
        cards_str: 出牌字符串,空字符串表示"不出"
        若 position 正是我自己的位置，则与 play 等价（扣手牌、校验合法性）。
        返回: True-成功, False-失败, 'game_over'-游戏结束
        """
        # 记录自己 → 走 play_move，保证手牌同步减少
        if self.my_position is not None and position == self.my_position:
            return self.play_move(cards_str)

        # 验证出牌顺序
        if position != self.acting_player_position:
            print(f"错误: 现在不是 {self._get_position_name(position)} 出牌")
            print(f"当前应该由 {self._get_position_name(self.acting_player_position)} 出牌")
            return False
        
        if cards_str.strip() == '' or cards_str.strip().lower() == 'pass':
            # 不出
            action = []
        else:
            # 解析并自动排序
            action = self.parse_cards(cards_str)
            # 再次确保排序（防御性编程）
            action = sorted(action)
        
        # 更新出牌记录
        self.last_move_dict[position] = action.copy()
        self.card_play_action_seq.append(action.copy())
        self.played_cards[position].extend(action)
        self._note_seen(action)
        
        # 更新炸弹计数
        if action in bombs:
            self.bomb_num += 1
        
        # 更新最后出牌信息
        if len(action) > 0:
            self.last_move = action.copy()
            self.last_pid = position
        
        # 更新剩余手牌数
        self.num_cards_left[position] -= len(action)
        
        # 更新轮换
        self.acting_player_position = self._get_next_position(position)
        
        action_display = '不出' if not action else self.cards_to_display(action)
        print(f"{self._get_position_name(position)}出牌: {action_display}")
        print(f"  (剩余 {self.num_cards_left[position]} 张)")
        print(f"记牌器: {self._unseen_display()}")
        
        # 检查游戏是否结束
        if self.num_cards_left[position] == 0:
            winner = self._get_position_name(position)
            print(f"\n{'='*50}")
            print(f"游戏结束! {winner} 获胜!")
            print(f"{'='*50}")
            self.game_over = True
            return 'game_over'
        
        return True
    
    def recommend_move(self):
        """
        推荐当前最佳出牌（使用当前 model_type: WP/ADP）
        返回推荐的牌列表
        """
        if self.acting_player_position != self.my_position:
            print(f"现在不是轮到我出牌,当前出牌方: {self._get_position_name(self.acting_player_position)}")
            return None

        # 构建InfoSet
        infoset = self._build_infoset()

        # 如果只有一个合法动作,直接返回
        if len(infoset.legal_actions) == 1:
            action = infoset.legal_actions[0]
            action_display = '不出' if not action else self.cards_to_display(action)
            print(f"\n推荐出牌: {action_display}  [{self.model_type}]")
            return action

        # 仿真搜索模式
        if self.search_enabled:
            try:
                from ddz_search import search_best_move
            except Exception as e:
                print(f"搜索模块不可用，回退单步模型: {e}")
            else:
                print(f"\n启动仿真搜索（{self.model_type} 策略，目标 {self.search_objective}，"
                      f"{self.search_sims} 次，候选上限 {self.search_top_k}）...")
                best_action, report = search_best_move(
                    self.models[self.model_type],
                    self,
                    infoset,
                    num_simulations=self.search_sims,
                    top_k=self.search_top_k,
                    objective=self.search_objective,
                    wp_weight=self.search_wp_weight,
                    models_adp=self.models['ADP'],
                    ckpt_root=self.ckpt_root,
                    num_workers=self.search_workers,
                    verbose=True,
                )
                action_display = '不出' if not best_action else self.cards_to_display(best_action)
                wr = report.get('win_rate')
                au = report.get('avg_util')
                fused = report.get('fused')
                meta = []
                if wr is not None:
                    meta.append(f"胜率={wr*100:.1f}%")
                if au is not None:
                    meta.append(f"分差={au:+.3f}")
                if fused is not None:
                    meta.append(f"融合分={fused:.3f}")
                meta_s = (' | ' + ' '.join(meta)) if meta else ''
                print(f"\n推荐出牌: {action_display}  [搜索 {self.search_objective}{meta_s}]")

                cands = report.get('candidates') or []
                if len(cands) > 1:
                    print("\n搜索候选:")
                    for i, row in enumerate(cands[:6], 1):
                        mark = ' ←' if i == 1 else ''
                        print(f"  {i}. {row['display']}  "
                              f"(胜率={row['win_rate']*100:.1f}%, 分差={row['avg_util']:+.3f}, "
                              f"融合={row.get('fused', 0):.3f}, n={row['n']}){mark}")
                return best_action

        # 使用模型推理
        obs = get_obs(infoset)

        z_batch = torch.from_numpy(obs['z_batch']).float()
        x_batch = torch.from_numpy(obs['x_batch']).float()

        model = self.models[self.model_type][self.my_position]
        with torch.no_grad():
            y_pred = model.forward(z_batch, x_batch, return_value=True)['values']
        y_pred = y_pred.numpy().flatten()

        best_action_index = int(np.argmax(y_pred))
        best_action = infoset.legal_actions[best_action_index]
        best_score = float(y_pred[best_action_index])

        action_display = '不出' if not best_action else self.cards_to_display(best_action)
        print(f"\n推荐出牌: {action_display}  [{self.model_type} {self._format_score(best_score)}]")

        # 显示前3个候选（含分数）
        if len(infoset.legal_actions) > 1:
            print("\n其他候选出牌:")
            sorted_indices = np.argsort(y_pred)[::-1]
            for i, idx in enumerate(sorted_indices[1:4], 2):
                if idx < len(infoset.legal_actions):
                    alt_action = infoset.legal_actions[idx]
                    alt_display = '不出' if not alt_action else self.cards_to_display(alt_action)
                    print(f"  {i}. {alt_display}  ({self._format_score(float(y_pred[idx]))})")

        return best_action
    
    def play_move(self, cards_str):
        """
        执行我的出牌
        cards_str: 出牌字符串,空字符串表示"不出"
        支持任意顺序输入，自动排序
        """
        if self.acting_player_position != self.my_position:
            print(f"现在不是轮到我出牌,当前出牌方: {self._get_position_name(self.acting_player_position)}")
            return
        
        if cards_str.strip() == '' or cards_str.strip().lower() == 'pass':
            action = []
        else:
            # 解析并自动排序
            action = self.parse_cards(cards_str)
            # 再次确保排序（防御性编程）
            action = sorted(action)
        
        # 构建InfoSet验证合法性
        infoset = self._build_infoset()
        
        # 验证是否是合法动作
        if action not in infoset.legal_actions:
            print(f"错误: 不是合法的出牌组合")
            print("合法出牌选项:")
            for i, legal_action in enumerate(infoset.legal_actions[:10], 1):
                action_display = '不出' if not legal_action else self.cards_to_display(legal_action)
                print(f"  {i}. {action_display}")
            return
        
        # 更新手牌
        for card in action:
            self.my_hand_cards.remove(card)
        self.my_hand_cards.sort()
        
        # 更新出牌记录
        self.last_move_dict[self.my_position] = action.copy()
        self.card_play_action_seq.append(action.copy())
        self.played_cards[self.my_position].extend(action)
        self._note_seen(action)
        
        # 更新炸弹计数
        if action in bombs:
            self.bomb_num += 1
        
        # 更新最后出牌信息
        if len(action) > 0:
            self.last_move = action.copy()
            self.last_pid = self.my_position
        
        # 更新剩余手牌数
        self.num_cards_left[self.my_position] -= len(action)
        
        # 更新轮换
        self.acting_player_position = self._get_next_position(self.my_position)
        
        action_display = '不出' if not action else self.cards_to_display(action)
        print(f"我出牌: {action_display}")
        print(f"剩余手牌: {self.cards_to_display(self.my_hand_cards)} ({len(self.my_hand_cards)}张)")
        print(f"记牌器: {self._unseen_display()}")
        
        # 检查游戏是否结束
        if len(self.my_hand_cards) == 0:
            print(f"\n{'='*50}")
            print(f"游戏结束! 我 ({self._get_position_name(self.my_position)}) 获胜!")
            print(f"{'='*50}")
            self.game_over = True
            return 'game_over'
        
        return True
    
    def reset(self):
        """重置游戏状态，准备开始新对局"""
        self.my_hand_cards = []
        self.three_cards = []
        self.card_play_action_seq = []
        self.played_cards = {'landlord': [], 'landlord_up': [], 'landlord_down': []}
        self.last_move_dict = {'landlord': [], 'landlord_up': [], 'landlord_down': []}
        self.last_move = []
        self.last_pid = 'landlord'
        self.bomb_num = 0
        self.acting_player_position = 'landlord'
        self.game_over = False
        self.my_position = None
        self.num_cards_left = {'landlord': 20, 'landlord_up': 17, 'landlord_down': 17}
        self.seen_cards = Counter()
        print("游戏状态已重置，可以开始新对局。\n")


def main():
    """主程序"""
    print("=" * 60)
    print("斗地主AI出牌助手")
    print("基于DouZero预训练模型")
    print("=" * 60)
    
    # 模型路径（checkpoints 根目录，内含 douzero_WP / douzero_ADP）
    ckpt_root = os.path.join(os.path.dirname(__file__), 'douzero-baselines', 'checkpoints')

    if not os.path.exists(ckpt_root):
        print(f"错误: 找不到模型目录 {ckpt_root}")
        print("请确保 douzero-baselines 文件夹在当前目录下")
        return

    # 初始化助手
    assistant = DDZAssistant(ckpt_root)
    
    def setup_game():
        """设置新对局"""
        print("\n请选择你的位置:")
        print("1. 地主上家 (landlord_up)")
        print("2. 地主 (landlord)")
        print("3. 地主下家 (landlord_down)")
        
        while True:
            choice = input("请输入数字 (1-3): ").strip()
            if choice in ['1', '2', '3']:
                position_map = {'1': 'landlord_up', '2': 'landlord', '3': 'landlord_down'}
                position = position_map[choice]
                break
            print("无效输入,请重新输入")
        
        # 输入手牌
        print("\n请输入你的手牌，必须正好 17 张 (例如: 3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4)")
        print("提示: 只输起始 17 张；若你是地主，3 张底牌会自动并入，不要写在手牌里")
        hand_cards_str = input("手牌: ").strip()

        # 所有玩家都可以输入底牌（三家都能看到）
        print("\n请输入三张底牌（地主必填正好 3 张；农民可留空）")
        print("提示: 地主手牌不会手输底牌，系统会自动并入")
        three_cards_str = input("底牌: ").strip()
        if three_cards_str.lower() in ("pass", "无", "none", "-"):
            three_cards_str = ""

        # 开始游戏
        try:
            assistant.start_game(position, hand_cards_str, three_cards_str)
        except Exception as e:
            print(f"错误: {e}")
            print("请重新设置对局")
            return False
        return True
    
    # 游戏循环（支持多局）
    print("\n命令说明:")
    print("  record <位置> <牌> - 记录其他玩家出牌")
    print("    位置: landlord(地主), landlord_up(地主上家), landlord_down(地主下家)")
    print("    牌: 出牌内容,或 'pass' 表示不出")
    print("  recommend - 获取AI推荐出牌")
    print("  play <牌> - 执行我的出牌")
    print("  model [WP|ADP] - 查看/切换推荐模型 (WP胜率, ADP分差)")
    print("  search [on|off] [n=次数] [k=候选] [w=WP权重] [workers=N] [obj=FUSE|WP|ADP] - 仿真搜索")
    print("  status - 显示当前状态")
    print("  reset - 开始新对局")
    print("  help - 显示帮助")
    print("  quit - 退出程序")
    print()
    
    # 第一局（失败则进入命令循环，可用 reset 重开）
    if not setup_game():
        print("开局未完成。可输入 reset 重新设置，或 quit 退出。")

    over_hint_shown = False
    while True:
        try:
            # 检查游戏是否结束（只提示一次，避免刷屏）
            if assistant.game_over and not over_hint_shown:
                print("\n游戏已结束。可点击 GUI「新对局」，或输入 'reset' 开始新对局 / 'quit' 退出。")
                over_hint_shown = True
            elif not assistant.game_over:
                over_hint_shown = False
            
            cmd = input("> ").strip()
            
            if cmd == 'quit' or cmd == 'exit':
                print("再见!")
                break
            
            elif cmd == 'reset':
                assistant.reset()
                if not setup_game():
                    continue
            
            elif cmd == 'help':
                print("\n命令说明:")
                print("  record <位置> <牌> - 记录其他玩家出牌")
                print("  recommend - 获取AI推荐出牌")
                print("  play <牌> - 执行我的出牌")
                print("  model [WP|ADP] - 查看/切换推荐模型")
                print("  search [on|off] [n=200] [k=6] [w=0.7] [workers=8] [obj=FUSE|WP|ADP]")
                print("  status - 显示当前状态")
                print("  reset - 开始新对局")
                print("  quit - 退出程序")
            
            elif cmd.startswith('record '):
                if assistant.game_over:
                    print("游戏已结束，请先输入 'reset' 开始新对局")
                    continue
                parts = cmd.split(' ', 2)
                if len(parts) < 3:
                    print("用法: record <位置> <牌>")
                    print("  位置: landlord, landlord_up, landlord_down")
                    continue
                pos = parts[1]
                cards = parts[2]
                if pos not in ['landlord', 'landlord_up', 'landlord_down']:
                    print("无效位置,请使用: landlord, landlord_up, landlord_down")
                    continue
                try:
                    result = assistant.record_move(pos, cards)
                    if result == 'game_over':
                        pass  # 游戏结束消息已在 record_move 中打印
                except Exception as e:
                    print(f"错误: {e}")
            
            elif cmd == 'recommend':
                if assistant.game_over:
                    print("游戏已结束，请先输入 'reset' 开始新对局")
                    continue
                try:
                    assistant.recommend_move()
                except Exception as e:
                    print(f"错误: {e}")
            
            elif cmd.startswith('play '):
                if assistant.game_over:
                    print("游戏已结束，请先输入 'reset' 开始新对局")
                    continue
                cards = cmd[5:].strip()
                try:
                    assistant.play_move(cards)
                except Exception as e:
                    print(f"错误: {e}")
            
            elif cmd == 'play':
                print("用法: play <牌>")
                print("  例如: play 3 4 5 6 7")
                print("  不出: play pass")
            
            elif cmd.lower() == 'model':
                desc = '胜率优先' if assistant.model_type == 'WP' else '平均分差'
                print(f"当前推荐模型: {assistant.model_type} ({desc})")
                print("切换: model WP  或  model ADP")

            elif cmd.lower().startswith('model '):
                parts = cmd.split()
                assistant.set_model_type(parts[1])

            elif cmd.lower() == 'search' or cmd.lower().startswith('search '):
                tokens = cmd.lower().split()
                if len(tokens) == 1:
                    state = '开启' if assistant.search_enabled else '关闭'
                    wtxt = f"WP权重={assistant.search_wp_weight:.2f}"
                    ptxt = f"workers={assistant.search_workers}" if assistant.search_workers > 1 else "单进程"
                    print(f"仿真搜索: {state} | n={assistant.search_sims} k={assistant.search_top_k} "
                          f"目标={assistant.search_objective} {wtxt} {ptxt}")
                    print("用法: search on|off [n=200] [k=6] [w=0.7] [workers=8] [obj=FUSE|WP|ADP]")
                    continue
                enabled = None
                sims = None
                top_k = None
                wp_weight = None
                workers = None
                objective = None
                for tok in tokens[1:]:
                    if tok in ('on', '1', 'true', 'yes'):
                        enabled = True
                    elif tok in ('off', '0', 'false', 'no'):
                        enabled = False
                    elif tok.startswith('n='):
                        sims = int(tok[2:])
                    elif tok.startswith('k='):
                        top_k = int(tok[2:])
                    elif tok.startswith('w='):
                        wp_weight = float(tok[2:])
                    elif tok.startswith('workers=') or tok.startswith('p='):
                        workers = int(tok.split('=', 1)[1])
                    elif tok.startswith('obj='):
                        objective = tok.split('=', 1)[1]
                    else:
                        print(f"无法识别参数: {tok}")
                try:
                    assistant.set_search(
                        enabled=enabled, sims=sims, top_k=top_k,
                        wp_weight=wp_weight, workers=workers, objective=objective,
                    )
                except Exception as e:
                    print(f"错误: {e}")

            elif cmd == 'status':
                print(f"\n我的位置: {assistant._get_position_name(assistant.my_position)}")
                print(f"我的手牌: {assistant.cards_to_display(assistant.my_hand_cards)} ({len(assistant.my_hand_cards)}张)")
                print(f"当前出牌方: {assistant._get_position_name(assistant.acting_player_position)}")
                print(f"推荐模型: {assistant.model_type}")
                sstate = '开' if assistant.search_enabled else '关'
                ptxt = f"w={assistant.search_workers}" if assistant.search_workers > 1 else "单进程"
                print(f"仿真搜索: {sstate} (n={assistant.search_sims}, k={assistant.search_top_k}, "
                      f"obj={assistant.search_objective}, WP权重={assistant.search_wp_weight:.2f}, {ptxt})")
                print(f"记牌器(未出现): {assistant._unseen_display()}")
                print(f"已出炸弹数: {assistant.bomb_num}")
                print(f"底牌: {assistant.cards_to_display(assistant.three_cards)}")
                print(f"\n各位置剩余手牌:")
                for pos in ['landlord', 'landlord_up', 'landlord_down']:
                    print(f"  {assistant._get_position_name(pos)}: {assistant.num_cards_left[pos]}张")
                if assistant.game_over:
                    print("\n游戏已结束，输入 'reset' 开始新对局")
            
            elif cmd:
                print(f"未知命令: {cmd}")
                print("输入 'help' 查看帮助")
        
        except KeyboardInterrupt:
            print("\n\n再见!")
            break
        except Exception as e:
            print(f"错误: {e}")


if __name__ == '__main__':
    main()
