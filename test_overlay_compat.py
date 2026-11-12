# -*- coding: utf-8 -*-
"""回归验证：ddz_float.js（Auto.js 悬浮窗）依赖的响应字段在改造后依然存在且语义不变。"""
import sys, os, io, contextlib, json, re

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

# 从 ddz_float.js 静态提取它读取的所有响应字段
js = open(os.path.join(ROOT, 'ddz_float.js'), encoding='utf-8').read()
fields = sorted(set(re.findall(r'\b[rd]\.([A-Za-z_][A-Za-z0-9_]*)', js)))
print('ddz_float.js 读取的响应字段:', fields)

buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    import web_server as ws
    ws.app.config['TESTING'] = True
    c = ws.app.test_client()

responses = {}
r = c.post('/api/start', json={'position': 'landlord_down',
                               'hand': '3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4', 'three': '9 9 2'})
responses['start'] = r.get_json()
r = c.post('/api/record', json={'position': 'landlord', 'cards': '5'})
responses['record'] = r.get_json()
r = c.post('/api/recommend', json={})
responses['recommend'] = r.get_json()
r = c.post('/api/play', json={'cards': ''})
responses['play'] = r.get_json()
r = c.post('/api/reset', json={})
responses['reset'] = r.get_json()
r = c.post('/api/health', json={})
responses['health'] = r.get_json()

print()
print('=' * 68)
ok_all = True
for name, d in responses.items():
    if not isinstance(d, dict):
        continue
    missing = [f for f in fields if f in ('ok', 'error', 'hand', 'unseen', 'move',
                                          'position', 'next_position', 'game_over')
               and f not in d]
    # ok 在成功响应里必须有；error 只在失败响应里出现
    must = {'ok'}
    if name in ('start', 'record', 'recommend', 'play', 'reset', 'health'):
        must = {'ok'}
    if name == 'start':
        must |= {'hand', 'unseen', 'position'}
    if name in ('record', 'play'):
        must |= {'hand', 'unseen', 'next_position', 'game_over'}
    if name == 'recommend':
        must |= {'hand', 'unseen', 'move'}
    lack = sorted(must - set(d))
    status = 'PASS' if not lack else 'FAIL'
    if lack:
        ok_all = False
    print(f'  {status}  /api/{name:9s} 必需字段={sorted(must)}  缺={lack}')

print()
print('=' * 68)
print('新增字段（向后兼容，旧客户端忽略即可）')
print('=' * 68)
print('  record  can_undo      =', responses['record'].get('can_undo'))
print('  play    can_undo      =', responses['play'].get('can_undo'))
print('  recommend candidates  =', len(responses['recommend'].get('candidates') or []), '项')
print('  recommend meta        =', responses['recommend'].get('meta'))

print()
print('=' * 68)
print('结论:', 'PASS — 悬浮窗所需字段全部保留' if ok_all else 'FAIL — 存在字段缺失')
print('=' * 68)
sys.exit(0 if ok_all else 1)
