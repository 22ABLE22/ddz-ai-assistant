package com.example.ddzoffline.ai;

/**
 * DouZero 网络前向：LSTM(162,128) -> 拼接 -> 5 层 Dense(512)+ReLU -> Dense(512,1)。
 *
 * 地主与农民结构完全相同，只有第一层 Dense 的输入维度不同：
 *   地主  dense1 = Linear(128+373, 512)   -> inDim 373
 *   农民  dense1 = Linear(128+484, 512)   -> inDim 484
 *
 * 权重文件按固定顺序拼接（见 tools/export_android.py 的 TENSOR_ORDER），
 * 因此偏移量可由 inDim 推导，无需在设备上解析 JSON。
 */
public final class DdzModel {

    public static final int LSTM_INPUT = 162;
    public static final int LSTM_HIDDEN = 128;
    public static final int DENSE_HIDDEN = 512;

    public final int inDim;              // 373 或 484
    private final Lstm lstm;
    private final Tensor d1w, d1b, d2w, d2b, d3w, d3b, d4w, d4b, d5w, d5b, d6w, d6b;

    private DdzModel(int inDim, Lstm lstm, Tensor[] d) {
        this.inDim = inDim;
        this.lstm = lstm;
        this.d1w = d[0]; this.d1b = d[1];
        this.d2w = d[2]; this.d2b = d[3];
        this.d3w = d[4]; this.d3b = d[5];
        this.d4w = d[6]; this.d4b = d[7];
        this.d5w = d[8]; this.d5b = d[9];
        this.d6w = d[10]; this.d6b = d[11];
    }

    /** 地主模型所需的第一层输入维度。 */
    public static int inDimFor(String position) {
        return "landlord".equals(position) ? 373 : 484;
    }

    /**
     * 从已读取的 float 权重块构建模型。
     *
     * @param blob 按 TENSOR_ORDER 顺序拼接的权重（little-endian float32 已转 float）
     * @param inDim 373（地主）或 484（农民）
     */
    public static DdzModel fromBlob(float[] blob, int inDim) {
        final int H = LSTM_HIDDEN, I = LSTM_INPUT, D = DENSE_HIDDEN;
        int p = 0;

        int nWih = 4 * H * I;
        float[] wih = slice(blob, p, nWih); p += nWih;
        int nWhh = 4 * H * H;
        float[] whh = slice(blob, p, nWhh); p += nWhh;
        float[] bih = slice(blob, p, 4 * H); p += 4 * H;
        float[] bhh = slice(blob, p, 4 * H); p += 4 * H;

        int d1in = H + inDim;
        Tensor[] d = new Tensor[12];
        d[0] = take(blob, p, D, d1in); p += D * d1in;
        d[1] = take(blob, p, 1, D);    p += D;
        for (int layer = 2; layer <= 5; layer++) {
            d[(layer - 1) * 2] = take(blob, p, D, D); p += D * D;
            d[(layer - 1) * 2 + 1] = take(blob, p, 1, D); p += D;
        }
        d[10] = take(blob, p, 1, D); p += D;
        d[11] = take(blob, p, 1, 1); p += 1;

        if (p != blob.length) {
            throw new IllegalArgumentException(
                    "权重长度不符：用掉 " + p + "，实际 " + blob.length + "（inDim=" + inDim + "）");
        }
        return new DdzModel(inDim, new Lstm(I, H, wih, whh, bih, bhh), d);
    }

    /** 期望的权重元素总数，用于加载时快速校验。 */
    public static int expectedParamCount(int inDim) {
        int H = LSTM_HIDDEN, I = LSTM_INPUT, D = DENSE_HIDDEN;
        return 4 * H * I + 4 * H * H + 4 * H + 4 * H
                + D * (H + inDim) + D
                + 4 * (D * D + D)
                + D + 1;
    }

    /**
     * 前向推理。
     *
     * @param z 形状 (5,162) 行主序（历史出牌编码）
     * @param x 形状 (n, inDim) 行主序（每个候选动作一行）
     * @return 每个候选动作的 value，长度 n
     */
    public float[] forward(float[] z, Tensor x) {
        if (x.cols != inDim) {
            throw new IllegalArgumentException("x.cols=" + x.cols + " 应为 " + inDim);
        }
        // LSTM 输出对 batch 内所有行相同（z 不随候选变化），只算一次再广播
        Tensor lstmOut = new Tensor(x.rows, LSTM_HIDDEN);
        lstm.forwardLastShared(z, 5, lstmOut);

        Tensor h = Tensor.concatCols(lstmOut, x);          // (n, 128+inDim)
        h = h.matmulTransposedWeight(d1w, d1b, true);
        h = h.matmulTransposedWeight(d2w, d2b, true);
        h = h.matmulTransposedWeight(d3w, d3b, true);
        h = h.matmulTransposedWeight(d4w, d4b, true);
        h = h.matmulTransposedWeight(d5w, d5b, true);
        h = h.matmulTransposedWeight(d6w, d6b, false);      // (n,1)

        float[] out = new float[x.rows];
        for (int r = 0; r < x.rows; r++) {
            out[r] = h.data[r];
        }
        return out;
    }

    private static float[] slice(float[] src, int off, int len) {
        float[] out = new float[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static Tensor take(float[] src, int off, int rows, int cols) {
        return new Tensor(rows, cols, slice(src, off, rows * cols));
    }
}
