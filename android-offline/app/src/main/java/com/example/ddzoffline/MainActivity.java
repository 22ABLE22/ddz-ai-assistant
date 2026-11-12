package com.example.ddzoffline;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主界面：纯手机离线运行，无需电脑。
 *
 * 布局与网页版保持同一套设计令牌（深绿牌桌 + 金色强调），
 * 并把「记牌器」「候选方案」「撤销」都做进原生 UI。
 */
public final class MainActivity extends Activity {

    // ---- 设计令牌（与 web_index.html 一致）----
    private static final int C_BG = Color.rgb(14, 61, 46);
    private static final int C_CARD = Color.rgb(22, 84, 63);
    private static final int C_DEEP = Color.rgb(11, 47, 34);
    private static final int C_BORDER = Color.rgb(42, 122, 94);
    private static final int C_GOLD = Color.rgb(255, 213, 79);
    private static final int C_GOLD_INK = Color.rgb(26, 26, 26);
    private static final int C_TEAL = Color.rgb(0, 121, 107);
    private static final int C_RED = Color.rgb(183, 28, 28);
    private static final int C_FG = Color.WHITE;
    private static final int C_MUTED = Color.rgb(165, 214, 196);
    private static final int C_HINT = Color.rgb(154, 217, 191);
    private static final int C_DANGER = Color.rgb(255, 180, 171);

    private DdzEngine engine;
    private HandStore handStore;

    private EditText handInput, threeInput, playedInput;
    private TextView statusText, turnTag, handCountHint;
    private TextView moveLabel;
    private LinearLayout moveChips, handChips, counterGrid, candList;
    private LinearLayout adoptRow, candBox;
    private LinearLayout searchBox, searchList;
    private LinearLayout watchList;
    private TextView watchStatus;
    private Button btnUndo, btnWp, btnAdp, btnClassic, btnLaizi, btnDeep;
    private CardPickerView handPicker, playPicker;
    private LinearLayout presetBox;
    private TextView modeHint;

    private String position = DdzEngine.LANDLORD;
    private String recPos = DdzEngine.DOWN;
    private DdzEngine.Recommendation current;
    private DdzEngine.Candidate selected;
    private boolean waitingOverlayPermission;
    private int laiziRank = -1;          // -1 = 经典模式
    private boolean inputByPicker = true;
    private LinearLayout laiziRow;
    private final List<Button> laiziButtons = new ArrayList<>();

    private final List<Button> posButtons = new ArrayList<>();
    private final List<Button> recButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = GameState.engine(this);
        handStore = new HandStore(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = false;
            startOverlayService();
        }
        // 用户可能刚在系统设置里开了无障碍服务，回来刷新状态
        renderWatch();
    }

    // ================= UI 构建 =================

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(28));

        // 标题栏
        LinearLayout bar = row();
        TextView title = text("斗地主军师", 20, C_GOLD);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button reset = pillButton("新对局", C_BORDER, C_FG);
        bar.addView(reset, new LinearLayout.LayoutParams(dp(88), dp(48)));
        reset.setOnClickListener(v -> resetGame());
        root.addView(bar);

        TextView subtitle = text("纯手机运行 · 本地 DouZero 模型 · 无需电脑", 12, C_HINT);
        root.addView(subtitle, matchWrap());

        // 状态条
        statusText = text("等待开局", 14, C_MUTED);
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusText.setBackground(rounded(C_DEEP, C_BORDER, 8));
        root.addView(statusText, matchWrap());

        // ---------- 开局 ----------
        LinearLayout openCard = card();
        openCard.addView(sectionTitle("开局", ""));
        openCard.addView(label("我的身份"));
        LinearLayout posRow = row();
        for (String[] p : new String[][]{{DdzEngine.LANDLORD, "我是地主"},
                {DdzEngine.DOWN, "地主下家"}, {DdzEngine.UP, "地主上家"}}) {
            Button b = pillButton(p[1], C_BORDER, C_MUTED);
            final String key = p[0];
            b.setOnClickListener(v -> { position = key; syncPosButtons(); });
            posButtons.add(b);
            posRow.addView(b, weight());
        }
        openCard.addView(posRow, matchWrap());

        openCard.addView(label("我的手牌（点选牌面，不必打字）"));
        handPicker = new CardPickerView(this);
        handPicker.setMaxCards(20);
        handPicker.setOnChangeListener(cards -> {
            if (inputByPicker) {
                handInput.setText(cards.length == 0 ? "" : HandStore.toCompact(cards));
            }
            updateHandHint();
        });
        openCard.addView(handPicker, matchWrap());

        // 保留键盘输入作为备选路径（与点选共用同一套解析）
        TextView toggleText = text("展开键盘输入（备选）", 12, C_HINT);
        toggleText.setPadding(0, dp(6), 0, 0);
        openCard.addView(toggleText, matchWrap());
        handInput = input("例如 34567JJQQKKAA2XD34，或 3-7 J*3");
        handInput.setVisibility(View.GONE);
        openCard.addView(handInput, matchWrap());
        toggleText.setOnClickListener(v -> {
            boolean show = handInput.getVisibility() != View.VISIBLE;
            handInput.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleText.setText(show ? "收起键盘输入" : "展开键盘输入（备选）");
        });
        handInput.addTextChangedListener(new SimpleWatcher(() -> {
            if (handInput.getVisibility() != View.VISIBLE) return;
            // 键盘输入时反向同步到点选器，保证两条路径状态一致
            inputByPicker = false;
            int[] cards = com.example.ddzoffline.ai.CardInput.parse(
                    handInput.getText().toString()).toArray();
            handPicker.setCards(HandStore.toCompact(cards));
            inputByPicker = true;
            updateHandHint();
        }));
        handCountHint = text("点选牌面即可；需要 17 张，地主会自动并入 3 张底牌成为 20 张。", 12, C_HINT);
        openCard.addView(handCountHint, matchWrap());

        // 从截图读手牌：只读自己的手牌（纯输入自动化，不涉及任何隐藏信息），
        // 省掉开局 17-20 张的点选。取图用 ACTION_OPEN_DOCUMENT，不需要任何权限。
        Button fromShot = pillButton("从截图读手牌", C_BORDER, C_FG);
        fromShot.setOnClickListener(v -> openHandCapture());
        LinearLayout.LayoutParams fsLp = matchWrap();
        fsLp.topMargin = dp(8);
        openCard.addView(fromShot, fsLp);

        // 预设与历史
        presetBox = new LinearLayout(this);
        presetBox.setOrientation(LinearLayout.VERTICAL);
        openCard.addView(presetBox, matchWrap());

        LinearLayout presetActions = row();
        Button savePreset = pillButton("保存为预设", C_BORDER, C_FG);
        Button clearHistory = pillButton("清空历史", C_BORDER, C_MUTED);
        savePreset.setOnClickListener(v -> savePreset());
        clearHistory.setOnClickListener(v -> { handStore.clearHistory(); renderPresets(); });
        presetActions.addView(savePreset, weight());
        presetActions.addView(clearHistory, weight());
        openCard.addView(presetActions, matchWrap());

        openCard.addView(label("底牌（地主必填 3 张）"));
        threeInput = input("如 992");
        openCard.addView(threeInput, matchWrap());

        // ---- 玩法：经典 / 赖子 ----
        openCard.addView(label("玩法"));
        LinearLayout modeRow = row();
        btnClassic = pillButton("经典模式", C_BORDER, C_MUTED);
        btnLaizi = pillButton("赖子模式", C_BORDER, C_MUTED);
        btnClassic.setOnClickListener(v -> switchMode(-1));
        btnLaizi.setOnClickListener(v -> switchMode(7));
        modeRow.addView(btnClassic, weight());
        modeRow.addView(btnLaizi, weight());
        openCard.addView(modeRow, matchWrap());

        modeHint = text("", 12, C_HINT);
        openCard.addView(modeHint, matchWrap());

        // 赖子点数选择（仅在赖子模式下显示）
        LinearLayout laiziRow = row();
        laiziRow.setId(android.R.id.widget_frame);
        for (final int r : new int[]{3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17}) {
            Button b = pillButton(com.example.ddzoffline.ai.Types.label(r), C_BORDER, C_MUTED);
            b.setTextSize(13);
            b.setOnClickListener(v -> { laiziRank = r; applyRules(); renderPresets(); });
            laiziRow.addView(b, weight());
            laiziButtons.add(b);
        }
        laiziRow.setVisibility(View.GONE);
        openCard.addView(laiziRow, matchWrap());
        laiziRow.setTag("laiziRow");
        this.laiziRow = laiziRow;

        Button start = pillButton("开始对局", C_GOLD, C_GOLD_INK);
        LinearLayout.LayoutParams startLp = matchWrap();
        startLp.topMargin = dp(12);
        openCard.addView(start, startLp);
        start.setOnClickListener(v -> startGame());

        // 模型切换
        openCard.addView(label("推荐模型"));
        LinearLayout modelRow = row();
        btnWp = pillButton("WP 胜率优先", C_BORDER, C_MUTED);
        btnAdp = pillButton("ADP 平均分差", C_BORDER, C_MUTED);
        btnWp.setOnClickListener(v -> switchModel("WP"));
        btnAdp.setOnClickListener(v -> switchModel("ADP"));
        modelRow.addView(btnWp, weight());
        modelRow.addView(btnAdp, weight());
        openCard.addView(modelRow, matchWrap());
        syncModelButtons();
        root.addView(openCard, matchWrap());

        // ---------- 对局 ----------
        LinearLayout playCard = card();
        turnTag = text("", 12, C_HINT);
        playCard.addView(sectionTitle("对局", ""));

        playCard.addView(label("谁出的牌"));
        LinearLayout recRow = row();
        for (String[] p : new String[][]{{DdzEngine.LANDLORD, "地主"},
                {DdzEngine.DOWN, "地主下家"}, {DdzEngine.UP, "地主上家"}}) {
            Button b = pillButton(p[1], C_BORDER, C_MUTED);
            final String key = p[0];
            b.setOnClickListener(v -> { recPos = key; syncRecButtons(); });
            recButtons.add(b);
            recRow.addView(b, weight());
        }
        playCard.addView(recRow, matchWrap());

        playCard.addView(label("出的牌（留空表示不出）"));
        playPicker = new CardPickerView(this);
        playPicker.setMaxCards(20);
        playPicker.setOnChangeListener(cards -> {
            if (cards.length > 0) {
                playedInput.setText(HandStore.toCompact(cards));
            }
        });
        playCard.addView(playPicker, matchWrap());
        playedInput = input("也可用键盘输入，如 33 44");
        playedInput.setVisibility(View.GONE);
        playCard.addView(playedInput, matchWrap());
        TextView togglePlay = text("展开键盘输入（备选）", 12, C_HINT);
        playCard.addView(togglePlay, matchWrap());
        togglePlay.setOnClickListener(v -> {
            boolean show = playedInput.getVisibility() != View.VISIBLE;
            playedInput.setVisibility(show ? View.VISIBLE : View.GONE);
            togglePlay.setText(show ? "收起键盘输入" : "展开键盘输入（备选）");
        });

        LinearLayout actRow = row();
        Button record = pillButton("记录出牌", C_TEAL, C_FG);
        Button recommend = pillButton("推荐出牌", C_BORDER, C_FG);
        record.setOnClickListener(v -> recordMove());
        recommend.setOnClickListener(v -> doRecommend());
        actRow.addView(record, weight());
        actRow.addView(recommend, weight());
        LinearLayout.LayoutParams actLp = matchWrap();
        actLp.topMargin = dp(10);
        playCard.addView(actRow, actLp);

        // 深度搜索：在单步打分的候选上做蒙特卡洛仿真，用「胜率」而非单步价值选牌。
        // 能处理「赖子该不该现在用掉」这类需要跨步推理的决策。
        btnDeep = pillButton("深度搜索（仿真）", C_BORDER, C_FG);
        btnDeep.setBackground(rounded(0x00000000, C_GOLD, 8));
        btnDeep.setOnClickListener(v -> doDeepSearch());
        LinearLayout.LayoutParams deepLp = matchWrap();
        deepLp.topMargin = dp(8);
        playCard.addView(btnDeep, deepLp);

        btnUndo = pillButton("撤销上一步", 0x00000000, C_MUTED);
        btnUndo.setBackground(rounded(0x00000000, C_BORDER, 8));
        btnUndo.setEnabled(false);
        btnUndo.setOnClickListener(v -> undoMove());
        LinearLayout.LayoutParams undoLp = matchWrap();
        undoLp.topMargin = dp(8);
        playCard.addView(btnUndo, undoLp);

        // 推荐结果
        moveLabel = text("推荐出牌", 12, C_HINT);
        LinearLayout.LayoutParams mlLp = matchWrap();
        mlLp.topMargin = dp(12);
        playCard.addView(moveLabel, mlLp);
        moveChips = new LinearLayout(this);
        moveChips.setOrientation(LinearLayout.VERTICAL);
        playCard.addView(moveChips, matchWrap());

        // 候选
        candBox = new LinearLayout(this);
        candBox.setOrientation(LinearLayout.VERTICAL);
        candBox.setVisibility(View.GONE);
        candList = new LinearLayout(this);
        candList.setOrientation(LinearLayout.VERTICAL);
        TextView candTitle = text("候选方案（点选后「采用推荐」）", 12, C_HINT);
        LinearLayout.LayoutParams ctLp = matchWrap();
        ctLp.topMargin = dp(10);
        candBox.addView(candTitle, ctLp);
        candBox.addView(candList, matchWrap());
        playCard.addView(candBox, matchWrap());

        // 深度搜索结果（仿真胜率明细）
        searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.VERTICAL);
        searchBox.setVisibility(View.GONE);
        searchList = new LinearLayout(this);
        searchList.setOrientation(LinearLayout.VERTICAL);
        TextView searchTitle = text("深度搜索（蒙特卡洛仿真胜率）", 12, C_HINT);
        LinearLayout.LayoutParams stLp = matchWrap();
        stLp.topMargin = dp(10);
        searchBox.addView(searchTitle, stLp);
        searchBox.addView(searchList, matchWrap());
        playCard.addView(searchBox, matchWrap());

        adoptRow = row();
        Button adopt = pillButton("采用推荐", C_GOLD, C_GOLD_INK);
        Button passBtn = pillButton("不出", C_RED, C_FG);
        adopt.setOnClickListener(v -> adoptMove());
        passBtn.setOnClickListener(v -> doPlay(null));
        adoptRow.addView(adopt, weight());
        adoptRow.addView(passBtn, weight());
        adoptRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams adoptLp = matchWrap();
        adoptLp.topMargin = dp(10);
        playCard.addView(adoptRow, adoptLp);

        Button overlay = pillButton("开启手机悬浮窗", C_BORDER, C_FG);
        overlay.setOnClickListener(v -> openOverlay());
        LinearLayout.LayoutParams ovLp = matchWrap();
        ovLp.topMargin = dp(10);
        playCard.addView(overlay, ovLp);

        // 对局检测：自动判断你是否打开了斗地主，省掉每次手动开始
        watchStatus = text("", 12, C_HINT);
        LinearLayout.LayoutParams wsLp = matchWrap();
        wsLp.topMargin = dp(12);
        playCard.addView(watchStatus, wsLp);
        watchList = new LinearLayout(this);
        watchList.setOrientation(LinearLayout.VERTICAL);
        playCard.addView(watchList, matchWrap());
        root.addView(playCard, matchWrap());

        // ---------- 我的手牌 ----------
        LinearLayout handCard = card();
        handCard.addView(sectionTitle("我的剩余手牌", ""));
        handChips = new LinearLayout(this);
        handChips.setOrientation(LinearLayout.VERTICAL);
        handCard.addView(handChips, matchWrap());
        root.addView(handCard, matchWrap());

        // ---------- 记牌器 ----------
        LinearLayout counterCard = card();
        counterCard.addView(sectionTitle("记牌器", "未出现张数"));
        counterGrid = new LinearLayout(this);
        counterGrid.setOrientation(LinearLayout.VERTICAL);
        counterCard.addView(counterGrid, matchWrap());
        TextView legend = text("红框 = 满张 / 双王未见，可能成炸　·　灰 = 已出完", 12, C_HINT);
        LinearLayout.LayoutParams lgLp = matchWrap();
        lgLp.topMargin = dp(8);
        counterCard.addView(legend, lgLp);
        root.addView(counterCard, matchWrap());

        scroll.addView(root);
        setContentView(scroll);

        syncPosButtons();
        syncRecButtons();
        applyRules();
        renderPresets();
        refreshAll();
    }

    // ================= 动作 =================

    private void startGame() {
        try {
            int[] cards = handPicker.cards();
            if (cards.length == 0) {
                setStatus("请先点选 17 张手牌", C_DANGER);
                return;
            }
            String handText = HandStore.toCompact(cards);
            engine.start(position, handText, threeInput.getText().toString());
            // 记录历史，下次可一键回填
            handStore.pushHistory(handText, laiziRank, posIndex());
            renderPresets();
            current = null; selected = null;
            playPicker.clear();
            setStatus("已开局 — 按顺序记录别人的出牌", C_MUTED);
            refreshAll();
        } catch (Exception e) { fail(e); }
    }

    private void recordMove() {
        try {
            int[] picked = playPicker.cards();
            String text = picked.length > 0
                    ? HandStore.toCompact(picked)
                    : playedInput.getText().toString();
            engine.record(recPos, text);
            playPicker.clear();
            playedInput.setText("");
            current = null; selected = null;
            setStatus(engine.isGameOver() ? "本局结束" : engine.status(), C_MUTED);
            refreshAll();
        } catch (Exception e) { fail(e); }
    }

    private void doRecommend() {
        try {
            current = engine.recommend();
            selected = current.candidates.isEmpty() ? null : current.candidates.get(0);
            renderRecommendation();
            setStatus("推荐已生成 — 可点候选切换方案", C_GOLD);
            refreshAll();
        } catch (Exception e) { fail(e); }
    }

    /**
     * 深度搜索：对单步打分的候选做蒙特卡洛仿真，用「胜率」而非单步价值选牌。
     *
     * <p>计算量比单步打分大得多，放后台线程，避免卡住界面。
     */
    private void doDeepSearch() {
        if (!engine.isStarted() || !engine.isMyTurn()) {
            setStatus("还没轮到你出牌", C_RED);
            return;
        }
        btnDeep.setEnabled(false);
        setStatus("深度搜索中…（仿真需要几秒）", C_GOLD);
        final int topK = 4;
        final int sims = 8;
        final int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        new Thread(() -> {
            try {
                DdzEngine.SearchResult r = engine.deepSearch(topK, sims, 0.7, threads);
                runOnUiThread(() -> renderSearch(r));
            } catch (Exception e) {
                runOnUiThread(() -> fail(e));
            } finally {
                runOnUiThread(() -> btnDeep.setEnabled(true));
            }
        }, "ddz-deep-search").start();
    }

    private void renderSearch(DdzEngine.SearchResult r) {
        searchList.removeAllViews();
        for (com.example.ddzoffline.ai.MonteCarloSearch.Row row : r.rows) {
            boolean isBest = row == r.best;
            String line = String.format("%s   胜率 %.0f%%   分差 %+.2f",
                    row.display, row.winRate * 100, row.avgUtil);
            TextView tv = text(line, 13, isBest ? C_GOLD : C_MUTED);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(4);
            searchList.addView(tv, lp);
        }
        searchBox.setVisibility(View.VISIBLE);
        String msg = "深度搜索：" + r.move + "（" + r.sims + " 次仿真 · " + r.millis + " ms）";
        if (r.changedDecision()) msg += " — 与单步推荐不同：" + r.baselineMove;
        setStatus(msg, C_GOLD);
    }

    /**
     * 渲染「对局检测」区：无障碍服务状态 + 最近进入的应用列表。
     *
     * <p>包名不硬编码 —— 让用户从「最近进入的应用」里点一次即可。
     * 任何斗地主版本都能用，游戏改包名也不用改代码。
     */
    private void renderWatch() {
        if (watchStatus == null || watchList == null) return;
        boolean svc = GameWatchService.isServiceOn(this);
        String watched = GameWatchService.getWatched(this);

        if (!svc) {
            watchStatus.setText("对局检测：未开启 — 需在系统设置里授权一次");
            watchStatus.setTextColor(C_GOLD);
        } else if (watched == null) {
            watchStatus.setText("对局检测：已开启 — 还没选目标应用");
            watchStatus.setTextColor(C_GOLD);
        } else {
            watchStatus.setText("对局检测：已开启 — 监听「" + appLabel(watched) + "」");
            watchStatus.setTextColor(C_MUTED);
        }

        watchList.removeAllViews();
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);

        if (!svc) {
            Button b = pillButton("去开启无障碍服务", C_BORDER, C_FG);
            b.setOnClickListener(v -> GameWatchService.openSettings(this));
            watchList.addView(b, lp);
            return;
        }

        List<String> recent = GameWatchService.recentPackages(this);
        if (recent.isEmpty()) {
            watchList.addView(
                    text("先打开一次斗地主，它就会出现在这里，点一下设为监听目标", 11, C_HINT), lp);
            return;
        }
        watchList.addView(text("最近进入的应用（点一下设为监听目标）", 11, C_HINT), lp);
        for (String pkg : recent) {
            Button b = pillButton(appLabel(pkg), C_BORDER, C_FG);
            b.setOnClickListener(v -> {
                GameWatchService.setWatched(this, pkg);
                renderWatch();
            });
            LinearLayout.LayoutParams blp = matchWrap();
            blp.topMargin = dp(4);
            watchList.addView(b, blp);
        }
    }

    /** 包名 → 应用名；拿不到就退回包名。 */
    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static final int REQ_HAND_SHOT = 2001;

    /**
     * 打开截图识牌界面。
     *
     * <p>手牌张数由位置决定：地主 20 张（含 3 张底牌），农民 17 张。
     * 张数已知就不用猜，切槽能切准。
     */
    private void openHandCapture() {
        int count = DdzEngine.LANDLORD.equals(position) ? 20 : 17;
        Intent i = new Intent(this, HandCaptureActivity.class);
        i.putExtra(HandCaptureActivity.EXTRA_COUNT, count);
        startActivityForResult(i, REQ_HAND_SHOT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_HAND_SHOT || res != RESULT_OK || data == null) return;
        String text = data.getStringExtra(HandCaptureActivity.EXTRA_RESULT);
        if (text == null || text.isEmpty()) return;
        // 结果先填进输入框给人确认，不直接开局 —— 识别错一两个点也不会污染对局
        handInput.setText(text);
        handInput.setVisibility(View.VISIBLE);
        setStatus("已从截图读入手牌，核对后点「开始」", C_GOLD);
    }

    private void adoptMove() {
        if (selected == null) { doPlay(null); return; }
        // 关键：把候选的具体化结果一并交给引擎，
        // 保证「推荐评估的牌面」与「实际出牌记录」一致（赖子模式必需）
        doPlay(selected.cards, selected.concrete);
    }

    private void doPlay(int[] cards) {
        doPlay(cards, null);
    }

    private void doPlay(int[] cards, int[] concrete) {
        try {
            String text = (cards == null || cards.length == 0)
                    ? "" : com.example.ddzoffline.ai.Types.display(cards);
            engine.record(engine.getMine(), text, concrete);
            current = null; selected = null;
            setStatus(engine.isGameOver() ? "本局结束" : engine.status(), C_MUTED);
            refreshAll();
        } catch (Exception e) { fail(e); }
    }

    private void undoMove() {
        if (engine.undo()) {
            current = null; selected = null;
            setStatus("已撤销上一步 — 可重新记录", C_MUTED);
            refreshAll();
        }
    }

    private void resetGame() {
        engine.reset();
        current = null; selected = null;
        playedInput.setText("");
        setStatus("已重置，请输入新手牌", C_MUTED);
        refreshAll();
    }

    // ================= 玩法切换 =================

    /** 切换玩法：rank < 0 为经典模式，否则为该点数的赖子模式。 */
    private void switchMode(int rank) {
        laiziRank = rank;
        applyRules();
        renderPresets();
    }

    private void applyRules() {
        if (laiziRank < 0) {
            engine.enableClassic();
            modeHint.setText("经典模式：无赖子，行为与原先完全一致。");
        } else {
            engine.enableLaizi(laiziRank);
            modeHint.setText("赖子模式："
                    + com.example.ddzoffline.ai.Types.label(laiziRank)
                    + " 为万能牌，可代替除大小王外的任意牌。\n"
                    + "注意：DouZero 未训练过赖子玩法，推荐是「把赖子具体化后套用经典模型」的近似结果。");
        }
        // UI 同步
        paintToggle(btnClassic, laiziRank < 0);
        paintToggle(btnLaizi, laiziRank >= 0);
        if (laiziRow != null) {
            laiziRow.setVisibility(laiziRank >= 0 ? View.VISIBLE : View.GONE);
        }
        int[] ranks = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17};
        for (int i = 0; i < laiziButtons.size(); i++) {
            paintToggle(laiziButtons.get(i), ranks[i] == laiziRank);
        }
        // 点选器高亮赖子点数
        if (handPicker != null) handPicker.setLaiziRank(laiziRank);
        if (playPicker != null) playPicker.setLaiziRank(laiziRank);

        current = null;
        selected = null;
        updateHandHint();
    }

    // ================= 预设与历史 =================

    private void savePreset() {
        int[] cards = handPicker.cards();
        if (cards.length == 0) {
            setStatus("请先点选手牌再保存", C_DANGER);
            return;
        }
        String compact = HandStore.toCompact(cards);
        String name = "预设 " + compact.substring(0, Math.min(6, compact.length()));
        handStore.savePreset(name, compact, laiziRank, posIndex());
        renderPresets();
        setStatus("已保存预设：" + compact, C_MUTED);
    }

    private int posIndex() {
        if (DdzEngine.LANDLORD.equals(position)) return 0;
        if (DdzEngine.DOWN.equals(position)) return 1;
        return 2;
    }

    private void renderPresets() {
        if (presetBox == null) return;
        presetBox.removeAllViews();

        List<HandStore.Entry> history = handStore.history();
        if (!history.isEmpty()) {
            presetBox.addView(label("最近手牌（点一下直接回填）"));
            LinearLayout row = null;
            for (int i = 0; i < history.size(); i++) {
                if (i % 2 == 0) {
                    row = row();
                    presetBox.addView(row, matchWrap());
                }
                final HandStore.Entry e = history.get(i);
                Button b = pillButton(e.cards, C_DEEP, C_FG);
                b.setTextSize(12);
                b.setOnClickListener(v -> applyEntry(e));
                row.addView(b, weight());
            }
        }

        List<HandStore.Entry> presets = handStore.presets();
        if (!presets.isEmpty()) {
            presetBox.addView(label("我的预设"));
            LinearLayout row = null;
            for (int i = 0; i < presets.size(); i++) {
                if (i % 2 == 0) {
                    row = row();
                    presetBox.addView(row, matchWrap());
                }
                final HandStore.Entry e = presets.get(i);
                Button b = pillButton(e.display(), C_DEEP, C_GOLD);
                b.setTextSize(12);
                b.setOnClickListener(v -> applyEntry(e));
                b.setOnLongClickListener(v -> {
                    handStore.deletePreset(e.name);
                    renderPresets();
                    setStatus("已删除预设：" + e.name, C_MUTED);
                    return true;
                });
                row.addView(b, weight());
            }
        }
    }

    /** 回填一条预设/历史，并恢复它当时的玩法与身份。 */
    private void applyEntry(HandStore.Entry e) {
        if (e.laiziRank != laiziRank) {
            switchMode(e.laiziRank);
        }
        inputByPicker = false;
        handPicker.setCards(e.cards);
        handInput.setText(e.cards);
        inputByPicker = true;
        updateHandHint();
        setStatus("已回填手牌：" + e.cards, C_MUTED);
    }

    private void updateHandHint() {
        if (handCountHint == null) return;
        int n = handPicker == null ? 0 : handPicker.total();
        if (n == 0) {
            handCountHint.setText("点选牌面即可；需要 17 张，地主会自动并入 3 张底牌成为 20 张。");
            handCountHint.setTextColor(C_HINT);
        } else {
            handCountHint.setText("已选 " + n + " 张"
                    + (n == 17 ? " ✓ 张数正确" : "（需 17 张，还差 " + Math.abs(17 - n) + " 张）"));
            handCountHint.setTextColor(n == 17 ? C_GOLD : C_HINT);
        }
    }

    private void switchModel(String obj) {        try {
            engine.setObjective(obj);
            syncModelButtons();
            setStatus("已切换模型：" + obj + (obj.equals("WP") ? "（胜率优先）" : "（平均分差）"), C_MUTED);
        } catch (Exception e) { fail(e); }
    }

    private void syncModelButtons() {
        boolean wp = "WP".equals(engine.getObjective());
        paintToggle(btnWp, wp);
        paintToggle(btnAdp, !wp);
    }

    private void syncPosButtons() {
        String[] keys = {DdzEngine.LANDLORD, DdzEngine.DOWN, DdzEngine.UP};
        for (int i = 0; i < posButtons.size(); i++) paintToggle(posButtons.get(i), keys[i].equals(position));
    }

    private void syncRecButtons() {
        String[] keys = {DdzEngine.LANDLORD, DdzEngine.DOWN, DdzEngine.UP};
        for (int i = 0; i < recButtons.size(); i++) paintToggle(recButtons.get(i), keys[i].equals(recPos));
    }

    // ================= 渲染 =================

    private void refreshAll() {
        boolean started = engine.isStarted() || engine.isGameOver();

        // 局面变了，上一次的搜索结果就失效了
        if (searchBox != null) searchBox.setVisibility(View.GONE);

        // 手牌
        handChips.removeAllViews();
        if (started) {
            List<Integer> cards = DdzEngine.parseCards(engine.getHandText());
            addChipRows(handChips, cards, 9, true);
            if (cards.isEmpty()) handChips.addView(text("（已出完）", 14, C_HINT));
        } else {
            handChips.addView(text("尚未开局", 14, C_HINT));
        }

        // 记牌器
        counterGrid.removeAllViews();
        if (started) {
            Map<String, Integer> u = engine.unseenCounts();
            addCounterRows(u);
        } else {
            counterGrid.addView(text("尚未开局", 14, C_HINT));
        }

        // 撤销
        btnUndo.setEnabled(engine.canUndo());
        btnUndo.setTextColor(engine.canUndo() ? C_FG : C_HINT);
        btnUndo.setAlpha(engine.canUndo() ? 1f : 0.5f);

        // 轮次
        turnTag.setText(started ? "轮到 " + DdzEngine.name(engine.getTurn()) : "—");
    }

    private void renderRecommendation() {
        moveChips.removeAllViews();
        candList.removeAllViews();
        if (current == null) {
            adoptRow.setVisibility(View.GONE);
            candBox.setVisibility(View.GONE);
            return;
        }
        moveLabel.setText("推荐出牌");
        List<Integer> cards = DdzEngine.parseCards(current.move);
        if (cards.isEmpty()) {
            TextView t = text("不出", 22, C_RED);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            moveChips.addView(t);
        } else {
            addChipRows(moveChips, cards, 9, true);
        }
        adoptRow.setVisibility(View.VISIBLE);

        List<DdzEngine.Candidate> cands = current.candidates;
        if (cands.size() <= 1) { candBox.setVisibility(View.GONE); return; }
        candBox.setVisibility(View.VISIBLE);

        for (int i = 0; i < cands.size(); i++) {
            final DdzEngine.Candidate c = cands.get(i);
            LinearLayout item = row();
            item.setPadding(dp(10), dp(8), dp(10), dp(8));
            item.setBackground(rounded(C_DEEP, c.best ? C_GOLD : C_BORDER, 8));

            TextView rank = text(String.valueOf(i + 1), 12, c.best ? C_GOLD_INK : C_FG);
            rank.setGravity(Gravity.CENTER);
            rank.setBackground(rounded(c.best ? C_GOLD : C_BORDER, 0, 999));
            item.addView(rank, new LinearLayout.LayoutParams(dp(24), dp(24)));

            LinearLayout cardsBox = new LinearLayout(this);
            cardsBox.setOrientation(LinearLayout.HORIZONTAL);
            cardsBox.setPadding(dp(8), 0, 0, 0);
            List<Integer> cc = DdzEngine.parseCards(c.display);
            if (cc.isEmpty()) {
                cardsBox.addView(text("不出", 15, C_FG));
            } else {
                for (int card : cc) cardsBox.addView(chip(card, false), chipLp(false));
            }
            item.addView(cardsBox, new LinearLayout.LayoutParams(0, dp(44), 1));

            String score = "WP".equals(engine.getObjective())
                    ? String.format("胜率≈%.1f%%", c.score * 100)
                    : String.format("分差≈%+.2f", c.score);
            item.addView(text(score, 12, C_HINT), wrapWrap());

            item.setOnClickListener(v -> {
                selected = c;
                moveLabel.setText("已选第 " + (cands.indexOf(c) + 1) + " 个方案");
                moveChips.removeAllViews();
                List<Integer> sel = DdzEngine.parseCards(c.display);
                if (sel.isEmpty()) {
                    TextView t = text("不出", 22, C_RED);
                    t.setTypeface(null, android.graphics.Typeface.BOLD);
                    moveChips.addView(t);
                } else {
                    addChipRows(moveChips, sel, 9, true);
                }
            });
            LinearLayout.LayoutParams ilp = matchWrap();
            ilp.bottomMargin = dp(6);
            candList.addView(item, ilp);
        }
    }

    /** 牌面 chip 分行排列（原生无 Flexbox，按每行上限手工折行）。 */
    private void addChipRows(LinearLayout host, List<Integer> cards, int perRow, boolean big) {
        if (cards.isEmpty()) return;
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = matchWrap();
                rlp.bottomMargin = dp(4);
                host.addView(row, rlp);
            }
            row.addView(chip(cards.get(i), big), chipLp(big));
        }
    }

    private void addCounterRows(Map<String, Integer> unseen) {
        String[] order = {"3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "X", "D"};
        LinearLayout row = null;
        int col = 0;
        for (String k : order) {
            if (col % 8 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = matchWrap();
                rlp.bottomMargin = dp(4);
                counterGrid.addView(row, rlp);
            }
            int n = unseen.getOrDefault(k, 0);
            int max = (k.equals("X") || k.equals("D")) ? 1 : 4;
            boolean hot = n == max;
            boolean done = n == 0;

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(2), dp(5), dp(2), dp(5));
            box.setBackground(rounded(C_DEEP, hot ? Color.rgb(255, 82, 82) : C_BORDER, 7));
            box.setAlpha(done ? 0.38f : 1f);

            TextView kk = text(k, 13, C_MUTED);
            kk.setGravity(Gravity.CENTER);
            TextView vv = text(String.valueOf(n), 15, hot ? Color.rgb(255, 138, 128) : C_FG);
            vv.setGravity(Gravity.CENTER);
            vv.setTypeface(null, android.graphics.Typeface.BOLD);
            box.addView(kk);
            box.addView(vv);

            box.setContentDescription(k + " 剩 " + n + " 张"
                    + (hot ? "，可能成炸" : (done ? "，已出完" : "")));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(46), 1);
            blp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(box, blp);
            col++;
        }
    }

    // ================= 小组件 =================

    private void setStatus(String s, int color) {
        statusText.setText(s);
        statusText.setTextColor(color);
    }

    private void fail(Exception e) {
        String msg = e.getMessage() == null ? "操作失败" : e.getMessage();
        setStatus(msg, C_DANGER);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void openOverlay() {
        if (!engine.isStarted()) {
            Toast.makeText(this, "请先开始对局", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "请打开“允许显示在其他应用上层”，返回后会自动启动", Toast.LENGTH_LONG).show();
            return;
        }
        startOverlayService();
    }

    private void startOverlayService() {
        Intent service = new Intent(this, OverlayService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "悬浮窗已开启，可切回牌桌", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- 绘制辅助 ----

    private TextView sectionTitle(String title, String tag) {
        TextView t = text(title, 13, C_MUTED);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, C_MUTED);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(C_HINT);
        e.setTextColor(C_FG);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(rounded(C_DEEP, C_BORDER, 8));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(48);
        lp.topMargin = dp(4);
        e.setLayoutParams(lp);
        return e;
    }

    private Button pillButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(rounded(bg, C_BORDER, 8));
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }

    private void paintToggle(Button b, boolean on) {
        if (b == null) return;
        b.setTextColor(on ? C_GOLD_INK : C_MUTED);
        b.setBackground(rounded(on ? C_GOLD : C_BORDER, 0, 8));
    }

    private TextView chip(int card, boolean big) {
        TextView t = new TextView(this);
        t.setText(com.example.ddzoffline.ai.Types.label(card));
        t.setTextSize(big ? 17 : 14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        boolean joker = card == 20 || card == 30;
        boolean major = card == 17 || joker;
        t.setTextColor(joker ? Color.rgb(255, 138, 101) : (major ? C_GOLD : C_FG));
        int border = joker ? Color.rgb(255, 138, 101) : (major ? 0x80FFD54F : C_BORDER);
        t.setBackground(rounded(C_DEEP, border, big ? 7 : 5));
        return t;
    }

    private LinearLayout.LayoutParams chipLp(boolean big) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(big ? 34 : 26), dp(big ? 44 : 30));
        lp.setMargins(0, 0, dp(4), 0);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(12), dp(12), dp(12));
        l.setBackground(rounded(C_CARD, C_BORDER, 12));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        d.setCornerRadius(radiusDp >= 999 ? dp(999) : dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 极简 TextWatcher：只关心变化回调，避免引入额外抽象。 */
    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final Runnable onChange;
        SimpleWatcher(Runnable r) { this.onChange = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(android.text.Editable s) { onChange.run(); }
    }
}
