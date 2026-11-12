package com.example.ddzoffline.ai;

import java.util.Arrays;

/**
 * 最小张量：行主序 float 二维矩阵。
 *
 * 设计原则：
 *  - 只实现 DouZero 推理需要的操作（矩阵乘、偏置加、ReLU、LSTM、拼接）
 *  - 单线程、无分配复用：推理一次只分配必要缓冲
 *  - 不依赖任何第三方库（Android 上零依赖）
 */
public final class Tensor {

    public final int rows;
    public final int cols;
    public final float[] data;

    public Tensor(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.data = new float[rows * cols];
    }

    public Tensor(int rows, int cols, float[] data) {
        if (data.length != rows * cols) {
            throw new IllegalArgumentException("data 长度 " + data.length + " != " + rows + "*" + cols);
        }
        this.rows = rows;
        this.cols = cols;
        this.data = data;
    }

    public float get(int r, int c) { return data[r * cols + c]; }
    public void set(int r, int c, float v) { data[r * cols + c] = v; }

    /**
     * 本矩阵 (rows x cols) 乘以权重矩阵 w (out x cols) 的转置，得到 (rows x out)。
     * 等价 PyTorch 的 x @ w.T —— 这是 nn.Linear 的语义。
     *
     * w 以 (out, cols) 行主序存储，每行是一个输出神经元的权重。
     */
    public Tensor matmulTransposedWeight(Tensor w, Tensor bias, boolean relu) {
        if (w.cols != this.cols) {
            throw new IllegalArgumentException(
                    "维度不匹配: x(" + rows + "x" + cols + ") @ w.T(" + w.rows + "x" + w.cols + ")");
        }
        int out = w.rows;
        Tensor y = new Tensor(rows, out);
        final float[] xd = this.data;
        final float[] wd = w.data;
        final float[] yd = y.data;
        final int in = this.cols;

        for (int r = 0; r < rows; r++) {
            final int xBase = r * in;
            final int yBase = r * out;
            // 对每个输出神经元做点积；内层按行连续访问权重，缓存友好
            for (int o = 0; o < out; o++) {
                final int wBase = o * in;
                float acc = (bias != null) ? bias.data[o] : 0f;
                for (int i = 0; i < in; i++) {
                    acc += xd[xBase + i] * wd[wBase + i];
                }
                yd[yBase + o] = relu ? (acc > 0f ? acc : 0f) : acc;
            }
        }
        return y;
    }

    /** 沿最后一维拼接两个同高度矩阵。 */
    public static Tensor concatCols(Tensor a, Tensor b) {
        if (a.rows != b.rows) {
            throw new IllegalArgumentException("拼接要求行数一致: " + a.rows + " vs " + b.rows);
        }
        Tensor out = new Tensor(a.rows, a.cols + b.cols);
        for (int r = 0; r < a.rows; r++) {
            System.arraycopy(a.data, r * a.cols, out.data, r * out.cols, a.cols);
            System.arraycopy(b.data, r * b.cols, out.data, r * out.cols + a.cols, b.cols);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Tensor(" + rows + "x" + cols + ")";
    }

    /** 调试用：前 n 个值。 */
    public String head(int n) {
        int k = Math.min(n, data.length);
        return Arrays.toString(Arrays.copyOf(data, k));
    }
}
