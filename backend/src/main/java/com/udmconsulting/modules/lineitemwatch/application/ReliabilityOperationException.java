package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.supportability.OperationalErrorCode;
import java.util.Objects;

public final class ReliabilityOperationException extends RuntimeException {

    private final OperationalErrorCode errorCode;

    public ReliabilityOperationException(OperationalErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ReliabilityOperationException(OperationalErrorCode errorCode, Throwable cause) {
        super(errorCode.name(), cause);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public OperationalErrorCode errorCode() {
        return errorCode;
    }
}
