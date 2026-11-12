package com.example.ddzoffline.ai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 手牌识别 —— 把「手牌区域截图」转成点数序列。
 *
 * <p>流程：手牌行 → 按张数等分切槽 → 每槽内用模板匹配找出点数 → 返回结果。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>等分切槽</b>：斗地主手牌是等间距扇形展开，每张露出左侧一列（含点数），
 *       所以按张数等分即可，不需要做边缘检测。</li>
 *   <li><b>模板来自用户标定</b>：不同游戏、不同手机的点数字体都不一样，
 *       内置模板必然过时。所以模板由用户从自己的截图里框一次，存本地复用。</li>
 *   <li><b>结果先给人确认</b>：识别错了不直接污染对局记录。</li>
 * </ul>
 */
public final class HandRecognizer {

    private HandRecognizer() {}

    // ---------------- 数据结构 ----------------

    /** 一个点数模板。 */
    public static final class CardTemplate {
        public final int rank;                        // 3..14 / 17 / 20 / 30
        public final TemplateMatcher.Gray gray;

        public CardTemplate(int rank, TemplateMatcher.Gray gray) {
            this.rank = rank;
            this.gray = gray;
        }
    }

    /** 单个槽位的识别结果。 */
    public static final class Slot {
        public final int rank;      // 识别出的点数；-1 = 未识别
        public final double score;  // 相关系数
        public final int x, w;      // 槽位水平范围

        Slot(int rank, double score, int x, int w) {
            this.rank = rank;
            this.score = score;
            this.x = x;
            this.w = w;
        }

        public String label() {
            return rank < 0 ? "?" : Types.label(rank);
        }
    }

    public static final class Result {
        public final List<Slot> slots = new ArrayList<>();
        /** 所有槽位中最低的相关系数 —— 用来判断整行识别是否可信。 */
        public double worstScore = 1.0;
        public boolean confident;

        /** 识别出的点数序列（未识别的为 -1）。 */
        public int[] ranks() {
            int[] r = new int[slots.size()];
            for (int i = 0; i < r.length; i++) r[i] = slots.get(i).rank;
            return r;
        }

        public boolean allRecognized() {
            for (Slot s : slots) if (s.rank < 0) return false;
            return true;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Slot s : slots) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(s.label());
            }
            return sb.toString();
        }
    }

    // ---------------- 识别 ----------------

    /**
     * 识别手牌行。
     *
     * @param hand      手牌区域灰度图（已按需降采样，与模板同一尺度）
     * @param count     手牌张数（17 或 20，由对局状态已知）
     * @param templates 点数模板集
     * @param minScore  单个槽位的最低可接受相关系数，低于则记为未识别
     */
    public static Result recognize(TemplateMatcher.Gray hand, int count,
                                   List<CardTemplate> templates, double minScore) {
        Result res = new Result();
        if (count <= 0 || templates.isEmpty() || hand.w < count) {
            res.confident = false;
            return res;
        }

        int slotW = hand.w / count;
        for (int i = 0; i < count; i++) {
            int x0 = i * slotW;
            int x1 = (i == count - 1) ? hand.w : x0 + slotW;

            int bestRank = -1;
            double bestScore = -1.0;
            for (CardTemplate t : templates) {
                if (t.gray.w > x1 - x0 || t.gray.h > hand.h) continue;
                double s = TemplateMatcher.bestMatch(hand, t.gray, x0, 0, x1, hand.h);
                if (s > bestScore) {
                    bestScore = s;
                    bestRank = t.rank;
                }
            }
            if (bestScore < minScore) bestRank = -1;
            res.slots.add(new Slot(bestRank, bestScore, x0, x1 - x0));
            res.worstScore = Math.min(res.worstScore, bestScore);
        }
        res.confident = res.allRecognized() && res.worstScore >= minScore;
        return res;
    }

    // ---------------- 模板集序列化 ----------------

    private static final byte[] MAGIC = {'D', 'D', 'Z', 'T'};

    /** 把模板集编码成字节（存本地，下次开局直接复用，不用重新标定）。 */
    public static byte[] encode(List<CardTemplate> templates) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.write(MAGIC);
        out.writeByte(1);                       // 版本
        out.writeShort(templates.size());
        for (CardTemplate t : templates) {
            out.writeShort(t.rank);
            out.writeShort(t.gray.w);
            out.writeShort(t.gray.h);
            out.write(t.gray.px);
        }
        out.flush();
        return bos.toByteArray();
    }

    public static List<CardTemplate> decode(byte[] data) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte[] magic = new byte[4];
        in.readFully(magic);
        for (int i = 0; i < 4; i++) {
            if (magic[i] != MAGIC[i]) throw new IllegalArgumentException("模板文件格式不符");
        }
        int version = in.readUnsignedByte();
        if (version != 1) throw new IllegalArgumentException("模板版本不支持: " + version);
        int n = in.readUnsignedShort();
        List<CardTemplate> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int rank = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            byte[] px = new byte[w * h];
            in.readFully(px);
            out.add(new CardTemplate(rank, new TemplateMatcher.Gray(px, w, h)));
        }
        return out;
    }
}
