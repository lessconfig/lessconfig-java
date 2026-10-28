package com.lessconfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures {@code slf4j-simple} output for deliberately thrown listener exceptions.
 * Verified warnings are consumed; other output is restored to the console when the scope closes.
 * Tests using this helper must hold JUnit's {@code Resources.SYSTEM_ERR} resource lock.
 */
final class ExpectedListenerLog implements AutoCloseable {
    /**
     * Original console stream to restore when capture ends.
     */
    private final PrintStream previousError = System.err;
    /**
     * Thread-safe buffer shared by the test thread and watcher logging.
     */
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    /**
     * Temporary UTF-8 stream used by the test-scoped SLF4J provider.
     */
    private final PrintStream capturedError;
    /**
     * Complete stack trace expected after each warning message.
     */
    private final String expectedStackTrace;
    /**
     * Records already checked, so repeated assertions require separate log entries.
     */
    private final List<String> verifiedLogs = new ArrayList<>();

    /**
     * Redirects standard error while preserving the expected exception's complete stack trace.
     * The Simple provider must use {@code System.err} without caching its output stream.
     *
     * @param expectedFailure exception deliberately thrown by this test's listener
     * @throws UnsupportedEncodingException if the runtime lacks the required UTF-8 charset
     */
    ExpectedListenerLog(RuntimeException expectedFailure) throws UnsupportedEncodingException {
        StringWriter stackTrace = new StringWriter();
        expectedFailure.printStackTrace(new PrintWriter(stackTrace));
        expectedStackTrace = stackTrace.toString();
        capturedError = new PrintStream(output, true, StandardCharsets.UTF_8.name());
        System.setErr(capturedError);
    }

    /**
     * Consumes one warning with the SDK logger name, message and complete expected stack trace.
     * Callers must first wait for listener delivery to finish before inspecting asynchronous logs.
     *
     * @param message expected SDK log message
     */
    void assertLogged(String message) {
        String record = "WARN " + DefaultLessConfigClient.class.getName() + " - " + message
                + System.lineSeparator() + expectedStackTrace;
        Matcher matcher = Pattern.compile("(?m)^[^\\r\\n]*\\b" + Pattern.quote(record))
                .matcher(unverifiedOutput());
        assertTrue(matcher.find(), "The expected listener warning and stack trace were not logged: " + message);
        verifiedLogs.add(matcher.group());
    }

    /**
     * Returns captured output with each successfully verified occurrence removed once.
     *
     * @return remaining diagnostics, including any warning whose assertion failed
     */
    private String unverifiedOutput() {
        StringBuilder remaining = new StringBuilder(new String(output.toByteArray(), StandardCharsets.UTF_8));
        for (String verified : verifiedLogs) {
            int start = remaining.indexOf(verified);
            remaining.delete(start, start + verified.length());
        }
        return remaining.toString();
    }

    /**
     * Restores standard error and prints unverified diagnostics, including after assertion failures.
     */
    @Override
    public void close() {
        System.setErr(previousError);
        capturedError.close();
        previousError.print(unverifiedOutput());
    }
}
