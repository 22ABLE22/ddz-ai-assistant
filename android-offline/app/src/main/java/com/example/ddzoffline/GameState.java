package com.example.ddzoffline;

import android.content.Context;

import com.example.ddzoffline.ai.WeightSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 进程内共享状态：全屏 Activity 与悬浮窗 Service 共用同一个引擎实例。
 *
 * 引擎的权重从 APK 的 assets 读取，因此这里绑定 applicationContext。
 */
public final class GameState {

    private static volatile DdzEngine engine;

    private GameState() {}

    /** assets 权重来源。 */
    private static final class AssetWeightSource implements WeightSource {
        private final Context context;
        AssetWeightSource(Context c) { this.context = c; }

        @Override
        public byte[] read(String name) throws Exception {
            try (InputStream is = context.getAssets().open(name)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 22);
                byte[] tmp = new byte[1 << 16];
                int n;
                while ((n = is.read(tmp)) > 0) bos.write(tmp, 0, n);
                return bos.toByteArray();
            }
        }
    }

    /** 取得引擎（首次调用时绑定 applicationContext）。 */
    public static DdzEngine engine(Context context) {
        DdzEngine e = engine;
        if (e == null) {
            synchronized (GameState.class) {
                e = engine;
                if (e == null) {
                    e = new DdzEngine(new AssetWeightSource(context.getApplicationContext()));
                    engine = e;
                }
            }
        }
        return e;
    }

    public static DdzEngine engine() {
        DdzEngine e = engine;
        if (e == null) {
            throw new IllegalStateException("引擎尚未初始化，请先调用 engine(context)");
        }
        return e;
    }
}
