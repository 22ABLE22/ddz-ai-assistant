# -*- coding: utf-8 -*-
"""
导出 DouZero 权重 + 生成 Java 侧验收用的黄金参考向量。

产出：
  android-offline/app/src/main/assets/ddz_<obj>_<pos>.bin   权重（little-endian float32，按 meta 顺序拼接）
  android-offline/app/src/main/assets/ddz_meta.json         张量形状/偏移 + 模型元信息
  tools/golden/*.json                                       黄金测试向量（不进 APK）

黄金向量分两层：
  Layer A  forward 层：固定随机 z/x -> 期望 values（验证 LSTM/Dense 数值实现）
  Layer B  encode 层：完整 infoset 快照 -> 期望 x_batch/z_batch（验证特征编码）
"""
import sys, os, json, struct
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # 项目根（tools/ 的上一级）
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, 'DouZero'))

import torch

CKPT = os.path.join(ROOT, 'douzero-baselines', 'checkpoints')
ASSETS = os.path.join(ROOT, 'android-offline', 'app', 'src', 'main', 'assets')
GOLDEN = os.path.join(ROOT, 'tools', 'golden')
os.makedirs(ASSETS, exist_ok=True)
os.makedirs(GOLDEN, exist_ok=True)

POSITIONS = ['landlord', 'landlord_up', 'landlord_down']
OBJECTIVES = ['WP', 'ADP']

# 每个位置的张量顺序（Java 侧按同一顺序读取）
TENSOR_ORDER = [
    'lstm.weight_ih_l0', 'lstm.weight_hh_l0', 'lstm.bias_ih_l0', 'lstm.bias_hh_l0',
    'dense1.weight', 'dense1.bias',
    'dense2.weight', 'dense2.bias',
    'dense3.weight', 'dense3.bias',
    'dense4.weight', 'dense4.bias',
    'dense5.weight', 'dense5.bias',
    'dense6.weight', 'dense6.bias',
]


def export_weights():
    meta = {'format': 'ddz-douzero-v1', 'dtype': 'float32', 'byteorder': 'little',
            'positions': POSITIONS, 'objectives': OBJECTIVES, 'tensorOrder': TENSOR_ORDER,
            'models': {}}
    for obj in OBJECTIVES:
        for pos in POSITIONS:
            src = os.path.join(CKPT, f'douzero_{obj}', f'{pos}.ckpt')
            sd = torch.load(src, map_location='cpu', weights_only=True)
            tensors = []
            entries = []
            offset = 0
            for name in TENSOR_ORDER:
                t = sd[name].detach().cpu().numpy().astype('<f4')
                flat = np.ascontiguousarray(t.reshape(-1))
                entries.append({'name': name, 'shape': list(t.shape),
                                'offset': offset, 'count': int(flat.size)})
                tensors.append(flat)
                offset += flat.size
            blob = np.concatenate(tensors)
            fn = f'ddz_{obj}_{pos}.bin'
            blob.tofile(os.path.join(ASSETS, fn))
            meta['models'][f'{obj}/{pos}'] = {
                'file': fn, 'bytes': int(blob.nbytes),
                'params': int(blob.size), 'tensors': entries,
            }
            print(f'  {obj}/{pos:14s} {blob.size:>9,} params  {blob.nbytes/1024/1024:6.2f} MB  -> {fn}')
    with open(os.path.join(ASSETS, 'ddz_meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    print(f'\nmeta -> ddz_meta.json')
    return meta


# ---------------- Layer A：forward 数值验证 ----------------
def golden_forward(n_cases=6, seed=20261013):
    rng = np.random.RandomState(seed)
    cases = []
    for obj in OBJECTIVES:
        for pos in POSITIONS:
            src = os.path.join(CKPT, f'douzero_{obj}', f'{pos}.ckpt')
            sd = torch.load(src, map_location='cpu', weights_only=True)
            from douzero.dmc.models import model_dict
            model = model_dict[pos]()
            model.load_state_dict({k: v for k, v in sd.items()})
            model.eval()

            in_dim = sd['dense1.weight'].shape[1] - 128   # 501-128=373 / 612-128=484
            for ci in range(n_cases):
                n_legal = int(rng.randint(2, 40))
                # 用 0/1 稀疏特征，贴近真实 obs（避免极端值掩盖数值差异）
                x = (rng.rand(n_legal, in_dim) < 0.08).astype(np.float32)
                z1 = (rng.rand(5, 162) < 0.06).astype(np.float32)
                # 与真实管线一致：z_batch = repeat(z, n_legal)
                z = np.repeat(z1[np.newaxis, :, :], n_legal, axis=0)
                with torch.no_grad():
                    out = model.forward(torch.from_numpy(z),
                                        torch.from_numpy(x), return_value=True)
                vals = out['values'].numpy().astype('<f4').reshape(-1)
                cases.append({
                    'objective': obj, 'position': pos, 'index': ci,
                    'in_dim': int(in_dim), 'n_legal': n_legal,
                    'x': x.reshape(-1).astype('<f4').tolist(),
                    'z': z1.reshape(-1).astype('<f4').tolist(),
                    'expected_values': vals.tolist(),
                })
            print(f'  forward golden: {obj}/{pos}  {n_cases} 例  in_dim={in_dim}')
    path = os.path.join(GOLDEN, 'layer_a_forward.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'cases': cases}, f)
    print(f'  -> {os.path.relpath(path, ROOT)}  ({os.path.getsize(path)/1024:.0f} KB)')
    return cases


# ---------------- Layer B：特征编码验证 ----------------
def canonicalize(moves):
    """与 Java MoveGenerator.canonicalize 完全一致的排序：
    先按长度，再逐元素比较。用于消除 Python 侧 set/dict 迭代序的不确定性。"""
    norm = [sorted(list(m)) for m in moves]
    norm.sort(key=lambda m: (len(m), m))
    return norm


def golden_encode():
    from douzero.env.env import get_obs
    from ddz_assistant import DDZAssistant
    import io, contextlib

    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        a = DDZAssistant(CKPT)
        a.set_search(enabled=False)

    scenarios = [
        # (身份, 手牌, 底牌, [(位置, 出的牌)...])
        ('landlord',      '3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4', '9 9 2', []),
        ('landlord_down', '3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4', '9 9 2',
         [('landlord', '5')]),
        ('landlord_up',   '3 3 4 5 6 7 8 9 10 J Q K A 2 X D 4', '9 9 2',
         [('landlord', 'K K'), ('landlord_down', '')]),
        ('landlord_down', '3 3 3 4 4 4 5 6 7 8 9 10 J Q K A 2', 'X D 2',
         [('landlord', '3 4 5 6 7')]),
        ('landlord',      '5 5 6 6 7 7 8 8 9 9 10 10 J J Q Q K', 'A A 2',
         [('landlord_up', '3'), ('landlord_down', '4'), ('landlord', '5')]),
    ]

    cases = []
    for idx, (pos, hand, three, records) in enumerate(scenarios):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            a.reset()
            a.start_game(pos, hand, three)
            for rp, rc in records:
                a.record_move(rp, rc)
            infoset = a._build_infoset()
            # Python 的 legal_actions 顺序来自 set/dict 迭代，不可复现；
            # Java 侧统一用 (长度, 逐元素) 排序，这里先规范化再编码，
            # 保证 x_batch 的行顺序两侧一致、可逐位比对。
            infoset.legal_actions = canonicalize(infoset.legal_actions)
            obs = get_obs(infoset)
        cases.append({
            'index': idx, 'position': pos, 'hand': hand, 'three': three,
            'records': [{'position': p, 'cards': c} for p, c in records],
            'player_position': infoset.player_position,
            'player_hand_cards': list(map(int, infoset.player_hand_cards)),
            'other_hand_cards': list(map(int, infoset.other_hand_cards)),
            'num_cards_left_dict': {k: int(v) for k, v in infoset.num_cards_left_dict.items()},
            'bomb_num': int(infoset.bomb_num),
            'last_pid': a.last_pid,
            'last_move': list(map(int, infoset.last_move)),
            'last_move_dict': {k: list(map(int, v)) for k, v in infoset.last_move_dict.items()},
            'played_cards': {k: list(map(int, v)) for k, v in infoset.played_cards.items()},
            'card_play_action_seq': [list(map(int, s)) for s in infoset.card_play_action_seq],
            'legal_actions': [list(map(int, m)) for m in infoset.legal_actions],
            'x_no_action': obs['x_no_action'].astype(np.int32).reshape(-1).tolist(),
            'x_batch': obs['x_batch'].astype('<f4').reshape(-1).tolist(),
            'z_batch': obs['z_batch'].astype('<f4').reshape(-1).tolist(),
            'x_batch_shape': list(obs['x_batch'].shape),
            'z_batch_shape': list(obs['z_batch'].shape),
        })
        print(f'  encode golden #{idx}: {pos:14s} legal={len(infoset.legal_actions):3d} '
              f'x_batch={obs["x_batch"].shape}')

    path = os.path.join(GOLDEN, 'layer_b_encode.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'cases': cases}, f)
    print(f'  -> {os.path.relpath(path, ROOT)}  ({os.path.getsize(path)/1024:.0f} KB)')
    return cases


if __name__ == '__main__':
    print('=' * 70)
    print('导出权重')
    print('=' * 70)
    export_weights()
    print()
    print('=' * 70)
    print('Layer A 黄金向量（forward 数值）')
    print('=' * 70)
    golden_forward()
    print()
    print('=' * 70)
    print('Layer B 黄金向量（特征编码）')
    print('=' * 70)
    golden_encode()
    print()
    print('完成。')
