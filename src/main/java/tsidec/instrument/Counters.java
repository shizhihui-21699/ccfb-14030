package tsidec.instrument;

/**
 * 插桩后的计数后端。
 *
 * 单位是**基本块的执行次数**，而不是纳秒。选它的理由：
 *   同一条输入路径的"执行了多少条字节码"是完全确定的，不含任何调度/GC/频率噪声。
 *   若两类输入（只差秘密）导致执行指令数不同，则控制流乃至工作量必然随秘密变化，
 *   这是比"总耗时"更强、且可精确复现的泄漏证据。
 *
 * 同时保留"每块执行次数"，用于把泄漏定位到具体基本块（进而映射到源码行）。
 */
public final class Counters {
    private static int[] blockSize = new int[0];   // 每块的指令条数
    private static long[] blockHits = new long[0]; // 每块被执行次数
    private static volatile long total = 0;

    private Counters() {}

    /** 由插桩器在加载改写后的类之前调用。 */
    public static synchronized void configure(int[] sizes) {
        blockSize = sizes.clone();
        blockHits = new long[sizes.length];
        total = 0;
    }

    /** 插桩代码在每个基本块入口调用。 */
    public static void block(int id) {
        if (id < 0 || id >= blockSize.length) return;
        blockHits[id]++;
        total += blockSize[id];
    }

    public static synchronized void resetCounts() {
        java.util.Arrays.fill(blockHits, 0);
        total = 0;
    }

    public static long total() { return total; }

    public static synchronized long[] hitsSnapshot() { return blockHits.clone(); }

    public static synchronized int[] sizesSnapshot() { return blockSize.clone(); }
}
