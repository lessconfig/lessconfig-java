package com.lessconfig;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one daemon thread, wakeups, retry backoff and shutdown; HTTP/cache logic is supplied by the client.
 */
final class LongPollWatcher implements AutoCloseable {
    /**
     * One client-supplied watch cycle and its retry classification.
     */
    interface Task {
        /**
         * Performs one poll/application cycle or waits for subscriptions.
         */
        void run();

        /**
         * Classifies and reports a background failure.
         *
         * @param error failure from the previous cycle
         * @return true to retry with backoff, false to terminate
         */
        boolean onError(LessConfigException error);
    }

    private static final AtomicLong IDS = new AtomicLong();
    private final Task task;
    private final Duration initialDelay;
    private final Duration maximumDelay;
    private final Object signal = new Object();
    private Thread thread;
    private volatile boolean closed;
    private boolean signalled;

    /**
     * Creates an unstarted worker with bounded exponential retry delays.
     *
     * @param task         client-supplied polling/application cycle
     * @param initialDelay first retry delay
     * @param maximumDelay maximum retry delay
     */
    LongPollWatcher(Task task, Duration initialDelay, Duration maximumDelay) {
        this.task = task;
        this.initialDelay = initialDelay;
        this.maximumDelay = maximumDelay;
    }

    /**
     * Starts the daemon at most once. @throws IllegalStateException if already closed
     */
    synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Watcher is closed");
        }
        if (thread == null) {
            thread = new Thread(this::loop, "lessconfig-watch-" + IDS.incrementAndGet());
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Wakes a worker waiting for subscriptions or retry delay.
     */
    void wake() {
        synchronized (signal) {
            signalled = true;
            signal.notifyAll();
        }
    }

    /**
     * Waits for a scope change or shutdown without emitting HTTP requests.
     */
    void awaitWork() {
        pause(Duration.ZERO);
    }

    /**
     * Waits interruptibly until the delay, a wakeup or shutdown.
     *
     * @param delay wait duration; zero waits until a notification
     */
    private void pause(Duration delay) {
        synchronized (signal) {
            if (!closed && !signalled) {
                try {
                    signal.wait(delay.toMillis());
                } catch (InterruptedException ignored) {
                    /* Closing wakes the loop. */
                }
            }
            signalled = false;
        }
    }

    /**
     * Runs cycles until close or a terminal failure, resetting backoff after success.
     */
    private void loop() {
        Duration delay = initialDelay;
        while (!closed) {
            synchronized (signal) {
                signalled = false;
            }
            try {
                task.run();
                delay = initialDelay;
            } catch (LessConfigException e) {
                if (closed || !task.onError(e)) {
                    return;
                }
                pause(delay);
                delay = nextDelay(delay);
            } catch (RuntimeException e) {
                if (closed) {
                    return;
                }
                LessConfigException error = new LessConfigException("Watcher failed", e);
                if (!task.onError(error)) {
                    return;
                }
                pause(delay);
                delay = nextDelay(delay);
            }
        }
    }

    /**
     * Doubles the retry delay without exceeding the configured bound.
     *
     * @param delay previous retry delay
     * @return next bounded retry delay
     */
    private Duration nextDelay(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(maximumDelay) > 0 ? maximumDelay : doubled;
    }

    /**
     * Signals shutdown without waiting for user callbacks to complete.
     */
    @Override
    public void close() {
        closed = true;
        wake();
        synchronized (this) {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
