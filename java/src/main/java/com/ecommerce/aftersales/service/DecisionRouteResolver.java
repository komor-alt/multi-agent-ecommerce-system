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
 * - 输入只有结构化 IntakeResult 的 whitelisted intents（REQUEST_REFUND / TRACK_SHIPMENT /
 *   CANCEL_ORDER / EXCHANGE_RETURN / ACCOUNT_PAYMENT_ABUSE）与分类 issueType；映射完全确定；
 * - 存在 REQUEST_REFUND → COMPENSATION_EVALUATION，否则 → ANSWER_ONLY；
 *   UNSUPPORTED（取消订单/退换货/账号支付滥用等超范围问题类型）→ HUMAN_ESCALATION，
 *   必需证据重建为空清单（循环立即转人工，绝不取证）；
 *   预留路线 REQUEST_MORE_INFO 在 MVP 中不被解析器产生；
 * - requiredEvidence 由本解析器按「问题类型证据图」重建（模型在 Intake 里给出的
 *   requiredEvidence 只是不可信建议，既不能降低也不能抬高服务端要求）：
 *   - SHIPMENT_DELAY：ORDER → SHIPMENT → POLICY；
 *   - LOST_IN_TRANSIT：ORDER → SHIPMENT → CARRIER_CASE → POLICY；
 *   - DAMAGED_ITEM：ORDER → DELIVERY → DAMAGE_PHOTO → PRODUCT → POLICY；
 *   三条路径的证据清单与顺序互不相同，绝不存在一个全局固定的 ORDER/SHIPMENT/POLICY 顺序；
 * - ANSWER_ONLY（纯查询）也按问题类型重建：SHIPMENT_DELAY / LOST_IN_TRANSIT 只查
 *   ORDER+SHIPMENT（物流答复），DAMAGED_ITEM 只查 ORDER+DELIVERY（交付证明答复），
 *   绝不顺带取证与问题无关的证据；
 * - 未知问题类型走 SHIPMENT_DELAY 证据图默认分支，未知意图名同样走 ANSWER_ONLY 默认分支，
 *   绝不由模型指定路线或证据。
 */
@Service
public class DecisionRouteResolver {

    /** ANSWER_ONLY（SHIPMENT_DELAY / LOST_IN_TRANSIT）证据图：纯物流答复不需要政策/案件。 */
    public static final List<String> ANSWER_ONLY_EVIDENCE = List.of("ORDER", "SHIPMENT");
    /** ANSWER_ONLY（DAMAGED_ITEM）证据图：破损答复基于订单 + 交付证明，没有 SHIPMENT 节点。 */
    public static final List<String> DAMAGED_ANSWER_ONLY_EVIDENCE = List.of("ORDER", "DELIVERY");
    /** SHIPMENT_DELAY 补偿评估证据图。 */
    public static final List<String> SHIPMENT_DELAY_EVIDENCE = List.of("ORDER", "SHIPMENT", "POLICY");
    /** LOST_IN_TRANSIT 补偿评估证据图。 */
    public static final List<String> LOST_IN_TRANSIT_EVIDENCE =
            List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");
    /** DAMAGED_ITEM 补偿评估证据图。 */
    public static final List<String> DAMAGED_ITEM_EVIDENCE =
            List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
    /** 兼容旧名：SHIPMENT_DELAY 补偿评估全量。 */
    public static final List<String> COMPENSATION_EVALUATION_EVIDENCE = SHIPMENT_DELAY_EVIDENCE;

    /** 不可变解析结果：路线 + 服务端重建的必需证据清单。 */
    public record RouteDecision(DecisionRoute route, List<String> requiredEvidence) {
    }

    public RouteDecision resolve(AfterSalesTypes.IntakeResult intake) {
        String issueType = intake == null ? null : intake.issueType();
        // UNSUPPORTED 先于意图检查：超范围诉求（含同时表达退款/查物流的）一律人工升级，
        // 必需证据重建为空清单，Agent 循环立即完成转人工，绝不取证、绝不建方案。
        if (AfterSalesTypes.IntakeResult.UNSUPPORTED.equals(issueType)) {
            return new RouteDecision(DecisionRoute.HUMAN_ESCALATION, List.of());
        }
        boolean refund = intake != null && intake.intents() != null
                && intake.intents().contains("REQUEST_REFUND");
        if (refund) {
            // 补偿评估：证据图按问题类型区分，三条路径互不相同。
            return switch (issueType) {
                case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT ->
                        new RouteDecision(DecisionRoute.COMPENSATION_EVALUATION, LOST_IN_TRANSIT_EVIDENCE);
                case AfterSalesTypes.IntakeResult.DAMAGED_ITEM ->
                        new RouteDecision(DecisionRoute.COMPENSATION_EVALUATION, DAMAGED_ITEM_EVIDENCE);
                default ->
                        new RouteDecision(DecisionRoute.COMPENSATION_EVALUATION, SHIPMENT_DELAY_EVIDENCE);
            };
        }
        // 纯查询：DAMAGED_ITEM 的证据图没有 SHIPMENT 节点，答复基于交付证明。
        if (AfterSalesTypes.IntakeResult.DAMAGED_ITEM.equals(issueType)) {
            return new RouteDecision(DecisionRoute.ANSWER_ONLY, DAMAGED_ANSWER_ONLY_EVIDENCE);
        }
        return new RouteDecision(DecisionRoute.ANSWER_ONLY, ANSWER_ONLY_EVIDENCE);
    }
}
