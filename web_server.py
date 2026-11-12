# 斗地主军师 Web 服务：电脑跑 DouZero，手机浏览器访问
import sys, os, io, json, contextlib, socket
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from flask import Flask, request, jsonify
from ddz_assistant import DDZAssistant

ROOT = os.path.dirname(os.path.abspath(__file__))
CKPT = os.path.join(ROOT, 'douzero-baselines', 'checkpoints')

app = Flask(__name__)
_assistant = None

def _a():
    global _assistant
    if _assistant is None:
        _assistant = DDZAssistant(CKPT)
        _assistant.set_search(enabled=False)  # 单步模型，秒级响应
    return _assistant

def _run(cb):
    """捕获 stdout，执行回调，返回 (结果, 日志)"""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        r = cb()
    return r, buf.getvalue()

@app.route('/api/health', methods=['GET', 'POST'])
def health():
    """轻量连通性检查，不加载模型，供手机入口测试局域网连接。"""
    return jsonify(ok=True, service='ddz-ai-assistant')


@app.route('/api/start', methods=['POST'])
def start():
    d = request.json or {}
    a = _a()
    try:
        a.start_game(d.get('position', 'landlord'), d.get('hand', ''), d.get('three', ''))
    except Exception as e:
        return jsonify(ok=False, error=str(e))
    return jsonify(ok=True, hand=a.cards_to_display(a.my_hand_cards),
                   unseen=a.get_unseen_counts(), position=a.my_position)

@app.route('/api/record', methods=['POST'])
def record():
    d = request.json or {}
    a = _a()
    position = d.get('position', '')
    if position not in ('landlord', 'landlord_up', 'landlord_down'):
        return jsonify(ok=False, error='出牌位置无效')
    try:
        result = a.record_move(position, d.get('cards', ''))
    except Exception as e:
        return jsonify(ok=False, error=str(e))
    if result is False or result is None:
        if a.acting_player_position != position:
            error = f'当前应由{a._get_position_name(a.acting_player_position)}出牌'
        else:
            error = '出牌不合法，请检查牌面或当前手牌'
        return jsonify(ok=False, error=error)
    return jsonify(ok=True, game_over=(result == 'game_over'),
                   hand=a.cards_to_display(a.my_hand_cards),
                   next_position=a.acting_player_position,
                   can_undo=a.can_undo(),
                   unseen=a.get_unseen_counts())

@app.route('/api/recommend', methods=['POST'])
def recommend():
    a = _a()
    action, log = _run(lambda: a.recommend_move())
    if action is None:
        return jsonify(ok=False, error='还没轮到你出牌')
    display = a.cards_to_display(action) if action else '不出'
    return jsonify(ok=True, move=display, hand=a.cards_to_display(a.my_hand_cards),
                   unseen=a.get_unseen_counts(),
                   candidates=_candidates_payload(a),
                   meta=a.get_recommend_meta(),
                   can_undo=a.can_undo())


def _candidates_payload(a):
    """把候选出牌整理成前端可直接渲染的结构（缺字段填 None，不编造数值）。"""
    out = []
    for i, row in enumerate(a.get_candidates(), 1):
        out.append({
            'rank': i,
            'display': row.get('display', ''),
            'win_rate': row.get('win_rate'),
            'avg_util': row.get('avg_util'),
            'fused': row.get('fused'),
            'n': row.get('n'),
            'is_best': bool(row.get('is_best')),
            'score_text': row.get('score_text') or '',
        })
    return out


@app.route('/api/undo', methods=['POST'])
def undo():
    a = _a()
    if not a.can_undo():
        return jsonify(ok=False, error='没有可撤销的步骤')
    ok, _ = _run(lambda: a.undo())
    if not ok:
        return jsonify(ok=False, error='撤销失败')
    return jsonify(ok=True, hand=a.cards_to_display(a.my_hand_cards),
                   unseen=a.get_unseen_counts(),
                   next_position=a.acting_player_position,
                   can_undo=a.can_undo())

@app.route('/api/play', methods=['POST'])
def play():
    d = request.json or {}
    a = _a()
    try:
        result, _ = _run(lambda: a.play_move(d.get('cards', '')))
    except Exception as e:
        return jsonify(ok=False, error=str(e))
    if result is None or result is False:
        if a.acting_player_position != a.my_position:
            error = f'当前应由{a._get_position_name(a.acting_player_position)}出牌'
        else:
            error = '出牌不合法，请检查牌面或当前手牌'
        return jsonify(ok=False, error=error)
    return jsonify(ok=True, game_over=(result == 'game_over'),
                   next_position=a.acting_player_position,
                   hand=a.cards_to_display(a.my_hand_cards),
                   can_undo=a.can_undo(),
                   unseen=a.get_unseen_counts())

@app.route('/api/reset', methods=['POST'])
def reset():
    _a().reset()
    return jsonify(ok=True)

@app.route('/')
def index():
    with open(os.path.join(ROOT, 'web_index.html'), encoding='utf-8') as f:
        return f.read()

def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'

if __name__ == '__main__':
    print('斗地主军师 Web 服务启动')
    print(f'手机访问: http://{lan_ip()}:8899')
    app.run(host='0.0.0.0', port=8899, threaded=True)
