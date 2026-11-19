package tsidec.core;

/**
 * 时钟与开销的抽象。探针单次执行会与测量本身的开销叠加，
 * 因此被测函数应当：null 输入时什么也不做，非 null 时执行真实逻辑。
 */
public final class Threads {
    private Threads() {}

    /** 短暂让出，给后台线程（GC 辅助线程、Spotlight 等）喘息，降低长尾概率。 */
    public static void settle(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
