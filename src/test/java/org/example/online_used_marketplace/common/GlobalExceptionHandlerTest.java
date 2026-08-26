package org.example.online_used_marketplace.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void forbiddenBusinessExceptionUsesHttp403() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(ResultCode.FORBIDDEN, "internal only")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(403);
    }

    @Test
    void ordinaryBusinessExceptionKeepsCompatibilityHttp200() {
        ResponseEntity<Result<Void>> response = handler.handleBusinessException(
                new BusinessException(ResultCode.CONFLICT, "conflict")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(409);
    }
}
