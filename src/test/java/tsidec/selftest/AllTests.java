package tsidec.selftest;

/**
 * 测试入口。刻意不依赖 JUnit：一条命令即可在任意 JDK 17+ 上验证，
 * 不需要网络、不需要包管理器。返回非零退出码表示有失败项。
 */
public final class AllTests {
    public static void main(String[] args) {
        System.out.println("tsidec 自测\n");
        StatsTests.run();
        BehaviorTests.run();

        int fails = StatsTests.failures + BehaviorTests.failures;
        System.out.println();
        if (fails == 0) {
            System.out.println("全部通过。");
        } else {
            System.out.println("失败 " + fails + " 项。");
            System.exit(1);
        }
    }
}
