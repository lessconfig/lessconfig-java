package com.lessconfig;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Captures only a test's deliberately thrown listener exception while leaving other logs visible.
 * Tests using this helper must share the listener-logger resource lock.
 */
final class ExpectedListenerLog implements AutoCloseable {
    private final Logger logger = Logger.getLogger(DefaultLessConfigClient.class.getName());
    private final Filter previousFilter;
    private final BlockingQueue<LogRecord> records = new LinkedBlockingQueue<>();

    /**
     * Installs a filter that captures records for the exact expected exception instance.
     *
     * @param expectedFailure exception deliberately thrown by this test's listener
     */
    ExpectedListenerLog(RuntimeException expectedFailure) {
        previousFilter = logger.getFilter();
        logger.setFilter(record -> {
            if (record.getThrown() == expectedFailure) {
                records.add(record);
                return false;
            }
            return previousFilter == null || previousFilter.isLoggable(record);
        });
    }

    /**
     * Verifies that the SDK logged the expected listener failure as a warning.
     *
     * @param message expected SDK log message
     * @throws InterruptedException if waiting for the watcher log is interrupted
     */
    void assertLogged(String message) throws InterruptedException {
        LogRecord record = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(record, "The expected listener exception was not logged");
        assertEquals(Level.WARNING, record.getLevel());
        assertEquals(message, record.getMessage());
    }

    /** Restores the logger's original filter when the test finishes, including assertion failures. */
    @Override
    public void close() {
        logger.setFilter(previousFilter);
    }
}
