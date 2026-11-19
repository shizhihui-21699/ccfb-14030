package tsidec.core;

import tsidec.instrument.BasicBlockInstrumenter;
import tsidec.instrument.Counters;
import tsidec.stats.Descriptive;
import tsidec.stats.StudentT;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个目标的分析结果。时间证据与插桩证据分列，避免用一个掩盖另一个。
 */
public final class Analysis {
    public final Target target;
    public final Measurement timing;
    public final TimingVerdict timingVerdict;

    // 插桩（确定性）证据
    public final boolean instrumented;
    public final String instrumentError;
    public final long insnsClassA;       // 第 0 类秘密下，单次调用执行的字节码条数
    public final long insnsClassB;
    public final List<BlockDiff> blockDiffs;   // 执行次数随秘密变化的基本块
    public final List<BasicBlockInstrumenter.BlockMeta> blocks;

    public Analysis(Target target, Measurement timing, TimingVerdict timingVerdict,
                    boolean instrumented, String instrumentError,
                    long insnsClassA, long insnsClassB,
                    List<BlockDiff> blockDiffs, List<BasicBlockInstrumenter.BlockMeta> blocks) {
        this.target = target; this.timing = timing; this.timingVerdict = timingVerdict;
        this.instrumented = instrumented; this.instrumentError = instrumentError;
        this.insnsClassA = insnsClassA; this.insnsClassB = insnsClassB;
        this.blockDiffs = blockDiffs; this.blocks = blocks;
    }

    /** 一个执行次数随秘密变化的基本块。 */
    public static final class BlockDiff {
        public final BasicBlockInstrumenter.BlockMeta meta;
        public final long hitsA, hitsB;
        public BlockDiff(BasicBlockInstrumenter.BlockMeta meta, long hitsA, long hitsB) {
            this.meta = meta; this.hitsA = hitsA; this.hitsB = hitsB;
        }
        public double relative() {
            long m = Math.max(hitsA, hitsB);
            return m == 0 ? 0 : (double) Math.abs(hitsA - hitsB) / m;
        }
    }

    public enum Verdict {
        LEAK_CONFIRMED,   // 时间显著 且 结构证据成立
        LEAK_STRUCTURAL,  // 结构证据成立但时间未测出（可能被噪声淹没，或差异太小）
        LEAK_TIMING_ONLY, // 时间显著但无结构证据（需人工复核，可能是缓存/数据依赖）
        NO_LEAK,          // 两者都不显著
        INCONCLUSIVE      // 测量质量不达标（漂移/批次过短/自检失败）
    }

    public Verdict verdict() { return timingVerdict.verdict; }

    /** 执行指令数的相对差异（0 表示相同）。 */
    public double insnRatio() {
        if (insnsClassA == 0 && insnsClassB == 0) return 0;
        long m = Math.max(insnsClassA, insnsClassB);
        return m == 0 ? 0 : (double) Math.abs(insnsClassA - insnsClassB) / m;
    }
}
