package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.AfterSalesRunEventService;
import com.ecommerce.aftersales.service.AfterSalesService;
import com.ecommerce.aftersales.service.OperatorContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AfterSalesController 审批身份链路测试（standalone MockMvc + 两个局部 Advice）：
 * 缺 Header → 401 OPERATOR_IDENTITY_REQUIRED；非法 Header → 400 OPERATOR_IDENTITY_INVALID；
 * 合法 Header → 服务收到 OperatorContext(id) 且 body 只绑定 comment（body 里的 operatorId 被忽略，
 * 绝不透传为审批人）。ObjectMapper 采用 Jackson2ObjectMapperBuilder 默认配置
 * （与 Spring Boot 生产一致：未知 body 字段忽略，不拒绝请求）。
 */
class AfterSalesControllerTest {

    private final AfterSalesService afterSalesService = mock(AfterSalesService.class);
    private final AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AfterSalesController(afterSalesService, eventService))
                .setControllerAdvice(new AfterSalesIdentityAdvice(), new AfterSalesApprovalViolationAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        when(afterSalesService.approve(eq("proposal-1"), org.mockito.ArgumentMatchers.any(), eq("approved")))
                .thenReturn(Map.of("status", "PENDING"));
    }

    @Test
    void approveWithoutHeaderRejectsWith401AndNoStack() throws Exception {
        mockMvc.perform(post("/api/v1/after-sales/proposals/proposal-1/approve")
                        .contentType("application/json")
                        .content("{\"comment\":\"approved\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OPERATOR_IDENTITY_REQUIRED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.messageZh").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());

        verify(afterSalesService, never()).approve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void approveWithInvalidHeaderRejectsWith400() throws Exception {
        mockMvc.perform(post("/api/v1/after-sales/proposals/proposal-1/approve")
                        .header("X-Authenticated-Operator", "bad id!")
                        .contentType("application/json")
                        .content("{\"comment\":\"approved\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_IDENTITY_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.messageZh").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());

        verify(afterSalesService, never()).approve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void approveWithValidHeaderBuildsContextAndPassesCommentOnly() throws Exception {
        mockMvc.perform(post("/api/v1/after-sales/proposals/proposal-1/approve")
                        .header("X-Authenticated-Operator", "operator-vn-01")
                        .contentType("application/json")
                        .content("{\"comment\":\"approved\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<OperatorContext> contextCaptor = ArgumentCaptor.forClass(OperatorContext.class);
        verify(afterSalesService).approve(eq("proposal-1"), contextCaptor.capture(), eq("approved"));
        assertThat(contextCaptor.getValue().id()).isEqualTo("operator-vn-01");
    }

    @Test
    void bodyOperatorIdIsNeverAdoptedAsIdentity() throws Exception {
        // 客户端即使伪造 body.operatorId，也只会被忽略（未知字段不绑定），身份永远来自可信 Header。
        mockMvc.perform(post("/api/v1/after-sales/proposals/proposal-1/approve")
                        .header("X-Authenticated-Operator", "operator-vn-01")
                        .contentType("application/json")
                        .content("{\"comment\":\"approved\",\"operatorId\":\"evil-operator\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<OperatorContext> contextCaptor = ArgumentCaptor.forClass(OperatorContext.class);
        verify(afterSalesService).approve(eq("proposal-1"), contextCaptor.capture(), eq("approved"));
        assertThat(contextCaptor.getValue().id()).isEqualTo("operator-vn-01");
    }

    @Test
    void rejectWithoutHeaderRejectsWith401() throws Exception {
        mockMvc.perform(post("/api/v1/after-sales/proposals/proposal-1/reject")
                        .contentType("application/json")
                        .content("{\"comment\":\"rejected\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OPERATOR_IDENTITY_REQUIRED"));

        verify(afterSalesService, never()).reject(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectWithValidHeaderBuildsContext() throws Exception {
        when(afterSalesService.reject(eq("proposal-1"), org.mockito.ArgumentMatchers.any(), eq("rejected")))
                .thenReturn(Map.of("status", "REJECTED"));

        mockMvc.perform(post("/api/v1/after-sales/proposals/proposal-1/reject")
                        .header("X-Authenticated-Operator", "operator-vn-01")
                        .contentType("application/json")
                        .content("{\"comment\":\"rejected\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<OperatorContext> contextCaptor = ArgumentCaptor.forClass(OperatorContext.class);
        verify(afterSalesService).reject(eq("proposal-1"), contextCaptor.capture(), eq("rejected"));
        assertThat(contextCaptor.getValue().id()).isEqualTo("operator-vn-01");
    }
}
