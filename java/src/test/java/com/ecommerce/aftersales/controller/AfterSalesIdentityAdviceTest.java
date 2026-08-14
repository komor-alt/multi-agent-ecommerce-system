package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.OperatorIdentityViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 局部身份异常 Advice 测试：缺失 → 401（UNAUTHORIZED）、非法 → 400（BAD_REQUEST），
 * 只输出 code + 中英文安全摘要（无堆栈/内部状态），且只作用于 AfterSalesController。
 */
class AfterSalesIdentityAdviceTest {

    private final AfterSalesIdentityAdvice advice = new AfterSalesIdentityAdvice();

    @Test
    void missingIdentityMapsToUnauthorizedWithBilingualSafeSummary() {
        OperatorIdentityViolationException error = new OperatorIdentityViolationException(
                "OPERATOR_IDENTITY_REQUIRED",
                "Trusted operator identity is missing.",
                "缺少可信审批人身份。");

        ResponseEntity<Map<String, Object>> response = advice.handle(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("code", "OPERATOR_IDENTITY_REQUIRED");
        assertThat(body).containsEntry("message", "Trusted operator identity is missing.");
        assertThat(body).containsEntry("messageZh", "缺少可信审批人身份。");
        assertThat(body).hasSize(3); // 绝不携带堆栈或其他内部字段。
    }

    @Test
    void invalidIdentityMapsToBadRequestWithBilingualSafeSummary() {
        OperatorIdentityViolationException error = new OperatorIdentityViolationException(
                "OPERATOR_IDENTITY_INVALID",
                "Trusted operator identity has invalid format or length.",
                "审批人身份格式非法。");

        ResponseEntity<Map<String, Object>> response = advice.handle(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("code", "OPERATOR_IDENTITY_INVALID");
        assertThat(body).containsEntry("message", "Trusted operator identity has invalid format or length.");
        assertThat(body).containsEntry("messageZh", "审批人身份格式非法。");
        assertThat(body).hasSize(3);
    }

    @Test
    void adviceIsScopedToAfterSalesControllerOnly() {
        RestControllerAdvice adviceAnnotation = AfterSalesIdentityAdvice.class
                .getAnnotation(RestControllerAdvice.class);
        assertThat(adviceAnnotation.assignableTypes()).contains(AfterSalesController.class);
    }
}
