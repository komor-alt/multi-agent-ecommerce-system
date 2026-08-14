package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.ApprovalPolicyViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 局部审批策略违例处理：只对 AfterSalesController 生效，把 ApprovalPolicyViolationException
 * 映射为 HTTP 409（Conflict）+ 结构化安全字段（code + 中英文摘要），
 * 绝不输出堆栈、内部状态或数据库细节。PROPOSAL_NOT_FOUND 等既有错误保持原行为。
 */
@RestControllerAdvice(assignableTypes = AfterSalesController.class)
public class AfterSalesApprovalViolationAdvice {

    @ExceptionHandler(ApprovalPolicyViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handle(ApprovalPolicyViolationException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode());
        body.put("message", error.getMessage());
        body.put("messageZh", error.getMessageZh());
        return body;
    }
}
