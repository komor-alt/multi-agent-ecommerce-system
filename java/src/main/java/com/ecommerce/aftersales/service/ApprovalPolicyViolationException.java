package com.ecommerce.aftersales.service;

/**
 * 审批策略违例的安全结构化异常：只携带安全错误码与中英文摘要，
 * 绝不携带堆栈、内部状态或数据库细节。由局部 @RestControllerAdvice 以 HTTP 409 输出，
 * 在 ApprovalService.approve 事务内抛出即整体回滚（绝不创建 ApprovalRecord / ExecutionJob）。
 */
public class ApprovalPolicyViolationException extends RuntimeException {

    public static final String APPROVAL_PROPOSAL_STATE_INVALID = "APPROVAL_PROPOSAL_STATE_INVALID";
    public static final String APPROVAL_TICKET_STATE_INVALID = "APPROVAL_TICKET_STATE_INVALID";
    public static final String APPROVAL_RUN_STATE_INVALID = "APPROVAL_RUN_STATE_INVALID";
    public static final String APPROVAL_EVIDENCE_INCOMPLETE = "APPROVAL_EVIDENCE_INCOMPLETE";
    public static final String FINAL_ANSWER_INVALID = "FINAL_ANSWER_INVALID";
    public static final String APPROVAL_POLICY_VERSION_MISMATCH = "APPROVAL_POLICY_VERSION_MISMATCH";
    public static final String POLICY_CONTEXT_MISMATCH = "POLICY_CONTEXT_MISMATCH";
    public static final String APPROVAL_COMPENSATION_NOT_ELIGIBLE = "APPROVAL_COMPENSATION_NOT_ELIGIBLE";
    public static final String APPROVAL_AMOUNT_MISMATCH = "APPROVAL_AMOUNT_MISMATCH";
    public static final String APPROVAL_CURRENCY_MISMATCH = "APPROVAL_CURRENCY_MISMATCH";
    public static final String APPROVAL_ACTION_MISMATCH = "APPROVAL_ACTION_MISMATCH";

    private final String code;
    private final String messageZh;

    public ApprovalPolicyViolationException(String code, String message, String messageZh) {
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
