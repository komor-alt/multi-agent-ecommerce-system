package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.OperatorIdentityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 局部审批人身份异常处理：只对 AfterSalesController 生效，把 OperatorIdentityViolationException
 * 映射为 HTTP 401（身份缺失）/ 400（身份非法）+ 结构化安全字段（code + 中英文摘要），
 * 绝不输出堆栈、内部状态或数据库细节。PROPOSAL_NOT_FOUND 等既有错误保持原行为。
 */
@RestControllerAdvice(assignableTypes = AfterSalesController.class)
public class AfterSalesIdentityAdvice {

    @ExceptionHandler(OperatorIdentityViolationException.class)
    public ResponseEntity<Map<String, Object>> handle(OperatorIdentityViolationException error) {
        boolean missing = OperatorIdentityViolationException.OPERATOR_IDENTITY_REQUIRED.equals(error.getCode());
        HttpStatus status = missing ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode());
        body.put("message", error.getMessage());
        body.put("messageZh", error.getMessageZh());
        return ResponseEntity.status(status).body(body);
    }
}
