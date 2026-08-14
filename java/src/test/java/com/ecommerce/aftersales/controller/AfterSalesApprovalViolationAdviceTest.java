package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.ApprovalPolicyViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 局部 @RestControllerAdvice 测试：只输出 code + 中英文安全摘要（无堆栈/内部状态），
 * 映射 HTTP 409（CONFLICT），且只作用于 AfterSalesController。
 */
class AfterSalesApprovalViolationAdviceTest {

    @Test
    void violationMapsToConflictWithCodeAndBilingualSafeSummary() throws Exception {
        AfterSalesApprovalViolationAdvice advice = new AfterSalesApprovalViolationAdvice();
        ApprovalPolicyViolationException error = new ApprovalPolicyViolationException(
                "APPROVAL_AMOUNT_MISMATCH",
                "Proposal amount does not match the recomputed compensation amount.",
                "方案金额与规则重算金额不一致。");

        Map<String, Object> body = advice.handle(error);

        assertThat(body).containsEntry("code", "APPROVAL_AMOUNT_MISMATCH");
        assertThat(body).containsEntry("message", "Proposal amount does not match the recomputed compensation amount.");
        assertThat(body).containsEntry("messageZh", "方案金额与规则重算金额不一致。");
        assertThat(body).hasSize(3); // 绝不携带堆栈或其他内部字段。

        // HTTP 409：注解层面断言；控制器局部性：只处理 AfterSalesController。
        ResponseStatus status = AfterSalesApprovalViolationAdvice.class
                .getMethod("handle", ApprovalPolicyViolationException.class)
                .getAnnotation(ResponseStatus.class);
        assertThat(status.value()).isEqualTo(HttpStatus.CONFLICT);
        RestControllerAdvice adviceAnnotation = AfterSalesApprovalViolationAdvice.class
                .getAnnotation(RestControllerAdvice.class);
        assertThat(adviceAnnotation.assignableTypes()).contains(AfterSalesController.class);
    }
}
