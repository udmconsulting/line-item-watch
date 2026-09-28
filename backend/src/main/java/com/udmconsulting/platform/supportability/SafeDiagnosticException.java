package com.udmconsulting.platform.supportability;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class SafeDiagnosticException extends RuntimeException {

    private static final int MAX_CAUSE_DEPTH = 12;
    static final int MAX_STACK_FRAMES = 64;

    private SafeDiagnosticException(String exceptionType, Throwable cause) {
        super(exceptionType, cause, false, true);
    }

    public static Throwable from(Throwable unsafe) {
        if (unsafe == null) {
            return null;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return sanitize(unsafe, visited, 0);
    }

    private static SafeDiagnosticException sanitize(
            Throwable unsafe, Set<Throwable> visited, int depth) {
        String type = unsafe.getClass().getName();
        Throwable safeCause = null;
        if (depth < MAX_CAUSE_DEPTH && visited.add(unsafe) && unsafe.getCause() != null
                && !visited.contains(unsafe.getCause())) {
            safeCause = sanitize(unsafe.getCause(), visited, depth + 1);
        }
        SafeDiagnosticException safe = new SafeDiagnosticException(type, safeCause);
        StackTraceElement[] unsafeFrames = unsafe.getStackTrace();
        safe.setStackTrace(Arrays.copyOf(
                unsafeFrames, Math.min(unsafeFrames.length, MAX_STACK_FRAMES)));
        return safe;
    }
}
