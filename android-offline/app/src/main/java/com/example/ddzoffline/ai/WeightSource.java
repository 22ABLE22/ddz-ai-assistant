package com.example.ddzoffline.ai;

/**
 * 权重来源抽象。
 *
 * 存在的理由：让 {@link com.example.ddzoffline.DdzEngine} 不直接依赖 android.content.Context，
 * 从而可以在纯 JVM 上跑真实引擎（而非测试副本）做数值一致性验证。
 *
 * Android 实现从 assets 读；JVM 测试实现从文件系统读。
 */
public interface WeightSource {

    /** 读取指定资源名的原始字节（little-endian float32 序列）。 */
    byte[] read(String name) throws Exception;
}
