package com.udmconsulting.modules.lineitemwatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BaselineSyncExceptionTest {

    @Test
    void retryabilityIsOwnedExhaustivelyByTheFailureType() {
        assertThat(Arrays.stream(BaselineSyncFailure.values())
                        .filter(BaselineSyncFailure::retryable))
                .containsExactlyInAnyOrder(
                        BaselineSyncFailure.PROVIDER_STATE_CHANGED,
                        BaselineSyncFailure.PROVIDER_UNAVAILABLE,
                        BaselineSyncFailure.PROVIDER_ASSOCIATION_READ_INCOMPLETE);
        assertThat(Arrays.stream(BaselineSyncFailure.values())
                        .filter(failure -> !failure.retryable()))
                .containsExactlyInAnyOrder(
                        BaselineSyncFailure.CONNECTION_NOT_FOUND,
                        BaselineSyncFailure.CONNECTION_PROVIDER_UNSUPPORTED,
                        BaselineSyncFailure.CONNECTION_NOT_ACTIVE,
                        BaselineSyncFailure.MODULE_NOT_ENTITLED,
                        BaselineSyncFailure.PROVIDER_DEAL_NOT_FOUND,
                        BaselineSyncFailure.PROVIDER_DEAL_IDENTITY_MISMATCH,
                        BaselineSyncFailure.PROVIDER_LINE_ITEM_IDENTITY_MISMATCH,
                        BaselineSyncFailure.PROVIDER_LINE_ITEM_IDENTITY_DUPLICATE,
                        BaselineSyncFailure.PROVIDER_AUTHORIZATION_REJECTED,
                        BaselineSyncFailure.PROVIDER_RESPONSE_INVALID,
                        BaselineSyncFailure.PROVIDER_OBJECT_ID_INVALID);
    }

    @Test
    void exceptionExposesFailureAndDelegatesRetryability() {
        for (BaselineSyncFailure failure : BaselineSyncFailure.values()) {
            BaselineSyncException exception = new BaselineSyncException(failure);

            assertThat(exception.failure()).isSameAs(failure);
            assertThat(exception.retryable()).isEqualTo(failure.retryable());
            assertThat(exception.getMessage()).isNotBlank();
        }
    }

    @Test
    void rejectsMissingFailure() {
        assertThatNullPointerException()
                .isThrownBy(() -> new BaselineSyncException(null))
                .withMessage("failure must not be null");
    }
}
