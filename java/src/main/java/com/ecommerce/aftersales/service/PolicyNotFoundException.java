package com.ecommerce.aftersales.service;

import java.time.Instant;

/**
 * 政策查找失败的结构化错误：country + issueType + occurredAt 没有在该时间点已生效的匹配政策。
 * message 固定为 POLICY_NOT_FOUND（Agent 事件与 run.stopReason 直接透传），
 * 结构化字段供调用方记录或展示详细原因。查找失败关闭：绝不回退到通用政策。
 */
public class PolicyNotFoundException extends IllegalStateException {

    private final String country;
    private final String issueType;
    private final Instant occurredAt;

    public PolicyNotFoundException(String country, String issueType, Instant occurredAt) {
        super("POLICY_NOT_FOUND");
        this.country = country;
        this.issueType = issueType;
        this.occurredAt = occurredAt;
    }

    public String getCountry() {
        return country;
    }

    public String getIssueType() {
        return issueType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
