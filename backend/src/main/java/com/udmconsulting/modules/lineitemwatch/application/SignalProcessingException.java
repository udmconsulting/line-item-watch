package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.supportability.OperationalErrorCode;
import java.util.Objects;

public final class SignalProcessingException extends RuntimeException {

    private final OperationalErrorCode errorCode;

    public SignalProcessingException(OperationalErrorCode errorCode, Throwable cause) {
        super("Line Item signal processing failed", cause);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public OperationalErrorCode errorCode() {
        return errorCode;
    }
}
