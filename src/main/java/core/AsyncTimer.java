package core;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncTimer implements AutoCloseable {

    private final static Logger LOGGER = LoggerFactory.getLogger(AsyncTimer.class);
    private final static ScheduledExecutorService executorService =
            Executors.newScheduledThreadPool(1);

    private boolean pending = true;
    private final Thread thread;

    public AsyncTimer(Duration duration) {
        thread = Thread.currentThread();
        executorService.schedule(() -> {
            if (pending) {
                LOGGER.error("Async timer interrupted: {}", thread.getName());
                interrupt();
            }
        }, duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void interrupt() {
        AtomicReference<Runnable> atomicRunnable = new AtomicReference<>();
        atomicRunnable.set(() -> {
            if (pending) {
                thread.interrupt();
                executorService.schedule(atomicRunnable.get(), 100, TimeUnit.MILLISECONDS);
            }
        });
        atomicRunnable.get().run();
    }

    @Override
    public void close() {
        pending = false;
    }

}
