package dev.sebastiano.jewel.tooling.bridge;

/** Connects compiler trace markers to a sink without retaining application objects. */
public final class TraceBridge {
  public interface Sink {
    boolean enabled(int runtime);
    void start(int runtime, int key, int dirty1, int dirty2, String info);
    void end(int runtime);
    void failed();
  }

  private static volatile Sink sink;
  private static final ThreadLocal<int[]> forced = new ThreadLocal<int[]>() {
    @Override protected int[] initialValue() { return new int[1]; }
  };

  private TraceBridge() {}

  public static void install(Sink value) { sink = value; }

  public static boolean gate(boolean original, int runtime) {
    int[] pending = forced.get();
    pending[0] = 0;
    Sink current = sink;
    if (current == null) return original;
    try {
      boolean active = current.enabled(runtime);
      if (!original && active) pending[0] = runtime;
      return original || active;
    } catch (Throwable failure) {
      fail(current);
      return original;
    }
  }

  public static boolean start(int runtime, int key, int dirty1, int dirty2, String info) {
    boolean original = consume(runtime);
    Sink current = sink;
    if (current != null) {
      try { current.start(runtime, key, dirty1, dirty2, info); }
      catch (Throwable failure) { fail(current); }
    }
    return original;
  }

  public static boolean end(int runtime) {
    boolean original = consume(runtime);
    Sink current = sink;
    if (current != null) {
      try { current.end(runtime); }
      catch (Throwable failure) { fail(current); }
    }
    return original;
  }

  private static boolean consume(int runtime) {
    int[] pending = forced.get();
    boolean original = pending[0] != runtime;
    pending[0] = 0;
    return original;
  }

  private static void fail(Sink current) {
    sink = null;
    try { current.failed(); }
    catch (Throwable ignored) { }
  }
}
