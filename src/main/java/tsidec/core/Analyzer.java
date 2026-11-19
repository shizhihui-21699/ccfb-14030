package tsidec.core;

import tsidec.instrument.BasicBlockInstrumenter;
import tsidec.instrument.Counters;
import java.util.ArrayList;
import tsidec.stats.SignFlipTest;
import tsidec.stats.StudentT;
import tsidec.selfcheck.SelfCheck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 分析器：对一个目标同时做
 *   (1) 时间测量 —— ABBA 配对，判"耗时是否随秘密变化"
 *   (2) 插桩计数 —— 基本块执行次数，判"执行路径是否随秘密变化"
 *
 * 判定规则见 {@link #decide}。核心原则：
 *   结构证据（计数差异）优先于时间证据。计数是确定性的，不会被噪声污染；
 *   时间是定量的，但只有在结构证据支持下才能用来解释"有多容易被利用"。
 *   当两者矛盾时，输出"需人工复核"而不是强行下结论。
 */
public final class Analyzer {
    private final MeasurementEngine engine;
    private final SelfCheck.Result calibration;
    private final Path classRoot;

    public Analyzer(MeasurementEngine engine, SelfCheck.Result calibration, Path classRoot) {
        this.engine = engine;
        this.calibration = calibration;
        this.classRoot = classRoot;
    }

    public Analysis analyze(Target target, MeasurementEngine.Config cfg) {
        // ---- 1) 时间 ----
        long[] sink = new long[1];
        TargetBinder.Bound bound = TargetBinder.bind(target, Analyzer.class.getClassLoader());
        SecretPairHarness harness = new SecretPairHarness(target, bound, sink, poolSize(target));

        // 轮数取决于函数快慢；先做一次极短的计时（含自动调优），据此套预算。
        // 注意：不把这次估出的 K 复用给正式测量——预热后的 JIT 状态与冷启动不同，
        // 复用会让批次偏离目标（曾因此产生 4μs 的过短批次）。让正式测量自己再调一次，
        // autoTune 只需几十轮，代价可忽略。
        MeasurementEngine.Config probe = cfg.copy();
        probe.rounds = 300;
        engine.measure(harness, target.name(), probe);
        int rounds = engine.roundsForBudget(cfg.rounds, cfg.timeBudgetNanos);

        MeasurementEngine.Config actual = cfg.copy();
        actual.rounds = rounds;
        Measurement m = engine.measure(harness, target.name(), actual);

        // 每目标零点对照：用同一类秘密对自身配对（A=B=类 0），真值严格为 0。
        // 它捕获"这个目标本身的批结构/输入池/缓存布局"造成的系统性不对称，
        // 比只用全局校准更贴近实际。轮数取正式的 1/4 即可（只需估偏置，不需功效）。
        MeasurementEngine.Config zeroCfg = actual.copy();
        zeroCfg.rounds = Math.max(1000, rounds / 4);
        SecretPairHarness zeroHarness = new SecretPairHarness(target, bound, sink, poolSize(target), 0, 0);
        Measurement zero = engine.measure(zeroHarness, target.name() + "/zero", zeroCfg);

        TimingVerdict tv = decide(m, calibration, zero, engine.estimatedPerCallNs());

        // ---- 2) 插桩 ----
        Instrumentation ins = runInstrumentation(target, bound);

        Analysis.Verdict v = combine(tv, ins);
        TimingVerdict finalTv = new TimingVerdict(v, tv.effectNs, tv.se, tv.tStat, tv.welchP, tv.permP,
                tv.trimmedEffectNs, tv.trimmedSe, tv.stable, tv.qualityOk, tv.reason);

        return new Analysis(target, m, finalTv,
                ins.ok, ins.error, ins.insnsA, ins.insnsB, ins.blockDiffs, ins.blocks);
    }

    private static int poolSize(Target t) {
        // 输入池越大，缓存驻留模式越贴近真实；同时避免同一输入被反复命中。
        return 64;
    }

    // ------------------------------------------------------------------ 时间判定

    /**
     * @param minEffectNs 最小效应量门槛。取 3 条依赖 ALU 指令的等效时间——
     *        比它小的差异在实际攻击中几乎无法利用（需要上亿次采样）。
     */
    public static double minEffectThreshold(SelfCheck.Result cal) {
        double perOp = cal.nsPerAluOp;
        if (!(perOp > 0) || !Double.isFinite(perOp)) return 0.5;
        return Math.max(0.15, 3 * perOp);
    }

    static TimingVerdict decide(Measurement m, SelfCheck.Result cal) {
        return decide(m, cal, null, Double.NaN);
    }

    /**
     * @param zero 该目标自身的零点对照（A=B=类 0），可为 null
     * @param perCallNs 自动调优估出的单次耗时
     */
    static TimingVerdict decide(Measurement m, SelfCheck.Result cal, Measurement zero, double perCallNs) {
        int n = m.rounds();
        // 点估计与 SE 必须来自同一个统计量，否则 t 值与效应量会互相矛盾。
        // 统一用"截尾均值"：去掉偏离中位数最远的 10% 轮次，压制 GC/抢占长尾；
        // 中位数只作为报告里的稳健参照点，不参与显著性计算。
        double effect = m.trimmedMean;
        double se = m.trimmedSe;
        double t = se > 0 ? effect / se : 0;
        double welchP = StudentT.twoSidedP(t, Math.max(1, m.trimmedN - 1));
        // 配对设计的正确零假设检验是符号翻转，不是"前半 vs 后半"两样本比较。
        // 后者会把漂移误当组间差异，实测把 t=1041 的确定泄漏算成 p=0.10。
        double permP = SignFlipTest.test(m.perRoundDiffNs, 4000, new Random(12345)).pValue;

        // 批次门槛取 8μs：在 42ns 时钟粒度下相当于约 190 个 tick，量化误差 <0.6%。
        // 20μs 会把 17μs 的合法批次误判为"质量不达标"——层次设得比物理需要更严，
        // 只会把真泄漏判成"无法判定"，是另一种漏报。
        // 但如果函数非常慢（如 5.4μs/次的定延迟模幂），自适应的 K 会被压到 64，
        // 批次仍有 350μs，此时门槛不构成问题；判据保持绝对时长即可。
        double batchNs = m.minBatchNs();
        boolean batchTooShort = batchNs > 0 && batchNs < 8_000;
        boolean quality = !m.driftSuspicious && !batchTooShort;

        // 稳定性只用来【否决】一个阳性结论，不用来【要求】一个阳性结论：
        // 真值为 0 时两半均值必然随机异号，若要求同号反而会把稳健的阴性拖成"不稳定"。
        double[] halves = m.splitHalfEffects();
        double hMax = Math.max(Math.abs(halves[0]), Math.abs(halves[1]));
        boolean unstable = (halves[0] * halves[1] < 0) && hMax > 4 * se;

        double minEff = minEffectThreshold(cal);
        // 偏置优先取"本目标自己的零点对照"，它捕获了该目标的批结构/缓存布局不对称。
        // 退回全局校准只在对照缺失时发生（如慢函数预算不足）。
        double bias, biasSe;
        String biasSrc;
        if (zero != null && zero.rounds() >= 500) {
            bias = zero.trimmedMean;
            biasSe = zero.trimmedSe;
            biasSrc = "本目标零点";
        } else {
            bias = cal.biasNs;
            biasSe = cal.biasSe;
            biasSrc = "全局校准";
        }
        double seEff = Math.sqrt(se * se + biasSe * biasSe);
        double excess = Math.abs(effect - bias);

        boolean statSig = welchP < 1e-6 && permP < 1e-3 && Math.abs(t) > 6;
        boolean bigEnough = excess > minEff && excess > 5 * seEff;

        if (!quality) {
            return new TimingVerdict(Analysis.Verdict.INCONCLUSIVE, effect, se, t, welchP, permP,
                    m.trimmedMean, m.trimmedSe, !unstable, false,
                    "测量质量不达标：" + m.qualityNote());
        }
        if (statSig && bigEnough && !unstable) {
            return new TimingVerdict(Analysis.Verdict.LEAK_TIMING_ONLY, effect, se, t, welchP, permP,
                    m.trimmedMean, m.trimmedSe, true, true,
                    String.format("耗时随秘密变化：效应 %+.3f ns/次（%.1f×最小效应量），超出%s偏置 %.1fσ",
                            effect, excess / minEff, biasSrc, excess / seEff));
        }
        String why = !statSig ? "统计不显著"
                : !bigEnough ? String.format("效应量 %.3f ns 低于门槛 %.3f ns（已扣除%s偏置 %+.3f）",
                        excess, minEff, biasSrc, bias)
                : "两半估计异号且幅度可观（不稳定）";
        return new TimingVerdict(Analysis.Verdict.NO_LEAK, effect, se, t, welchP, permP,
                m.trimmedMean, m.trimmedSe, !unstable, true,
                String.format("未测出耗时差异：效应 %+.3f ns/次，%s", effect, why));
    }

    // ------------------------------------------------------------------ 插桩判定

    static final class Instrumentation {
        boolean ok;
        String error;
        long insnsA, insnsB;
        List<Analysis.BlockDiff> blockDiffs = new ArrayList<>();
        List<BasicBlockInstrumenter.BlockMeta> blocks = List.of();
    }

    private Instrumentation runInstrumentation(Target target, TargetBinder.Bound bound) {
        Instrumentation out = new Instrumentation();
        try {
            String internal = target.className().replace('.', '/');
            Path classFile = classRoot.resolve(internal + ".class");
            byte[] original = Files.readAllBytes(classFile);

            BasicBlockInstrumenter instr = new BasicBlockInstrumenter(0);
            BasicBlockInstrumenter.Result res = instr.instrument(original,
                    name -> name.equals(target.methodName()));
            if (res.blocks.isEmpty()) {
                out.error = "没有可插桩的基本块（方法未找到？）";
                return out;
            }

            int maxId = 0;
            for (var b : res.blocks) maxId = Math.max(maxId, b.id + 1);
            int[] sizes = new int[maxId];
            for (var b : res.blocks) sizes[b.id] = b.insnCount;
            Counters.configure(sizes);
            out.blocks = res.blocks;

            Map<String, byte[]> repl = new HashMap<>();
            repl.put(internal, res.bytecode);
            ClassLoader loader = new IsolatingLoader(repl, Analyzer.class.getClassLoader());
            Class<?> k = Class.forName(target.className(), true, loader);
            java.lang.reflect.Method m = k.getDeclaredMethod(target.methodName(), target.paramTypes());
            m.setAccessible(true);

            int trials = 200;
            long[] totalA = new long[1], totalB = new long[1];
            long[] hitsA = null, hitsB = null;

            // 第 0 类秘密
            Counters.resetCounts();
            for (int i = 0; i < trials; i++) m.invoke(null, target.argsFor(0, i));
            totalA[0] = Counters.total();
            hitsA = Counters.hitsSnapshot();

            // 第 1 类秘密
            Counters.resetCounts();
            for (int i = 0; i < trials; i++) m.invoke(null, target.argsFor(1, i));
            totalB[0] = Counters.total();
            hitsB = Counters.hitsSnapshot();

            out.ok = true;
            out.insnsA = totalA[0] / trials;
            out.insnsB = totalB[0] / trials;

            for (var b : res.blocks) {
                long hA = hitsA[b.id] / trials, hB = hitsB[b.id] / trials;
                if (hA != hB) out.blockDiffs.add(new Analysis.BlockDiff(b, hA, hB));
            }
            out.blockDiffs.sort((x, y) -> Double.compare(y.relative(), x.relative()));
            return out;
        } catch (Throwable t) {
            out.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return out;
        }
    }

    /** 只替换目标类的隔离加载器；其余走父加载器。 */
    private static final class IsolatingLoader extends ClassLoader {
        private final Map<String, byte[]> repl;
        IsolatingLoader(Map<String, byte[]> repl, ClassLoader parent) {
            super(parent);
            this.repl = repl;
        }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String in = name.replace('.', '/');
            if (repl.containsKey(in)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        byte[] b = repl.get(in);
                        c = defineClass(name, b, 0, b.length);
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    // ------------------------------------------------------------------ 合并

    static Analysis.Verdict combine(TimingVerdict tv, Instrumentation ins) {
        if (tv.verdict == Analysis.Verdict.INCONCLUSIVE) return Analysis.Verdict.INCONCLUSIVE;

        boolean structural = ins.ok && !ins.blockDiffs.isEmpty();
        // 时间侧任何"随秘密变化"的阳性结论都算 timing 阳性；
        // decide() 只产出 LEAK_TIMING_ONLY / NO_LEAK 两种，这里统一处理。
        boolean timingLeak = tv.verdict == Analysis.Verdict.LEAK_TIMING_ONLY;

        if (structural && timingLeak) return Analysis.Verdict.LEAK_CONFIRMED;
        if (structural) return Analysis.Verdict.LEAK_STRUCTURAL;
        if (timingLeak) return Analysis.Verdict.LEAK_TIMING_ONLY;
        return Analysis.Verdict.NO_LEAK;
    }
}
