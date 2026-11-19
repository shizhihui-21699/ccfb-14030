package tsidec.cli;

import tsidec.core.*;
import tsidec.pairs.SampleTargets;
import tsidec.report.JsonReport;
import tsidec.report.TextReport;
import tsidec.selfcheck.SelfCheck;
import tsidec.stats.StudentT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 命令行入口。
 *
 * 用法：
 *   java -cp out tsidec.cli.Main                        # 跑全部示例
 *   java -cp out tsidec.cli.Main --only mac,pad         # 按前缀筛选
 *   java -cp out tsidec.cli.Main --rounds 20000 --k 8192
 *   java -cp out tsidec.cli.Main --json out.json
 *   java -cp out tsidec.cli.Main --repeat 3             # 三次独立进程复测
 *
 * 退出码：0 = 全部判定与预期一致；1 = 有误判；2 = 自检未通过（测量不可信）。
 */
public final class Main {

    public static void main(String[] rawArgs) {
        Args args;
        try {
            args = Args.parse(rawArgs);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println(Args.usage());
            System.exit(64);
            return;
        }
        if (args.help) {
            System.out.println(Args.usage());
            return;
        }

        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    static int run(Args args) {
        MeasurementEngine engine = new MeasurementEngine();
        MeasurementEngine.Config cfg = new MeasurementEngine.Config();
        cfg.rounds = args.rounds;
        cfg.callsPerBatch = args.callsPerBatch; // 0 = 自动
        cfg.warmupRounds = args.warmup;
        cfg.seed = args.seed;
        cfg.timeBudgetNanos = args.budgetSeconds * 1_000_000_000L;

        System.out.println(TextReport.header(
                System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                cpuName(), new TextReport.MeasurementSummary(cfg.callsPerBatch, cfg.rounds)));

        // ---- 1. 自检 ----
        MeasurementEngine.Config calCfg = cfg.copy();
        calCfg.rounds = Math.max(2000, cfg.rounds / 2);
        SelfCheck.Result cal = SelfCheck.run(engine, calCfg);
        System.out.print(TextReport.selfCheck(cal));
        if (!cal.passed) {
            System.err.println("\n自检未通过，测量系统不可信，拒绝给出任何判定。");
            System.err.println("这通常意味着：机器负载过高、CPU 降频，或批内调用次数太少。");
            return 2;
        }
        System.out.printf("可测门槛: %.3f ns/次（≈3 条依赖 ALU 指令）%n%n",
                Analyzer.minEffectThreshold(cal));

        // ---- 2. 逐目标分析 ----
        List<Target> targets = args.selectTargets();
        Analyzer analyzer = new Analyzer(engine, cal, args.classRoot);
        List<Analysis> results = new ArrayList<>();
        for (Target t : targets) {
            Analysis a = analyzer.analyze(t, cfg);
            results.add(a);
            System.out.print(TextReport.target(a));
        }

        System.out.print(TextReport.summary(results, args.selfTestOnly ? results.size() : -1));

        // ---- 3. 报告 ----
        if (args.json != null) {
            JsonReport.MeasurementEngineMeta meta = new JsonReport.MeasurementEngineMeta();
            meta.jvm = System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version");
            meta.os = System.getProperty("os.name") + " " + System.getProperty("os.version");
            meta.cpu = cpuName();
            meta.callsPerBatch = cfg.callsPerBatch;
            meta.rounds = cfg.rounds;
            try {
                Files.writeString(args.json, JsonReport.render(cal, results, meta));
                System.out.println("JSON 报告: " + args.json.toAbsolutePath());
            } catch (Exception e) {
                System.err.println("写 JSON 失败: " + e);
            }
        }

        // ---- 4. 判定退出码 ----
        int wrong = 0;
        for (Analysis a : results) {
            Target.Expectation e = a.target.expectation();
            if (e == Target.Expectation.UNKNOWN) continue;
            boolean predictedLeak = a.verdict() != Analysis.Verdict.NO_LEAK
                    && a.verdict() != Analysis.Verdict.INCONCLUSIVE;
            boolean shouldLeak = e == Target.Expectation.LEAK;
            if (predictedLeak != shouldLeak) wrong++;
        }
        return wrong == 0 ? 0 : 1;
    }

    private static String cpuName() {
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase().contains("mac")) {
            try {
                Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                        .redirectErrorStream(true).start();
                String s = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                if (!s.isEmpty()) return s;
            } catch (Exception ignored) { }
        }
        return System.getProperty("os.arch") + " / " + Runtime.getRuntime().availableProcessors() + " cores";
    }

    // ------------------------------------------------------------------ 参数

    static final class Args {
        int rounds = 10000;
        /** 0 = 自动调优（按目标批次时长反推），默认。显式指定时不得低于 64。 */
        int callsPerBatch = 0;
        int warmup = 800;
        long seed = 0xC0FFEE;
        int budgetSeconds = 6;
        Path json;
        Path classRoot = Paths.get("out");
        String only;
        boolean help;
        boolean selfTestOnly = true;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                switch (s) {
                    case "--help", "-h" -> a.help = true;
                    case "--rounds" -> a.rounds = intArg(argv, ++i, s);
                    case "--k" -> a.callsPerBatch = intArg(argv, ++i, s);
                    case "--warmup" -> a.warmup = intArg(argv, ++i, s);
                    case "--seed" -> a.seed = Long.parseLong(argv[++i]);
                    case "--budget" -> a.budgetSeconds = intArg(argv, ++i, s);
                    case "--json" -> a.json = Paths.get(argv[++i]);
                    case "--classes" -> a.classRoot = Paths.get(argv[++i]);
                    case "--only" -> a.only = argv[++i];
                    default -> {
                        if (s.startsWith("-")) throw new IllegalArgumentException("未知选项 " + s);
                        throw new IllegalArgumentException("多余的参数 " + s);
                    }
                }
            }
            if (a.callsPerBatch != 0 && a.callsPerBatch < 64) {
                // 0 = 自动调优；显式指定时低于 64 会让批次短于时钟粒度几个数量级。
                throw new IllegalArgumentException("--k 须为 0（自动）或 ≥64，得到 " + a.callsPerBatch);
            }
            if (a.rounds < 1000) {
                throw new IllegalArgumentException("--rounds 不能小于 1000");
            }
            return a;
        }

        List<Target> selectTargets() {
            List<Target> all = new ArrayList<>(Arrays.asList(
                    SampleTargets.macUnsafe(), SampleTargets.macConstantTime(),
                    SampleTargets.tokenUnsafe(), SampleTargets.tokenConstantTime(),
                    SampleTargets.tokenLengthUnsafe(),
                    SampleTargets.paddingUnsafe(), SampleTargets.paddingConstantTime(),
                    SampleTargets.modexpUnsafe(), SampleTargets.modexpLadder(),
                    SampleTargets.modexpFullyConstantTime(),
                    SampleTargets.wipeUnsafe(), SampleTargets.wipeConstantTime()
            ));
            if (only == null) return all;
            List<String> prefixes = Arrays.asList(only.split(","));
            List<Target> picked = new ArrayList<>();
            for (Target t : all) {
                String key = t.className().substring(t.className().lastIndexOf('.') + 1).toLowerCase();
                for (String p : prefixes) {
                    if (key.startsWith(p.trim().toLowerCase())) { picked.add(t); break; }
                }
            }
            if (picked.isEmpty()) throw new IllegalArgumentException("--only 没有匹配任何目标: " + only);
            return picked;
        }

        private static int intArg(String[] argv, int i, String opt) {
            if (i >= argv.length) throw new IllegalArgumentException(opt + " 缺少值");
            try {
                return Integer.parseInt(argv[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(opt + " 需要整数，得到 " + argv[i]);
            }
        }

        static String usage() {
            return """
                用法: java -cp out tsidec.cli.Main [选项]

                  --rounds <n>    测量轮数（默认 10000，最少 1000）
                  --k <n>         每批连续调用次数（默认自动调优；显式指定须 ≥64）
                  --warmup <n>    预热轮数（默认 800）
                  --seed <n>      输入种子（默认 0xC0FFEE）
                  --budget <s>    单目标时间预算秒数（默认 6；慢函数会自动减轮）
                  --json <path>   输出 JSON 报告
                  --classes <dir> 被测类的 .class 根目录（默认 out）
                  --only <prefix> 只跑类名以指定前缀开头的目标，逗号分隔
                  -h, --help      显示本帮助

                退出码: 0=结论与预期一致  1=有误判  2=自检未通过
                """;
        }
    }
}
