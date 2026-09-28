package com.udmconsulting.platform.supportability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SafeDiagnosticExceptionTest {

    @Test
    void retainsTypesAndFramesWithoutMessagesOrSuppressedPayloads() {
        IllegalArgumentException cause = new IllegalArgumentException("provider payload portal=123");
        IllegalStateException unsafe = new IllegalStateException(
                "jdbc:postgresql://secret-host customer value", cause);
        unsafe.addSuppressed(new RuntimeException("refresh-token-secret"));

        Throwable safe = SafeDiagnosticException.from(unsafe);

        assertThat(safe.getMessage()).isEqualTo(IllegalStateException.class.getName());
        assertThat(safe.getStackTrace()).containsExactly(Arrays.copyOf(
                unsafe.getStackTrace(),
                Math.min(unsafe.getStackTrace().length, SafeDiagnosticException.MAX_STACK_FRAMES)));
        assertThat(safe.getCause().getMessage()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(safe.getSuppressed()).isEmpty();
        assertThat(render(safe)).doesNotContain(
                "secret-host", "customer value", "portal=123", "refresh-token-secret");
    }

    @Test
    void boundsCopiedFramesForEverySanitizedThrowable() {
        IllegalArgumentException cause = new IllegalArgumentException("unsafe cause message");
        IllegalStateException unsafe = new IllegalStateException("unsafe root message", cause);
        unsafe.setStackTrace(frames(SafeDiagnosticException.MAX_STACK_FRAMES + 10, "Root"));
        cause.setStackTrace(frames(SafeDiagnosticException.MAX_STACK_FRAMES + 20, "Cause"));

        Throwable safe = SafeDiagnosticException.from(unsafe);

        assertThat(safe.getStackTrace()).hasSize(SafeDiagnosticException.MAX_STACK_FRAMES);
        assertThat(safe.getCause().getStackTrace())
                .hasSize(SafeDiagnosticException.MAX_STACK_FRAMES);
        assertThat(render(safe)).doesNotContain("unsafe root message", "unsafe cause message");
    }

    private static StackTraceElement[] frames(int count, String owner) {
        StackTraceElement[] frames = new StackTraceElement[count];
        for (int index = 0; index < count; index++) {
            frames[index] = new StackTraceElement(owner, "method" + index, owner + ".java", index + 1);
        }
        return frames;
    }

    private static String render(Throwable throwable) {
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
