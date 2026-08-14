package com.ecommerce.aftersales.service;

import java.util.regex.Pattern;

/**
 * 可信审批人身份上下文：只接受来自可信 Gateway 的 X-Authenticated-Operator 值
 * （Demo 链路由 NestJS Gateway 按配置强制注入；生产环境必须由认证中间件生成并清洗后覆盖）。
 * 普通请求 body 永远无法携带或影响该身份——控制器只从 @RequestHeader 读取。
 *
 * 校验：非空 + 格式/长度（^[A-Za-z0-9._-]{2,64}$）。缺失抛 OPERATOR_IDENTITY_REQUIRED，
 * 非法抛 OPERATOR_IDENTITY_INVALID（结构化安全异常，由局部 Advice 映射 401/400，绝不输出堆栈）。
 */
public record OperatorContext(String id) {

    public static final String HEADER_NAME = "X-Authenticated-Operator";

    private static final Pattern OPERATOR_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{2,64}$");

    public OperatorContext {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) {
            throw new OperatorIdentityViolationException(
                    OperatorIdentityViolationException.OPERATOR_IDENTITY_REQUIRED,
                    "Trusted operator identity is missing.",
                    "缺少可信审批人身份。");
        }
        if (!OPERATOR_ID_PATTERN.matcher(trimmed).matches()) {
            throw new OperatorIdentityViolationException(
                    OperatorIdentityViolationException.OPERATOR_IDENTITY_INVALID,
                    "Trusted operator identity has invalid format or length.",
                    "审批人身份格式非法。");
        }
        id = trimmed;
    }

    /** 从可信 Gateway Header 构建；Header 缺失/空白即按「身份缺失」拒绝。 */
    public static OperatorContext fromTrustedGatewayHeader(String headerValue) {
        return new OperatorContext(headerValue);
    }
}
