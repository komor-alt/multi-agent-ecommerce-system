package com.ecommerce.aftersales.service;

/**
 * 审批人身份异常的安全结构化异常：只携带安全错误码与中英文摘要，
 * 绝不携带堆栈、内部状态或数据库细节。由局部 @RestControllerAdvice 映射为
 * HTTP 401（OPERATOR_IDENTITY_REQUIRED 缺失）/ 400（OPERATOR_IDENTITY_INVALID 非法）。
 */
public class OperatorIdentityViolationException extends RuntimeException {

    public static final String OPERATOR_IDENTITY_REQUIRED = "OPERATOR_IDENTITY_REQUIRED";
    public static final String OPERATOR_IDENTITY_INVALID = "OPERATOR_IDENTITY_INVALID";

    private final String code;
    private final String messageZh;

    public OperatorIdentityViolationException(String code, String message, String messageZh) {
        super(message);
        this.code = code;
        this.messageZh = messageZh;
    }

    public String getCode() {
        return code;
    }

    public String getMessageZh() {
        return messageZh;
    }
}
