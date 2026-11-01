package tech.kayys.wayang.eip;

/** Pauses the current thread; a seam so tests (and a future durable runtime) control waiting. */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;

    Sleeper SYSTEM = Thread::sleep;
}
