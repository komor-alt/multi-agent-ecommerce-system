package com.ecommerce.aftersales.model;

/**
 * 决策路线：Intake 分类之后、取证循环之前由服务端确定性解析（见 DecisionRouteResolver）。
 *
 * - ANSWER_ONLY：仅查询物流，直接以可信物流状态答复，不评估补偿；
 * - COMPENSATION_EVALUATION：客户表达了退款/补偿诉求，进入补偿评估管线
 *   （calculate_compensation → 视资格 create_action_proposal）；
 * - REQUEST_MORE_INFO / HUMAN_ESCALATION：预留路线，MVP 解析器不产生（模型永远不能输出路线）。
 */
public enum DecisionRoute {
    ANSWER_ONLY,
    COMPENSATION_EVALUATION,
    REQUEST_MORE_INFO,
    HUMAN_ESCALATION
}
