package tsidec.core;

/** 交替执行器：一次 round 内先跑一批 A 再跑一批 B（或反之），三次取时。 */
public interface AlternatingHarness {
    /**
     * 执行一轮。
     * @param k     每批连续调用次数
     * @param seed  本轮输入种子（两批必须用同一序列，且随轮次变化）
     * @param which 轮次序号；偶数轮先 A 后 B，奇数轮先 B 后 A
     * @return {A 批次纳秒, B 批次纳秒}
     */
    long[] round(int k, int seed, int which);
}
