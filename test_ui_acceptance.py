# -*- coding: utf-8 -*-
"""验收测试：验证 UI 改造后旧接口契约不变 + 新增接口可用。

阶段 A：以地主身份开局（地主先手）→ 验证开局校验、自己出牌、不出、撤销
阶段 B：以地主下家身份开局 → 验证记录对手出牌、轮次校验、撤销链
"""
import sys, os, io, contextlib

sys.stdout.reconfigure(encoding='utf-8')

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

results = []
def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('  PASS  ' if cond else '  FAIL  ') + name + (('  | ' + str(detail)) if detail else ''))

buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    import web_server as ws
    ws.app.config['TESTING'] = True
    c = ws.app.test_client()

HAND17 = '3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4'

# 旧接口字段集（改造前必须完全一致，不允许少字段）
OLD_START  = {'ok', 'hand', 'unseen', 'position'}
OLD_RECORD = {'ok', 'game_over', 'hand', 'next_position', 'unseen'}
OLD_PLAY   = {'ok', 'game_over', 'next_position', 'hand', 'unseen'}

print('=' * 74)
print('阶段 0：连通性')
print('=' * 74)
r = c.post('/api/health', json={})
check('/api/health 返回 ok', r.get_json().get('ok') is True, r.get_json())

print()
print('=' * 74)
print('阶段 A：地主身份（地主先手）')
print('=' * 74)

r = c.post('/api/start', json={'position': 'landlord', 'hand': HAND17, 'three': '9 9 2'})
d = r.get_json()
check('A1 /api/start ok', d.get('ok') is True, d.get('error'))
check('A2 /api/start 旧字段完整', OLD_START <= set(d), sorted(set(d)))
check('A3 地主手牌 20 张', len(d.get('hand', '').split()) == 20, d.get('hand'))
check('A4 unseen 覆盖 15 个点数', len(d.get('unseen') or {}) == 15, len(d.get('unseen') or {}))

# 地主先手 → 我出牌（record 自己位置等价于 play）
r = c.post('/api/record', json={'position': 'landlord', 'cards': '3 3'})
d = r.get_json()
check('A5 /api/record(自己) ok', d.get('ok') is True, d.get('error'))
check('A6 /api/record 旧字段完整', OLD_RECORD <= set(d), sorted(set(d)))
check('A7 /api/record 新增 can_undo', 'can_undo' in d, d.get('can_undo'))
check('A8 出牌后轮到地主下家', d.get('next_position') == 'landlord_down', d.get('next_position'))

# 撤销：手牌应回到 20 张
r = c.post('/api/undo', json={})
d = r.get_json()
check('A9 /api/undo ok', d.get('ok') is True, d.get('error'))
check('A10 撤销后手牌回到 20 张', len(d.get('hand', '').split()) == 20, d.get('hand'))
check('A11 撤销后轮次回到我', d.get('next_position') == 'landlord', d.get('next_position'))
check('A12 /api/undo 字段完整',
      {'ok', 'hand', 'unseen', 'next_position', 'can_undo'} <= set(d), sorted(set(d)))

# 领出方不能「不出」（斗地主规则：gen_moves() 不含 pass）——必须被拒绝
r = c.post('/api/record', json={'position': 'landlord', 'cards': ''})
check('A13 领出方不能不出（规则正确）', r.get_json().get('ok') is False,
      r.get_json().get('error'))

# 合法领出
r = c.post('/api/record', json={'position': 'landlord', 'cards': '5'})
check('A13b 领出合法牌 ok', r.get_json().get('ok') is True, r.get_json().get('error'))

# 轮次错误必须仍被拒绝（现在轮到 landlord_down）
r = c.post('/api/record', json={'position': 'landlord_up', 'cards': '6'})
check('A14 轮次错误被拒', r.get_json().get('ok') is False, r.get_json().get('error'))

# 非法位置必须仍被拒绝
r = c.post('/api/record', json={'position': 'nobody', 'cards': '3'})
check('A15 非法位置被拒', r.get_json().get('ok') is False, r.get_json().get('error'))

# 手牌张数不足必须报错
r = c.post('/api/start', json={'position': 'landlord', 'hand': '3 4 5', 'three': '9 9 2'})
check('A16 手牌不足报错', r.get_json().get('ok') is False, r.get_json().get('error'))

# 地主缺底牌必须报错
r = c.post('/api/start', json={'position': 'landlord', 'hand': HAND17, 'three': ''})
check('A17 地主缺底牌报错', r.get_json().get('ok') is False, r.get_json().get('error'))

print()
print('=' * 74)
print('阶段 B：地主下家身份（地主先手 → 我记录对手）')
print('=' * 74)

# B0：连续开局回归 —— 上一局结束时轮次停在别处，新开局必须回到地主先手
r = c.post('/api/start', json={'position': 'landlord', 'hand': HAND17, 'three': '9 9 2'})
c.post('/api/record', json={'position': 'landlord', 'cards': '5'})   # 轮次推到 landlord_down
r = c.post('/api/start', json={'position': 'landlord_down', 'hand': HAND17, 'three': '9 9 2'})
d0 = r.get_json()
check('B0a 连续开局 ok', d0.get('ok') is True, d0.get('error'))
check('B0b 新开局轮次回到地主先手', d0.get('position') == 'landlord_down', d0.get('position'))
r = c.post('/api/record', json={'position': 'landlord', 'cards': '5'})
check('B0c 新开局后能记录地主出牌（不继承旧轮次）',
      r.get_json().get('ok') is True, r.get_json().get('error'))

# 失败的开局不得破坏当前对局（B0c 后轮到 landlord_down，地主出的是 5，需出更大的牌）
r = c.post('/api/start', json={'position': 'landlord', 'hand': '3 4 5', 'three': '9 9 2'})
check('B0d 非法开局被拒', r.get_json().get('ok') is False, r.get_json().get('error'))
r = c.post('/api/record', json={'position': 'landlord_down', 'cards': '6'})
check('B0e 失败开局未破坏当前对局', r.get_json().get('ok') is True, r.get_json().get('error'))
r = c.post('/api/record', json={'position': 'landlord_down', 'cards': '4'})
check('B0f 小于上家的牌仍被拒（规则正确）', r.get_json().get('ok') is False,
      r.get_json().get('error'))

# 正式进入阶段 B
r = c.post('/api/start', json={'position': 'landlord_down', 'hand': HAND17, 'three': '9 9 2'})
d = r.get_json()
check('B1 农民开局 ok', d.get('ok') is True, d.get('error'))
check('B2 农民手牌 17 张', len(d.get('hand', '').split()) == 17, d.get('hand'))

# 记录地主出牌
r = c.post('/api/record', json={'position': 'landlord', 'cards': '5'})
d = r.get_json()
check('B3 记录地主出牌 ok', d.get('ok') is True, d.get('error'))
check('B4 记录后轮到我', d.get('next_position') == 'landlord_down', d.get('next_position'))
check('B5 can_undo=True', d.get('can_undo') is True, d.get('can_undo'))

# 推荐（新字段）
r = c.post('/api/recommend', json={})
d = r.get_json()
check('B6 /api/recommend ok', d.get('ok') is True, d.get('error'))
check('B7 recommend 旧字段完整', {'ok', 'move', 'hand', 'unseen'} <= set(d), sorted(set(d)))
check('B8 新增 candidates 为列表', isinstance(d.get('candidates'), list), len(d.get('candidates') or []))
check('B9 新增 meta 为 dict', isinstance(d.get('meta'), dict), d.get('meta'))
cands = d.get('candidates') or []
if cands:
    first = cands[0]
    need = {'rank', 'display', 'win_rate', 'avg_util', 'fused', 'n', 'is_best', 'score_text'}
    check('B10 候选字段完整', need <= set(first), sorted(first))
    check('B11 候选首位 is_best', first.get('is_best') is True)
    check('B12 候选 display 非空', bool(first.get('display')), first.get('display'))
    check('B13 候选 rank 从 1 递增',
          [x.get('rank') for x in cands] == list(range(1, len(cands) + 1)),
          [x.get('rank') for x in cands])
    check('B14 只有一个 is_best', sum(1 for x in cands if x.get('is_best')) == 1)
else:
    check('B10 候选非空', False, 'candidates 为空')

# 出牌
move = d.get('move')
r = c.post('/api/play', json={'cards': '' if move == '不出' else move})
d2 = r.get_json()
check('B15 /api/play ok', d2.get('ok') is True, d2.get('error'))
check('B16 play 旧字段完整', OLD_PLAY <= set(d2), sorted(set(d2)))
check('B17 play 新增 can_undo', 'can_undo' in d2, d2.get('can_undo'))

# 撤销我出的牌。注意：若推荐是「不出」，手牌本就不变，属正常
hand_after_play = d2.get('hand')
played_pass = (move == '不出')
r = c.post('/api/undo', json={})
d3 = r.get_json()
check('B18 撤销我的出牌 ok', d3.get('ok') is True, d3.get('error'))
if played_pass:
    check('B19 出「不出」时手牌不变（正确）', d3.get('hand') == hand_after_play,
          f"{hand_after_play} -> {d3.get('hand')}")
else:
    check('B19 手牌已回滚', d3.get('hand') != hand_after_play,
          f"{hand_after_play} -> {d3.get('hand')}")
check('B20 轮次回到我', d3.get('next_position') == 'landlord_down', d3.get('next_position'))

# 再撤销 → 回滚地主的出牌记录
r = c.post('/api/undo', json={})
d4 = r.get_json()
check('B21 连续撤销 ok', d4.get('ok') is True, d4.get('error'))
check('B22 轮次回到地主', d4.get('next_position') == 'landlord', d4.get('next_position'))

# 空栈必须安全报错
last = None
for _ in range(12):
    last = c.post('/api/undo', json={}).get_json()
check('B23 空栈安全报错', last.get('ok') is False and '撤销' in (last.get('error') or ''),
      last.get('error'))

# 撤销后 unseen 应恢复（与开局一致）
r = c.post('/api/start', json={'position': 'landlord_down', 'hand': HAND17, 'three': '9 9 2'})
base = r.get_json().get('unseen')
r = c.post('/api/record', json={'position': 'landlord', 'cards': '5'})
after = r.get_json().get('unseen')
check('B24a 记录后记牌器变化', after != base,
      {k: (base.get(k), after.get(k)) for k in base if base.get(k) != after.get(k)})
r = c.post('/api/undo', json={})
back = r.get_json().get('unseen')
check('B24b 撤销后记牌器复原', back == base,
      {k: (base.get(k), (back or {}).get(k)) for k in base if base.get(k) != (back or {}).get(k)})
check('B24c 撤销响应含 unseen', isinstance(back, dict) and len(back) == 15, type(back).__name__)

print()
print('=' * 74)
print('阶段 C：页面与重置')
print('=' * 74)

r = c.post('/api/reset', json={})
check('C1 /api/reset ok', r.get_json().get('ok') is True)

r = c.get('/')
html = r.get_data(as_text=True)
check('C2 GET / 返回 200', r.status_code == 200, r.status_code)
check('C3 含设计令牌', '--gold' in html and '--fg-hint' in html)
check('C4 含记牌器网格', 'counter' in html and 'cbox' in html)
check('C5 含撤销入口', 'undoMove' in html)
check('C6 含候选渲染', 'renderCandidates' in html)
check('C7 含手牌 chip 渲染', 'renderChips' in html and 'chipEl' in html)
check('C8 无 emoji 结构图标',
      not any(e in html for e in ['🃏', '🚀', '⚙️', '🎨', '✅', '❌', '🎯']))
check('C9 尊重减弱动效', 'prefers-reduced-motion' in html)
check('C10 状态区 aria-live', 'aria-live' in html)
check('C11 分段按钮 aria-pressed', 'aria-pressed' in html)
check('C12 触控令牌 48px', '--tap: 48px' in html)
check('C13 输入框有关联 label', 'for="handInput"' in html and 'for="recInput"' in html)
check('C14 含 safe-area 适配', 'safe-area-inset-top' in html)

print()
print('=' * 74)
passed = sum(1 for _, ok, _ in results if ok)
failed = [n for n, ok, _ in results if not ok]
print(f'结果: {passed}/{len(results)} 通过')
if failed:
    print('失败项:')
    for n in failed:
        print('  - ' + n)
print('=' * 74)
sys.exit(1 if failed else 0)
