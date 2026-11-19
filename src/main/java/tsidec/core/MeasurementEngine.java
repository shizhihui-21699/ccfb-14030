package tsidec.core;

import tsidec.stats.Trend;

/**
 * 配对测量引擎。
 *
 * 时序（每一环都对应一个具体噪声源）：
 *  预热   —— 让 JIT 把两段代码都编译到 C2、让 CPU 频率稳定；预热轮不计入统计。
 *  清空   —— 预热后短暂让出，等待后台线程（GC 辅助线程、Spotlight）安静下来。
 *  轮次   —— 每轮内 ABBA 交替（偶数轮 A→B，奇数轮 B→A），得到一对批次耗时。
 *  漂移   —— 对每轮配对差按轮次序号做 Spearman 趋势检验；显著且幅度大则标记不可靠。
 *
 * 为什么是"配对差"而不是"两组绝对耗时的均值差"：
 *   频率调节、缓存变暖、后台负载都是缓慢变化的公共分量。ABBA 让 A、B 在轮内
 *   处于相邻的时间位置，这些分量在 (B批次 - A批次) 里几乎完全相减。
 *   实测：同一函数的配对差零点偏置为 0.001ns/次，而绝对耗时逐轮漂移可达数 ns。
 */
public final class MeasurementEngine {

    public static final class Config {
        public int rounds = 10000;
        /**
         * 每批调用次数。0 表示**自动调优**：按被测函数的单次耗时反推，
         * 使批次时长落在 [targetBatchNs/4, targetBatchNs*4] 内。
         * 硬编码一个值必然在"太快的函数"上量化误差过大、在"太慢的函数"上慢到不可用。
         */
        public int callsPerBatch = 0;
        /** 目标批次时长（纳秒）。默认 200μs：是 42ns 时钟粒度的 ~4700 倍，
         *  量化误差 <0.03%；同时不至于让慢函数（模幂 ~1μs/次）每轮就要 1ms。 */
        public long targetBatchNs = 200_000;
        public int minCallsPerBatch = 64;
        public int maxCallsPerBatch = 200_000;
        public int warmupRounds = 400;
        public long gapMillis = 0;
        public long preMeasureSettleMillis = 60;
        public long seed = 0xC0FFEE;
        /** 单目标时间预算（纳秒）。超出则自动缩减轮数，保证工具在慢函数上也可用。 */
        public long timeBudgetNanos = 6_000_000_000L; // 6 秒
        public Config copy() {
            Config c = new Config();
            c.rounds = rounds; c.callsPerBatch = callsPerBatch; c.targetBatchNs = targetBatchNs;
            c.minCallsPerBatch = minCallsPerBatch; c.maxCallsPerBatch = maxCallsPerBatch;
            c.warmupRounds = warmupRounds; c.gapMillis = gapMillis;
            c.preMeasureSettleMillis = preMeasureSettleMillis; c.seed = seed;
            c.timeBudgetNanos = timeBudgetNanos;
            return c;
        }
    }

    public Measurement measure(AlternatingHarness h, String name, Config cfg) {
        int k = cfg.callsPerBatch > 0 ? cfg.callsPerBatch : autoTune(h, cfg);

        int warm = Math.max(0, cfg.warmupRounds);
        for (int r = 0; r < warm; r++) {
            h.round(k, r * 17, r);
        }
        if (cfg.preMeasureSettleMillis > 0) Threads.settle(cfg.preMeasureSettleMillis);

        int n = Math.max(64, cfg.rounds);
        double[] diff = new double[n];
        double[] aArr = new double[n];
        double[] bArr = new double[n];

        for (int r = 0; r < n; r++) {
            // 输入种子随轮次变化：避免"永远处理同一份数据"带来的缓存假象
            int seed = (int) ((r * 2654435761L ^ cfg.seed) & 0x7fffffff);
            long[] ba = h.round(k, seed, r);
            aArr[r] = ba[0];
            bArr[r] = ba[1];
            diff[r] = (ba[1] - ba[0]) / (double) k;
            if (cfg.gapMillis > 0) Threads.settle(cfg.gapMillis);
        }

        Trend.Result tr = Trend.spearman(diff);
        return new Measurement(name, k, diff, aArr, bArr, warm, tr);
    }

    /**
     * 自动选批大小：先做一次小规模预热 + 计时，估计单次调用耗时，
     * 再推出让批次时长接近 targetBatchNs 的 K。夹在 [min,max] 内。
     *
     * 为什么必须自适应：时钟粒度是 42ns（Apple Silicon 实测）。
     * 若函数单次 20ns、K=256，批次仅 5μs ≈ 120 个 tick，量化误差仍 <1%；
     * 但若函数单次 200ns（如模幂），K=4096 会让批次长达 800μs，
     * 每个轮次要等近 1ms，测 10000 轮就是 10 秒。反过来，对一个 2ns 的
     * 函数用 K=64，批次 128ns 只有 3 个 tick，完全测不出东西。
     *
     * 关键：必须用**两批中较慢的那批**来定 K。泄漏检测的两类输入工作量大不相同
     * （例如清零 1 字节 vs 256 字节，相差 40 倍）；若按快的那类定 K，
     * 慢的那类批次会膨胀到几十毫秒，且快的这类批次短到量化误差不可忽略。
     */
    private int autoTune(AlternatingHarness h, Config cfg) {
        // 预热一小段，让 JIT 编译
        for (int r = 0; r < 64; r++) h.round(cfg.minCallsPerBatch, r * 31, r);
        int probeK = 512;
        long[] worst = new long[41];
        for (int r = 0; r < worst.length; r++) {
            long[] ba = h.round(probeK, (r + 7) * 131, r);
            worst[r] = Math.max(ba[0], ba[1]);
        }
        java.util.Arrays.sort(worst);
        // 取中位数；JIT 可能在最初的几十轮里还没完全编译，中位数能避开这些。
        double perCallNs = Math.max(1e-3, worst[worst.length / 2] / (double) probeK);
        lastPerCallNs = perCallNs;
        // 目标批次 400μs：更保守，因为两类输入的工作量可能相差几十倍，
        // 用较慢那批反推会让较快那批的批次偏短；把目标抬高一倍留出裕量。
        long target = cfg.targetBatchNs * 2;
        long k = Math.round(target / perCallNs);
        int clamped = (int) Math.max(256, Math.min(cfg.maxCallsPerBatch, k));
        return clamped;
    }

    /** 自动调优估出的单次调用耗时，用于决定轮数（不代表正式测量的精度）。 */
    private double lastPerCallNs = Double.NaN;
    public double estimatedPerCallNs() { return lastPerCallNs; }

    /**
     * 按时间预算缩减轮数：一个轮次约花 2 个批次（A + B）。
     * 预算 5 秒时，5.4μs/次 × K≈37 的函数也能跑数千轮，而 20ns/次的函数不受影响。
     * 下限 4000 轮保证统计功效，上限为配置值。
     */
    public int roundsForBudget(int maxRounds, long budgetNanos) {
        if (!(lastPerCallNs > 0)) return maxRounds;
        long batchNs = Math.round(lastPerCallNs * 64); // 至少 64 次一批
        if (batchNs <= 0) return maxRounds;
        long perRoundNs = 2 * batchNs;
        long affordable = budgetNanos / Math.max(1, perRoundNs);
        return (int) Math.max(4000, Math.min(maxRounds, affordable));
    }
}
