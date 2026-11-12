package com.example.ddzoffline.ai;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 手牌识别验证（纯 Java 模板匹配，不依赖 OpenCV）。
 *
 * 用 Java2D 合成牌面图，端到端验证：
 *   合成牌行 → 切槽 → 模板匹配 → 点数序列
 *
 * 验证点：
 *   A) 精确识别：全部认对
 *   B) 抗噪：加噪声后仍认对
 *   C) 不确定时不瞎猜：模板对不上时标记为未识别
 *   D) 模板序列化往返一致
 *   E) 性能
 */
public final class HandRecognizerTest {

    static int pass = 0, fail = 0;

    /** 牌面点数用的单字符（避免「小王」这种宽字符影响切槽）。 */
    static String glyph(int rank) {
        switch (rank) {
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            case 14: return "A";
            case 17: return "2";
            case 20: return "x";
            case 30: return "D";
            default: return String.valueOf(rank);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        System.out.println("=".repeat(76));
        System.out.println("手牌识别（纯 Java 模板匹配）—— 验证");
        System.out.println("=".repeat(76));

        final int SLOT_W = 48, ROW_H = 96;

        // 参与测试的点数
        int[] ranks = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

        // ---------- A：精确识别 ----------
        section("A) 精确识别：17 张手牌");
        List<HandRecognizer.CardTemplate> templates = new ArrayList<>();
        for (int r : ranks) {
            templates.add(new HandRecognizer.CardTemplate(
                    r, TemplateMatcher.toGray(render(glyph(r), SLOT_W, ROW_H, 0xFFFFFFFF), SLOT_W, ROW_H)));
        }

        int[] hand = {3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30, 9};
        int[] row = buildRow(hand, SLOT_W, ROW_H, 0xFFFFFFFF);
        TemplateMatcher.Gray rowGray = TemplateMatcher.toGray(row, SLOT_W * hand.length, ROW_H);

        HandRecognizer.Result res = HandRecognizer.recognize(rowGray, hand.length, templates, 0.6);
        System.out.println("      期望: " + labels(hand));
        System.out.println("      识别: " + res.describe());
        System.out.println("      最低相关系数: " + String.format("%.4f", res.worstScore));
        check("全部 17 张认对", Arrays.equals(res.ranks(), hand), res.describe());
        check("整体置信", res.confident, "worst=" + res.worstScore);

        // ---------- B：抗噪 ----------
        section("B) 抗噪：叠加高斯噪声后仍能认对");
        int[] noisy = row.clone();
        Random rng = new Random(42);
        for (int i = 0; i < noisy.length; i++) {
            int c = noisy[i];
            int r = clamp(((c >> 16) & 0xFF) + (int) (rng.nextGaussian() * 12));
            int g = clamp(((c >> 8) & 0xFF) + (int) (rng.nextGaussian() * 12));
            int b = clamp((c & 0xFF) + (int) (rng.nextGaussian() * 12));
            noisy[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        TemplateMatcher.Gray noisyGray = TemplateMatcher.toGray(noisy, SLOT_W * hand.length, ROW_H);
        HandRecognizer.Result resN = HandRecognizer.recognize(noisyGray, hand.length, templates, 0.6);
        System.out.println("      识别: " + resN.describe());
        System.out.println("      最低相关系数: " + String.format("%.4f", resN.worstScore));
        check("噪声下仍全部认对", Arrays.equals(resN.ranks(), hand), resN.describe());

        // ---------- C：不确定时不瞎猜 ----------
        section("C) 不确定时标记为未识别（不瞎猜）");
        // 用「空白牌面」当模板 —— 与任何真实牌都对不上
        List<HandRecognizer.CardTemplate> blank = new ArrayList<>();
        blank.add(new HandRecognizer.CardTemplate(
                99, TemplateMatcher.toGray(render("", SLOT_W, ROW_H, 0xFFFFFFFF), SLOT_W, ROW_H)));
        HandRecognizer.Result resB = HandRecognizer.recognize(rowGray, hand.length, blank, 0.6);
        check("低置信度时返回未识别而不是乱猜",
                !resB.allRecognized() && !resB.confident,
                resB.describe());

        // ---------- D：模板序列化 ----------
        section("D) 模板序列化往返");
        byte[] blob = HandRecognizer.encode(templates);
        List<HandRecognizer.CardTemplate> back = HandRecognizer.decode(blob);
        boolean same = back.size() == templates.size();
        if (same) {
            for (int i = 0; i < back.size(); i++) {
                HandRecognizer.CardTemplate a = templates.get(i), b = back.get(i);
                if (a.rank != b.rank || a.gray.w != b.gray.w || a.gray.h != b.gray.h
                        || !Arrays.equals(a.gray.px, b.gray.px)) { same = false; break; }
            }
        }
        check("encode → decode 逐字节一致", same, "");
        System.out.println("      模板集 " + blob.length + " 字节（" + templates.size() + " 个点数）");

        HandRecognizer.Result resD = HandRecognizer.recognize(rowGray, hand.length, back, 0.6);
        check("用反序列化后的模板仍能认对", Arrays.equals(resD.ranks(), hand), resD.describe());

        // ---------- E：性能 ----------
        section("E) 性能");
        // 降采样一半（等价 cardRecorder 的 pyrDown）
        TemplateMatcher.Gray half = TemplateMatcher.downsample(rowGray, 2);
        List<HandRecognizer.CardTemplate> halfT = new ArrayList<>();
        for (HandRecognizer.CardTemplate t : templates) {
            halfT.add(new HandRecognizer.CardTemplate(t.rank, TemplateMatcher.downsample(t.gray, 2)));
        }
        long t0 = System.nanoTime();
        HandRecognizer.Result resH = HandRecognizer.recognize(half, hand.length, halfT, 0.6);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("      原图 " + rowGray.w + "x" + rowGray.h
                + " → 降采样 " + half.w + "x" + half.h + "，识别耗时 " + ms + " ms");
        check("降采样后仍全部认对", Arrays.equals(resH.ranks(), hand), resH.describe());
        check("单次识别在 2 秒内（桌面 CPU）", ms < 2000, ms + " ms");

        System.out.println();
        System.out.println("=".repeat(76));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(76));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 合成牌面 ----------------

    /** 渲染一个牌面点数。 */
    static int[] render(String text, int w, int h, int bg) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(bg, true));
        g.fillRect(0, 0, w, h);
        if (!text.isEmpty()) {
            g.setColor(new Color(0xFF101010, true));
            g.setFont(new Font("SansSerif", Font.BOLD, (int) (h * 0.62)));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 3, fm.getAscent() + 3);
        }
        g.dispose();
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);
        return px;
    }

    /** 把若干张牌横向拼成一行（模拟手牌扇形展开的等间距布局）。 */
    static int[] buildRow(int[] ranks, int slotW, int h, int bg) {
        int totalW = slotW * ranks.length;
        int[] row = new int[totalW * h];
        for (int i = 0; i < ranks.length; i++) {
            int[] card = render(glyph(ranks[i]), slotW, h, bg);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < slotW; x++) {
                    row[y * totalW + i * slotW + x] = card[y * slotW + x];
                }
            }
        }
        return row;
    }

    static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    static String labels(int[] ranks) {
        StringBuilder sb = new StringBuilder();
        for (int r : ranks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(glyph(r));
        }
        return sb.toString();
    }

    static void section(String s) {
        System.out.println();
        System.out.println("-- " + s);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }
}
