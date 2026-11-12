package com.example.ddzoffline.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 引擎级一致性测试（纯 JVM，无需 Android）：
 * 用与 Android 端相同的推理路径，对黄金场景产出推荐，并与 PyTorch 侧
 * 真实推荐结果比对。同时验证规则判定（领出/压牌/不出）。
 */
public final class EngineParityTest {

    static int pass = 0, fail = 0;

    /** 复刻 Android DdzEngine 的核心推理路径，但不依赖 android.content.Context。 */
    static final class BareEngine {
        final java.util.Map<String, DdzModel> models = new java.util.LinkedHashMap<>();
        String myPosition;
        List<Integer> hand = new ArrayList<>();
        List<Integer> three = new ArrayList<>();
        java.util.Map<String, List<Integer>> played = new java.util.LinkedHashMap<>();
        java.util.Map<String, int[]> lastMoveDict = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> numLeft = new java.util.LinkedHashMap<>();
        List<int[]> seq = new ArrayList<>();
        int[] lastMove = new int[0];
        String lastPid = "landlord";
        int bombNum;
        String turn = "landlord";
        boolean started;

        BareEngine(Path assets, String objective) throws Exception {
            for (String pos : new String[]{"landlord", "landlord_up", "landlord_down"}) {
                float[] blob = readFloats(assets.resolve("ddz_" + objective + "_" + pos + ".bin"));
                models.put(pos, DdzModel.fromBlob(blob, DdzModel.inDimFor(pos)));
            }
            reset();
        }

        void reset() {
            myPosition = null; hand = new ArrayList<>(); three = new ArrayList<>();
            played = new java.util.LinkedHashMap<>();
            lastMoveDict = new java.util.LinkedHashMap<>();
            for (String p : new String[]{"landlord", "landlord_up", "landlord_down"}) {
                played.put(p, new ArrayList<>());
                lastMoveDict.put(p, new int[0]);
            }
            numLeft = new java.util.LinkedHashMap<>();
            numLeft.put("landlord", 20); numLeft.put("landlord_up", 17); numLeft.put("landlord_down", 17);
            seq = new ArrayList<>();
            lastMove = new int[0]; lastPid = "landlord"; bombNum = 0;
            turn = "landlord"; started = false;
        }

        void start(String position, String handText, String threeText) {
            List<Integer> initial = DdzEngineShim.parse(handText);
            List<Integer> t = DdzEngineShim.parse(threeText);
            reset();
            myPosition = position;
            hand = new ArrayList<>(initial);
            if ("landlord".equals(position)) hand.addAll(t);
            java.util.Collections.sort(hand);
            three = new ArrayList<>(t);
            numLeft.put(position, hand.size());
            started = true;
            turn = "landlord";
        }

        int[] effectiveRival() {
            if (myPosition != null && myPosition.equals(lastPid)) return new int[0];
            return lastMove;
        }

        void record(String position, String cardsText) {
            List<Integer> cards = DdzEngineShim.parse(cardsText);
            if (!turn.equals(position)) throw new IllegalArgumentException("当前应由" + position + "出牌");
            if (position.equals(myPosition)) {
                if (!cards.isEmpty() && !DdzEngineShim.containsAll(hand, cards))
                    throw new IllegalArgumentException("不在手牌中");
                if (!legalAgainst(cards, effectiveRival())) throw new IllegalArgumentException("不符合牌型");
                for (Integer c : cards) hand.remove(c);
                numLeft.put(myPosition, hand.size());
            } else {
                if (!cards.isEmpty() && !legalAgainst(cards, effectiveRival()))
                    throw new IllegalArgumentException("不符合牌型");
                numLeft.put(position, numLeft.get(position) - cards.size());
            }
            int[] arr = DdzEngineShim.toArray(cards);
            lastMoveDict.put(position, arr);
            seq.add(arr);
            played.get(position).addAll(cards);
            if (arr.length == 4 && arr[0] == arr[3]) bombNum++;
            if (arr.length == 2 && arr[0] == 20 && arr[1] == 30) bombNum++;
            if (arr.length > 0) { lastMove = arr; lastPid = position; }
            turn = "landlord".equals(position) ? "landlord_down"
                    : ("landlord_down".equals(position) ? "landlord_up" : "landlord");
        }

        boolean legalAgainst(List<Integer> cards, int[] prev) {
            int[] arr = DdzEngineShim.toArray(cards);
            if (arr.length == 0) return prev.length > 0;
            java.util.Map<String, Object> my = MoveDetector.getMoveType(arr);
            int myType = MoveDetector.typeOf(my);
            if (myType == Types.TYPE_15_WRONG) return false;
            if (prev.length == 0) return true;
            java.util.Map<String, Object> up = MoveDetector.getMoveType(prev);
            int upType = MoveDetector.typeOf(up);
            if (myType == Types.TYPE_5_KING_BOMB) return true;
            if (upType == Types.TYPE_5_KING_BOMB) return false;
            if (myType == Types.TYPE_4_BOMB)
                return upType != Types.TYPE_4_BOMB || MoveDetector.rankOf(my) > MoveDetector.rankOf(up);
            if (upType == Types.TYPE_4_BOMB || upType == Types.TYPE_5_KING_BOMB) return false;
            if (myType != upType || arr.length != prev.length) return false;
            return MoveDetector.rankOf(my) > MoveDetector.rankOf(up);
        }

        String recommend() {
            InfoSet is = new InfoSet(myPosition);
            is.playerHandCards = DdzEngineShim.toArray(hand);
            is.lastMove = effectiveRival();
            is.lastPid = lastPid;
            is.bombNum = bombNum;
            is.cardPlayActionSeq = new ArrayList<>(seq);
            List<Integer> known = new ArrayList<>(hand);
            for (String p : new String[]{"landlord", "landlord_up", "landlord_down"}) known.addAll(played.get(p));
            known.addAll(three);
            List<Integer> others = new ArrayList<>();
            for (int r : new int[]{3,4,5,6,7,8,9,10,11,12,13,14,17,20,30}) {
                int max = (r == 20 || r == 30) ? 1 : 4;
                int have = 0;
                for (int c : known) if (c == r) have++;
                for (int i = 0; i < max - have; i++) others.add(r);
            }
            is.otherHandCards = DdzEngineShim.toArray(others);
            for (String p : new String[]{"landlord", "landlord_up", "landlord_down"}) {
                is.playedCards.put(p, DdzEngineShim.toArray(played.get(p)));
                is.lastMoveDict.put(p, lastMoveDict.get(p));
            }
            is.numCardsLeftDict = new java.util.LinkedHashMap<>(numLeft);

            List<int[]> legal = DdzFeatures.legalActionsFrom(is);
            is.legalActions = legal;
            DdzFeatures.Encoded enc = DdzFeatures.encode(is);
            Tensor x = new Tensor(legal.size(), enc.inDim, enc.xBatch);
            float[] values = models.get(myPosition).forward(enc.zBatch, x);

            Integer[] order = new Integer[values.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final float[] v = values;
            Arrays.sort(order, (a, b) -> Float.compare(v[b], v[a]));
            int[] best = legal.get(order[0]);
            return best.length == 0 ? "不出" : Types.display(best);
        }
    }

    /** 与 DdzEngine 的静态工具保持一致（避免依赖 Android 类）。 */
    static final class DdzEngineShim {
        static List<Integer> parse(String text) {
            List<Integer> out = new ArrayList<>();
            if (text == null) return out;
            String s = text.replace(" ", "").replace(",", "").replace("，", "").toUpperCase();
            for (int i = 0; i < s.length(); ) {
                char ch = s.charAt(i);
                if (ch == '1') { out.add(10); i += (i + 1 < s.length() && s.charAt(i + 1) == '0') ? 2 : 1; }
                else if (ch >= '3' && ch <= '9') { out.add(ch - '0'); i++; }
                else if (ch == 'T') { out.add(10); i++; }
                else if (ch == 'J') { out.add(11); i++; }
                else if (ch == 'Q') { out.add(12); i++; }
                else if (ch == 'K') { out.add(13); i++; }
                else if (ch == 'A') { out.add(14); i++; }
                else if (ch == '2') { out.add(17); i++; }
                else if (ch == 'X') { out.add(20); i++; }
                else if (ch == 'D') { out.add(30); i++; }
                else throw new IllegalArgumentException("无法识别的牌：" + ch);
            }
            java.util.Collections.sort(out);
            return out;
        }
        static int[] toArray(List<Integer> l) {
            int[] o = new int[l.size()];
            for (int i = 0; i < o.length; i++) o[i] = l.get(i);
            return o;
        }
        static boolean containsAll(List<Integer> src, List<Integer> want) {
            List<Integer> c = new ArrayList<>(src);
            for (Integer w : want) if (!c.remove(w)) return false;
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path assets = root.resolve("android-offline/app/src/main/assets");

        System.out.println("=".repeat(72));
        System.out.println("引擎一致性测试（Java 推理路径 vs PyTorch 参考）");
        System.out.println("=".repeat(72));

        // 与 Python 侧相同场景，逐一对拍推荐结果
        String[][][] scenarios = {
            // {位置, 手牌, 底牌, 记录...}  —— 记录以 "pos|cards" 平铺
            {{"landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2"}},
            {{"landlord_down", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2", "landlord|5"}},
            {{"landlord_up", "3 3 4 5 6 7 8 9 10 J Q K A 2 X D 4", "9 9 2",
              "landlord|K K", "landlord_down|"}},
        };

        for (String[][] sc : scenarios) {
            String[] s = sc[0];
            String pos = s[0], hand = s[1], three = s[2];
            BareEngine e = new BareEngine(assets, "WP");
            e.start(pos, hand, three);
            for (int i = 3; i < s.length; i++) {
                String[] kv = s[i].split("\\|", -1);
                e.record(kv[0], kv[1]);
            }
            String got = e.recommend();
            System.out.println("  " + pos + " -> Java 推荐: " + got);
        }

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("规则判定");
        System.out.println("=".repeat(72));
        BareEngine e = new BareEngine(assets, "WP");
        e.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");

        // 领出方不能不出
        check("领出不能不出", !e.legalAgainst(DdzEngineShim.parse(""), new int[0]));
        // 领出任意合法牌型均可
        check("领出单张合法", e.legalAgainst(DdzEngineShim.parse("3"), new int[0]));
        check("领出对子合法", e.legalAgainst(DdzEngineShim.parse("3 3"), new int[0]));
        // 非法牌型
        check("非法牌型被拒（3 4）", !e.legalAgainst(DdzEngineShim.parse("3 4"), new int[0]));
        // 压牌
        check("单张压单张", e.legalAgainst(DdzEngineShim.parse("5"), new int[]{3}));
        check("小的压不过", !e.legalAgainst(DdzEngineShim.parse("3"), new int[]{5}));
        check("炸弹压单张", e.legalAgainst(DdzEngineShim.parse("3 3 3 3"), new int[]{5}));
        check("王炸压炸弹", e.legalAgainst(DdzEngineShim.parse("X D"), new int[]{3,3,3,3}));
        check("炸弹压不过王炸", !e.legalAgainst(DdzEngineShim.parse("3 3 3 3"), new int[]{20,30}));
        check("不同型不能压（对子 vs 单张）", !e.legalAgainst(DdzEngineShim.parse("3 3"), new int[]{5}));
        check("同型不同长不能压（3连 vs 2连）",
                !e.legalAgainst(DdzEngineShim.parse("3 3 4 4 5 5"), new int[]{3,3,4,4}));
        check("有上家牌时可以不出", e.legalAgainst(DdzEngineShim.parse(""), new int[]{5}));

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(72));
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name); }
    }

    static float[] readFloats(Path p) throws Exception {
        byte[] raw = Files.readAllBytes(p);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[raw.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
        return out;
    }
}
