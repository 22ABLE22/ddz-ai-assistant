// 斗地主军师 Android 悬浮窗（手机触控版）
// 用法：电脑运行 web_server.py，首次打开设置填写电脑局域网地址。
var DEFAULT_SERVER = "http://192.168.5.81:8899";
var storage = storages.create("ddz_assistant_float");
var SERVER = String(storage.get("server", DEFAULT_SERVER)).replace(/\/$/, "");
var pos = String(storage.get("position", "landlord"));
var recPos = String(storage.get("recordPosition", "landlord"));
var folded = false;
var busy = false;
var w;

function toastSafe(message) {
    try { toast(String(message)); } catch (e) {}
}

function setStatus(message, error) {
    if (!w || !w.status) return;
    w.status.setText(String(message));
    try { w.status.setTextColor(error ? "#ff8a80" : "#ffe082"); } catch (e) {}
}

function normalizeServer(value) {
    var text = String(value || "").trim().replace(/\/$/, "");
    if (!/^https?:\/\//i.test(text)) text = "http://" + text;
    return text;
}

function api(path, body) {
    try {
        var res = http.post(SERVER + path, {
            headers: { "Content-Type": "application/json", "Accept": "application/json" },
            body: JSON.stringify(body || {}),
            timeout: 10000
        });
        if (!res || res.statusCode < 200 || res.statusCode >= 300) {
            return { ok: false, error: "服务器响应 " + (res ? res.statusCode : "未知") };
        }
        var data = res.body.json();
        return data || { ok: false, error: "服务器返回为空" };
    } catch (e) {
        return { ok: false, error: "连接失败，请检查电脑和手机是否在同一 Wi-Fi" };
    }
}

function runRequest(action, onSuccess) {
    if (busy) return;
    busy = true;
    setStatus("处理中…");
    try {
        var result = action();
        if (!result || !result.ok) {
            setStatus((result && result.error) || "操作失败", true);
            return;
        }
        onSuccess(result);
    } catch (e) {
        setStatus("操作失败: " + e, true);
    } finally {
        busy = false;
    }
}

function showUnseen(unseen) {
    if (!unseen) { w.unseen.setText("未开始"); return; }
    var order = ["3","4","5","6","7","8","9","10","J","Q","K","A","2","X","D"];
    w.unseen.setText(order.map(function(k) { return k + ":" + (unseen[k] || 0); }).join("  "));
}

function selectPosition(value) {
    pos = value;
    storage.put("position", value);
    w.bLandlord.setText(value === "landlord" ? "✓ 地主" : "地主");
    w.bDown.setText(value === "landlord_down" ? "✓ 下家" : "下家");
    w.bUp.setText(value === "landlord_up" ? "✓ 上家" : "上家");
    setStatus(value === "landlord" ? "身份：地主" : value === "landlord_down" ? "身份：地主下家" : "身份：地主上家");
}

function positionName(value) {
    return value === "landlord" ? "地主" : value === "landlord_down" ? "下家" : "上家";
}

function setTurn(value) {
    recPos = value;
    if (w && w.turn) w.turn.setText("当前出牌：" + positionName(value) + (value === pos ? "（我）" : ""));
}

function saveServer() {
    var value = normalizeServer(w.server.getText());
    if (!value || value === "http://") {
        setStatus("请填写电脑地址", true);
        return;
    }
    SERVER = value;
    storage.put("server", SERVER);
    w.server.setText(SERVER);
    runRequest(function() { return api("/api/health", {}); }, function() {
        w.config.setVisibility(8);
        setStatus("地址已保存，连接正常");
    });
}

function toggleConfig() {
    var hidden = w.config.getVisibility() === 8;
    w.config.setVisibility(hidden ? 0 : 8);
    if (hidden) w.server.setText(SERVER);
}

try {
    if (typeof floaty.checkPermission === "function" && !floaty.checkPermission()) {
        toastSafe("请允许悬浮窗权限后重新运行");
        floaty.requestPermission();
        sleep(1200);
    }
} catch (e) {}

try {
    w = floaty.rawWindow(
        <frame gravity="top|right">
            <vertical id="root" bg="#e60e3d2e" w="310" padding="1">
                <horizontal id="titleBar" bg="#16543f" h="52" padding="6" gravity="center_vertical">
                    <text id="title" text="🃏 军师" textColor="#ffe082" textSize="16" gravity="center_vertical" layout_weight="1" />
                    <button id="configBtn" text="⚙" textSize="15" w="48" h="44" />
                    <button id="foldBtn" text="▾" textSize="15" w="48" h="44" />
                    <button id="closeBtn" text="×" textSize="18" w="48" h="44" />
                </horizontal>
                <vertical id="body" padding="8">
                    <vertical id="config" visibility="gone" bg="#0b2f22" padding="6">
                        <text text="电脑服务地址" textColor="#a5d6c4" textSize="12" />
                        <horizontal gravity="center_vertical">
                            <input id="server" hint="例如 192.168.1.8:8899" textSize="13" singleLine="true" layout_weight="1" />
                            <button id="saveBtn" text="保存" textSize="12" w="62" h="44" />
                        </horizontal>
                    </vertical>
                    <text id="status" text="未开局" textColor="#ffe082" textSize="13" paddingTop="5" paddingBottom="5" />
                    <horizontal>
                        <button id="bLandlord" text="地主" textSize="12" h="44" layout_weight="1" />
                        <button id="bDown" text="下家" textSize="12" h="44" layout_weight="1" />
                        <button id="bUp" text="上家" textSize="12" h="44" layout_weight="1" />
                    </horizontal>
                    <input id="hand" hint="手牌 17 张，如 34567JJQQKKAA2X" textSize="13" singleLine="true" h="48" />
                    <input id="three" hint="底牌 3 张（地主必填）" textSize="13" singleLine="true" h="48" />
                    <button id="startBtn" text="开始对局" textSize="14" h="48" />
                    <button id="resetBtn" text="重置本局" textSize="12" h="42" />
                    <text id="turn" text="当前出牌：地主" textColor="#a5d6c4" textSize="12" paddingTop="6" />
                    <horizontal gravity="center_vertical">
                        <input id="rec" hint="本轮出的牌；不出点“过”" textSize="13" singleLine="true" h="48" layout_weight="1" />
                        <button id="recBtn" text="记录" textSize="12" w="70" h="48" />
                        <button id="recPass" text="过" textSize="12" w="52" h="48" />
                    </horizontal>
                    <text id="move" text="" textColor="#ffe082" textSize="19" gravity="center" paddingTop="8" paddingBottom="8" />
                    <horizontal>
                        <button id="recommendBtn" text="推荐出牌" textSize="13" h="48" layout_weight="1" />
                        <button id="adoptBtn" text="采用" textSize="13" h="48" layout_weight="1" />
                        <button id="passBtn" text="不出" textSize="13" h="48" layout_weight="1" />
                    </horizontal>
                    <text id="hand2" text="手牌：—" textColor="#ffe082" textSize="12" paddingTop="6" />
                    <text id="unseen" text="未开始" textColor="#a5d6c4" textSize="10" paddingTop="4" />
                </vertical>
            </vertical>
        </frame>
    );
} catch (e) {
    toastSafe("悬浮窗创建失败：" + e);
    exit();
}

// 初始化已保存设置和身份。
w.server.setText(SERVER);
selectPosition(pos);
setTurn("landlord");
w.config.setVisibility(8);

// 标题栏拖动：触控区域足够大，不影响按钮点击。
var downX = 0, downY = 0, winX = 0, winY = 0;
w.title.setOnTouchListener(function(view, event) {
    var action = event.getAction();
    if (action === 0) {
        downX = event.getRawX(); downY = event.getRawY();
        winX = w.getX(); winY = w.getY();
        return true;
    }
    if (action === 2) {
        w.setPosition(winX + (event.getRawX() - downX), winY + (event.getRawY() - downY));
        return true;
    }
    return true;
});

w.configBtn.click(toggleConfig);
w.saveBtn.click(saveServer);
w.foldBtn.click(function() {
    folded = !folded;
    w.body.setVisibility(folded ? 8 : 0);
    w.configBtn.setVisibility(folded ? 8 : 0);
    w.foldBtn.setText(folded ? "▸" : "▾");
    w.title.setText(folded ? "🃏" : "🃏 军师");
    try { w.setSize(folded ? 58 : 310, 54); } catch (e) {}
});
w.closeBtn.click(function() { w.close(); });
w.bLandlord.click(function() { selectPosition("landlord"); });
w.bDown.click(function() { selectPosition("landlord_down"); });
w.bUp.click(function() { selectPosition("landlord_up"); });

w.resetBtn.click(function() {
    runRequest(function() { return api("/api/reset", {}); }, function() {
        setTurn("landlord");
        w.move.setText("");
        w.hand2.setText("手牌：—");
        showUnseen(null);
        setStatus("已重置，请重新输入手牌和底牌");
    });
});

w.startBtn.click(function() {
    var hand = w.hand.getText().toString().trim();
    var three = w.three.getText().toString().trim();
    runRequest(function() { return api("/api/start", { position: pos, hand: hand, three: three }); }, function(r) {
        setStatus(r.next_position === pos ? "轮到你出牌，可请求推荐" : "对局已开始，请记录本轮出牌");
        setTurn(r.next_position || "landlord");
        w.hand2.setText("手牌：" + (r.hand || "—"));
        showUnseen(r.unseen);
    });
});

function doRecord(cards) {
    runRequest(function() { return api("/api/record", { position: recPos, cards: cards }); }, function(r) {
        w.rec.setText("");
        setTurn(r.next_position || "landlord");
        w.hand2.setText("手牌：" + (r.hand || "—"));
        showUnseen(r.unseen);
        if (r.game_over) setStatus("本局结束");
        else setStatus(r.next_position === pos ? "轮到你了，点“推荐出牌”" : "已记录，下一手：" + positionName(r.next_position));
    });
}
w.recBtn.click(function() { doRecord(w.rec.getText().toString().trim()); });
w.recPass.click(function() { doRecord(""); });

w.recommendBtn.click(function() {
    runRequest(function() { return api("/api/recommend", {}); }, function(r) {
        w.move.setText("推荐：" + r.move);
        w.hand2.setText("手牌：" + (r.hand || "—"));
        showUnseen(r.unseen);
        setStatus("请确认推荐后采用");
    });
});
w.adoptBtn.click(function() {
    var text = w.move.getText().toString().replace("推荐：", "").trim();
    if (!text) { setStatus("请先点推荐出牌", true); return; }
    runRequest(function() { return api("/api/play", { cards: text === "不出" ? "" : text }); }, function(r) {
        w.move.setText("");
        setTurn(r.next_position || "landlord");
        w.hand2.setText("手牌：" + (r.hand || "—"));
        showUnseen(r.unseen);
        setStatus(r.game_over ? "本局结束" : "已记录你的出牌，下一手：" + positionName(r.next_position));
    });
});
w.passBtn.click(function() {
    runRequest(function() { return api("/api/play", { cards: "" }); }, function(r) {
        w.move.setText("");
        setTurn(r.next_position || "landlord");
        w.hand2.setText("手牌：" + (r.hand || "—"));
        showUnseen(r.unseen);
        setStatus(r.game_over ? "本局结束" : "已记录我不出，下一手：" + positionName(r.next_position));
    });
});

toastSafe("军师已启动：拖动标题栏，点 ▾ 可收起");
setInterval(function() {}, 1000);
