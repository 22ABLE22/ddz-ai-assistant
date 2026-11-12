package com.example.ddzoffline.ai;

/**
 * 单层 LSTM 前向，语义与 PyTorch nn.LSTM(batch_first=True) 完全一致。
 *
 * PyTorch 把 4 个门的权重打包成 (4*hidden, input)，chunk 顺序固定为：
 *   [0] i 输入门   [1] f 遗忘门   [2] g 候选   [3] o 输出门
 *
 * 递推公式：
 *   i = σ(Wii·x + bii + Whi·h + bhi)
 *   f = σ(Wif·x + bif + Whf·h + bhf)
 *   g = tanh(Wig·x + big + Whg·h + bhg)
 *   o = σ(Wio·x + bio + Who·h + bho)
 *   c' = f*c + i*g
 *   h' = o*tanh(c')
 *
 * 注意：PyTorch 的 weight_ih_l0 / weight_hh_l0 都是 (4H, ·) 行主序，
 * 第 j 个门的权重位于第 [j*H, (j+1)*H) 行。
 */
public final class Lstm {

    public final int inputSize;
    public final int hiddenSize;

    private final float[] wih;   // (4H, input)
    private final float[] whh;   // (4H, hidden)
    private final float[] bih;   // (4H)
    private final float[] bhh;   // (4H)

    public Lstm(int inputSize, int hiddenSize,
                float[] wih, float[] whh, float[] bih, float[] bhh) {
        this.inputSize = inputSize;
        this.hiddenSize = hiddenSize;
        this.wih = wih;
        this.whh = whh;
        this.bih = bih;
        this.bhh = bhh;
        if (wih.length != 4 * hiddenSize * inputSize) {
            throw new IllegalArgumentException("wih 尺寸错误: " + wih.length);
        }
        if (whh.length != 4 * hiddenSize * hiddenSize) {
            throw new IllegalArgumentException("whh 尺寸错误: " + whh.length);
        }
        if (bih.length != 4 * hiddenSize || bhh.length != 4 * hiddenSize) {
            throw new IllegalArgumentException("bias 尺寸错误");
        }
    }

    /** 一个时间步的完整前向；h 与 c 原地更新。 */
    private void step(float[] x, int xOff, float[] h, float[] c) {
        final int H = hiddenSize;
        final int I = inputSize;
        float[] g = new float[4 * H];

        // gates = Wih·x + bih + Whh·h + bhh
        for (int row = 0; row < 4 * H; row++) {
            final int wiBase = row * I;
            float acc = bih[row] + bhh[row];
            for (int i = 0; i < I; i++) {
                acc += wih[wiBase + i] * x[xOff + i];
            }
            final int whBase = row * H;
            for (int j = 0; j < H; j++) {
                acc += whh[whBase + j] * h[j];
            }
            g[row] = acc;
        }

        // 逐门激活并更新 c、h
        for (int j = 0; j < H; j++) {
            float iGate = sigmoid(g[j]);
            float fGate = sigmoid(g[H + j]);
            float gGate = tanh(g[2 * H + j]);
            float oGate = sigmoid(g[3 * H + j]);
            float cNew = fGate * c[j] + iGate * gGate;
            c[j] = cNew;
            h[j] = oGate * tanh(cNew);
        }
    }

    /**
     * 序列前向，返回最后一个时间步的 h。
     * 与 PyTorch `lstm_out[:, -1, :]` 等价。
     *
     * @param z 形状 (seqLen, inputSize) 行主序
     */
    public float[] forwardLast(float[] z, int seqLen) {
        final int H = hiddenSize;
        float[] h = new float[H];
        float[] c = new float[H];
        float[] x = new float[inputSize];
        for (int t = 0; t < seqLen; t++) {
            System.arraycopy(z, t * inputSize, x, 0, inputSize);
            step(x, 0, h, c);
        }
        return h;
    }

    /**
     * 批量前向：所有样本共享同一条 z 序列（DouZero 中 z_batch 是 z 的重复）。
     * 只需算一次 LSTM，再复制给 batch 内每一行 —— 与原实现数值一致但省算力。
     */
    public void forwardLastShared(float[] z, int seqLen, Tensor out) {
        float[] h = forwardLast(z, seqLen);
        for (int r = 0; r < out.rows; r++) {
            System.arraycopy(h, 0, out.data, r * out.cols, hiddenSize);
        }
    }

    static float sigmoid(float v) {
        return (float) (1.0 / (1.0 + Math.exp(-v)));
    }

    static float tanh(float v) {
        return (float) Math.tanh(v);
    }
}
