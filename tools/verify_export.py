# -*- coding: utf-8 -*-
"""校验导出的 .bin 权重与 meta 能逐位还原 PyTorch 张量。"""
import sys, os, json
import numpy as np
import torch

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'android-offline', 'app', 'src', 'main', 'assets')
CKPT = os.path.join(ROOT, 'douzero-baselines', 'checkpoints')

meta = json.load(open(os.path.join(ASSETS, 'ddz_meta.json'), encoding='utf-8'))
order = meta['tensorOrder']

fails = 0
total = 0
for key, m in meta['models'].items():
    obj, pos = key.split('/')
    blob = np.fromfile(os.path.join(ASSETS, m['file']), dtype='<f4')
    sd = torch.load(os.path.join(CKPT, f'douzero_{obj}', f'{pos}.ckpt'),
                    map_location='cpu', weights_only=True)
    if blob.size != m['params']:
        print(f'FAIL {key}: 元素数 {blob.size} != meta {m["params"]}'); fails += 1; continue
    ok = True
    for e in m['tensors']:
        src = sd[e['name']].detach().cpu().numpy().astype('<f4').reshape(-1)
        seg = blob[e['offset']: e['offset'] + e['count']]
        total += 1
        if src.size != e['count'] or not np.array_equal(src, seg):
            print(f'FAIL {key}/{e["name"]}: 不一致 (src={src.size} seg={seg.size})')
            ok = False; fails += 1
    if ok:
        print(f'  PASS  {key:22s} {m["params"]:>9,} params  {len(m["tensors"])} tensors  '
              f'{m["bytes"]/1024/1024:5.2f} MB')

print()
print(f'张量校验: {total - fails}/{total} 一致')
sys.exit(1 if fails else 0)
