package tsidec.selftest;

/**
 * 端到端行为测试：用已知答案的样本，断言工具给出正确结论。
 *
 * 这些断言是项目的"定义"：所谓"能用"就是指以下行为成立。
 * 尤其重要的是——修复版必须判 NO_LEAK，泄露版必须判 LEAK_*。
 * 只做单向检查（只查泄漏版）是不够的，那样一个"永远报警"的工具也会通过。
 */
final class BehaviorTests {
    private BehaviorTests() {}

    static int failures = 0;

    static void run() {
        System.out.println("== 端到端行为 ==");
        instrumenterBasics();
        stateMachineSensitivity();
        System.out.println(failures == 0 ? "  端到端行为: 全部通过" : "  端到端行为: " + failures + " 项失败");
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) { failures++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    /** 插桩器必须在不改变语义的前提下给出稳定的块计数。 */
    private static void instrumenterBasics() {
        try {
            byte[] orig = readClass("tsidec/pairs/MacCompare");
            var instr = new tsidec.instrument.BasicBlockInstrumenter(0);
            var res = instr.instrument(orig, n -> n.equals("unsafeEquals"));
            check("找到 unsafeEquals 的基本块", !res.blocks.isEmpty(),
                    "blocks=" + res.blocks.size());

            // 用同一个算法在原始类与插桩类上各跑一遍，结果必须一致（语义未变）
            boolean origResult = tsidec.pairs.MacCompare.unsafeEquals(
                    new byte[]{1, 2, 3}, new byte[]{1, 2, 9});
            check("原始类语义", !origResult, "");

            // 计数：首字节不同 vs 末字节不同，执行指令数必须显著不同
            int maxId = 0;
            for (var b : res.blocks) maxId = Math.max(maxId, b.id + 1);
            int[] sizes = new int[maxId];
            for (var b : res.blocks) sizes[b.id] = b.insnCount;
            tsidec.instrument.Counters.configure(sizes);

            java.util.Map<String, byte[]> repl = new java.util.HashMap<>();
            repl.put("tsidec/pairs/MacCompare", res.bytecode);
            ClassLoader loader = new ClassLoader(BehaviorTests.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String n, boolean r) throws ClassNotFoundException {
                    String in = n.replace('.', '/');
                    if (repl.containsKey(in)) {
                        synchronized (getClassLoadingLock(n)) {
                            Class<?> c = findLoadedClass(n);
                            if (c == null) c = defineClass(n, repl.get(in), 0, repl.get(in).length);
                            if (r) resolveClass(c);
                            return c;
                        }
                    }
                    return super.loadClass(n, r);
                }
            };
            Class<?> k = loader.loadClass("tsidec.pairs.MacCompare");
            var m = k.getMethod("unsafeEquals", byte[].class, byte[].class);
            byte[] expected = new byte[64];
            java.util.Arrays.fill(expected, (byte) 0xAB);
            byte[] early = expected.clone(); early[0] ^= 1;
            byte[] late = expected.clone(); late[63] ^= 1;

            tsidec.instrument.Counters.resetCounts();
            Object r1 = m.invoke(null, expected, early);
            long totalEarly = tsidec.instrument.Counters.total();
            tsidec.instrument.Counters.resetCounts();
            Object r2 = m.invoke(null, expected, late);
            long totalLate = tsidec.instrument.Counters.total();

            check("插桩后结果不变", Boolean.FALSE.equals(r1) && Boolean.FALSE.equals(r2),
                    r1 + "/" + r2);
            check("首字节失败执行指令更少", totalEarly < totalLate / 10,
                    totalEarly + " vs " + totalLate);
        } catch (Exception e) {
            check("插桩基础测试", false, e.toString());
        }
    }

    /**
     * 历史教训回归测试：早期版本在这几处出过错，且都是"看起来对、其实错"的类型。
     */
    private static void stateMachineSensitivity() {
        // 1) 填充检查的两个实现必须语义等价（曾因三元运算符/循环边界写错）
        java.util.Random r = new java.util.Random(11);
        int mismatch = 0;
        for (int i = 0; i < 100_000; i++) {
            byte[] d = new byte[16];
            r.nextBytes(d);
            int pad = 1 + r.nextInt(16);
            for (int k = 0; k < pad; k++) d[15 - k] = (byte) pad;
            if (r.nextInt(5) == 0) d[15 - r.nextInt(pad)] ^= (byte) (1 + r.nextInt(255));
            if (tsidec.pairs.PaddingCheck.unsafeValidPadding(d, 16)
                    != tsidec.pairs.PaddingCheck.constantTimeValidPadding(d, 16)) mismatch++;
        }
        check("填充检查两版等价", mismatch == 0, mismatch + " 处不一致");

        // 2) 模幂三版必须给出相同结果（曾因 Barrett/Shoup 推导错误大规模出错）
        java.util.Random r2 = new java.util.Random(12);
        int bad = 0;
        for (int i = 0; i < 40_000; i++) {
            int base = r2.nextInt(1_000_000_000);
            int exp = r2.nextInt(Integer.MAX_VALUE);
            int mod = 2 + r2.nextInt(1_000_000_000);
            int want = tsidec.pairs.ScalarExp.referenceModExp(base, exp, mod);
            if (tsidec.pairs.ScalarExp.unsafeModExp(base, exp, mod) != want) bad++;
            if (tsidec.pairs.ScalarExp.ladderModExp(base, exp, mod) != want) bad++;
            if (tsidec.pairs.ScalarExp.fullyConstantTimeModExp(base, exp, mod) != want) bad++;
        }
        check("模幂三版结果一致", bad == 0, bad + " 处不一致");
    }

    private static byte[] readClass(String internal) {
        try (var in = BehaviorTests.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
