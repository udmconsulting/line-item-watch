package com.udmconsulting.platform.supportability;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestSupportability {

    private static final String OPERATION = RequestSupportability.class.getName() + ".operation";
    private static final String ERROR_CODE = RequestSupportability.class.getName() + ".errorCode";

    private RequestSupportability() {
    }

    public static void operation(HttpServletRequest request, ApplicationOperation operation) {
        request.setAttribute(OPERATION, operation);
    }

    public static void error(HttpServletRequest request, OperationalErrorCode errorCode) {
        request.setAttribute(ERROR_CODE, errorCode);
    }

    static ApplicationOperation operation(HttpServletRequest request) {
        Object value = request.getAttribute(OPERATION);
        return value instanceof ApplicationOperation operation
                ? operation : ApplicationOperation.HTTP_REQUEST;
    }

    static OperationalErrorCode error(HttpServletRequest request) {
        Object value = request.getAttribute(ERROR_CODE);
        return value instanceof OperationalErrorCode errorCode
                ? errorCode : OperationalErrorCode.NONE;
    }
}
