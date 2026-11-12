package com.example.ddzoffline.ai;

/**
 * 纯 Java 模板匹配 —— 替代 OpenCV 的 {@code pyrDown} + {@code matchTemplate(TM_CCOEFF_NORMED)}。
 *
 * <p>为什么不用 OpenCV：本项目约束「无第三方依赖」，而 OpenCV 的 arm64 动态库约 15 MB，
 * 会把 APK 从 33 MB 顶到 48 MB。这里其实只用到两个函数（降采样 + 归一化互相关），
 * 都能在 {@code byte[]}/{@code int[]} 上直接实现，约 150 行，零依赖且 JVM 可测。
 *
 * <p>性能关键：手牌识别时每张牌的位置基本可预测，所以只在**小偏移窗口**内搜索，
 * 不做全图扫描。全图搜索是 O(W·H·n)，偏移搜索是 O(dx·dy·n)，差三个数量级。
 */
public final class TemplateMatcher {

    private TemplateMatcher() {}

    /** 灰度缓冲区 + 尺寸。 */
    public static final class Gray {
        public final byte[] px;
        public final int w;
        public final int h;

        Gray(byte[] px, int w, int h) {
            this.px = px;
            this.w = w;
            this.h = h;
        }
    }

    // ---------------- 预处理 ----------------

    /** ARGB → 灰度（整数亮度近似，避免浮点）。 */
    public static Gray toGray(int[] argb, int w, int h) {
        byte[] g = new byte[w * h];
        for (int i = 0; i < g.length; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xFF;
            int gg = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            g[i] = (byte) ((r * 77 + gg * 151 + b * 28) >> 8);
        }
        return new Gray(g, w, h);
    }

    /** 盒式降采样。factor=2 等价于一次 pyrDown。 */
    public static Gray downsample(Gray src, int factor) {
        if (factor <= 1) return src;
        int w = Math.max(1, src.w / factor);
        int h = Math.max(1, src.h / factor);
        byte[] out = new byte[w * h];
        int area = factor * factor;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int sy = y * factor + dy;
                    if (sy >= src.h) break;
                    for (int dx = 0; dx < factor; dx++) {
                        int sx = x * factor + dx;
                        if (sx >= src.w) break;
                        sum += src.px[sy * src.w + sx] & 0xFF;
                        n++;
                    }
                }
                out[y * w + x] = (byte) (n == 0 ? 0 : sum / n);
            }
        }
        return new Gray(out, w, h);
    }

    /** 按比例缩放（最近邻），用于把模板缩放到目标尺寸。 */
    public static Gray resize(Gray src, int w, int h) {
        if (src.w == w && src.h == h) return src;
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int sy = Math.min(src.h - 1, y * src.h / h);
            for (int x = 0; x < w; x++) {
                int sx = Math.min(src.w - 1, x * src.w / w);
                out[y * w + x] = src.px[sy * src.w + sx];
            }
        }
        return new Gray(out, w, h);
    }

    // ---------------- 匹配 ----------------

    /**
     * 在 hay 的指定区域内滑动 needle，返回最大的归一化互相关系数（TM_CCOEFF_NORMED 等价）。
     *
     * @param x0,y0,x1,y1 搜索区域（左上闭、右下开）；传 (0,0,hay.w,hay.h) 即全图
     * @return 最佳相关系数，范围 [-1,1]，1 表示完全一致
     */
    public static double bestMatch(Gray hay, Gray needle,
                                   int x0, int y0, int x1, int y1) {
        int nw = needle.w, nh = needle.h;
        int maxX = Math.min(x1, hay.w) - nw;
        int maxY = Math.min(y1, hay.h) - nh;
        if (maxX < Math.max(0, x0) || maxY < Math.max(0, y0)) return -1.0;

        // 模板均值与去均值模板
        double tSum = 0;
        for (byte b : needle.px) tSum += b & 0xFF;
        double tMean = tSum / (nw * nh);
        double[] tZero = new double[nw * nh];
        double tVar = 0;
        for (int i = 0; i < tZero.length; i++) {
            double v = (needle.px[i] & 0xFF) - tMean;
            tZero[i] = v;
            tVar += v * v;
        }
        double tNorm = Math.sqrt(tVar);
        if (tNorm < 1e-9) return -1.0;   // 模板无对比度，无法匹配

        double best = -1.0;
        for (int oy = Math.max(0, y0); oy <= maxY; oy++) {
            for (int ox = Math.max(0, x0); ox <= maxX; ox++) {
                // 窗口均值
                double wSum = 0;
                for (int ty = 0; ty < nh; ty++) {
                    int row = (oy + ty) * hay.w + ox;
                    for (int tx = 0; tx < nw; tx++) wSum += hay.px[row + tx] & 0xFF;
                }
                double wMean = wSum / (nw * nh);

                // 相关系数
                double num = 0, den = 0;
                for (int ty = 0; ty < nh; ty++) {
                    int row = (oy + ty) * hay.w + ox;
                    int trow = ty * nw;
                    for (int tx = 0; tx < nw; tx++) {
                        double iv = (hay.px[row + tx] & 0xFF) - wMean;
                        num += tZero[trow + tx] * iv;
                        den += iv * iv;
                    }
                }
                double d = Math.sqrt(den);
                double r = (d < 1e-9) ? 0.0 : num / (tNorm * d);
                if (r > best) best = r;
            }
        }
        return best;
    }

    /** 全图搜索。 */
    public static double bestMatch(Gray hay, Gray needle) {
        return bestMatch(hay, needle, 0, 0, hay.w, hay.h);
    }
}
