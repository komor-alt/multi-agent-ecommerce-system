package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 决策路线解析器：Intake 分类完成后、取证循环之前，把白名单意图确定性映射为决策路线，
 * 并由服务端重建该路线所需的证据清单。LLM 永远不能输出路线，也永远不能修改必需证据。
 *
 * 安全边界：
 * - 输入只有结构化 IntakeResult 的 whitelisted intents（REQUEST_REFUND / TRACK_SHIPMENT）；
 * - 映射完全确定：存在 REQUEST_REFUND → COMPENSATION_EVALUATION，否则 → ANSWER_ONLY；
 *   预留路线 REQUEST_MORE_INFO / HUMAN_ESCALATION 在 MVP 中不被解析器产生；
 * - requiredEvidence 由本解析器按路线重建：模型在 Intake 里给出的 requiredEvidence 只是不可信
 *   建议，既不能降低（漏掉 POLICY 也不影响补偿评估），也不能抬高（补上 POLICY 也不会让
 *   ANSWER_ONLY 去取证）——路线的证据要求以本解析器为准；
 * - 未知意图名同样走 ANSWER_ONLY 默认分支，绝不由模型指定路线或证据。
 */
@Service
public class DecisionRouteResolver {

    public static final List<String> ANSWER_ONLY_EVIDENCE = List.of("ORDER", "SHIPMENT");
    public static final List<String> COMPENSATION_EVALUATION_EVIDENCE = List.of("ORDER", "SHIPMENT", "POLICY");

    /** 不可变解析结果：路线 + 服务端重建的必需证据清单。 */
    public record RouteDecision(DecisionRoute route, List<String> requiredEvidence) {
    }

    public RouteDecision resolve(AfterSalesTypes.IntakeResult intake) {
        if (intake != null && intake.intents() != null && intake.intents().contains("REQUEST_REFUND")) {
            return new RouteDecision(DecisionRoute.COMPENSATION_EVALUATION, COMPENSATION_EVALUATION_EVIDENCE);
        }
        return new RouteDecision(DecisionRoute.ANSWER_ONLY, ANSWER_ONLY_EVIDENCE);
    }
}
