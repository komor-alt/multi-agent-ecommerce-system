/**
 * 售后运营中心的派生规则集中层。
 *
 * 全部指标/风险/检查项都必须由真实 API 数据推导，本模块是唯一来源：
 * - 指标带：由 listAfterSalesTickets() 的 status / createdAt / updatedAt 推导；
 * - 风险等级：由 proposal 金额与 policy 上限、shipment 延迟天数推导，标注「规则评估」；
 * - 证据分类：由证据 ID 前缀（order:/shipment:/policy:/calculation:）推导；
 * - 国家：优先后端 ticket.country（来自已知订单数据），缺失回退到 finalAnswer，再缺失为「未知」。
 *
 * 本模块只依赖纯函数与类型，由 tsc typecheck 覆盖（当前前端无测试框架，不引入新依赖）。
 */

import type { AfterSalesEvent, AfterSalesTicket } from "../../types/afterSales";

export type TicketMetrics = {
  /** 待处理：已创建、尚未开始分析的工单 */
  pending: number;
  /** SLA 风险：非终态工单的最后活动时间超过阈值（见 SLA_RISK_THRESHOURS） */
  slaRisk: number;
  /** 待审批：工单状态为 PENDING_APPROVAL（后端在该状态下必然存在待审批方案） */
  pendingApproval: number;
  /** 执行异常：最新执行任务处于 RETRY_WAIT 或 DEAD_LETTER（Agent 分析 FAILED 不计入） */
  executionFailed: number;
};

/** SLA 风险阈值：最后活动时间（updatedAt）距今超过 24 小时且仍未终态。当前模型无 SLA 字段，这是明确的可解释近似。 */
export const SLA_RISK_THRESHOLD_HOURS = 24;

export function deriveTicketMetrics(tickets: AfterSalesTicket[], now = Date.now()): TicketMetrics {
  const metrics: TicketMetrics = { pending: 0, slaRisk: 0, pendingApproval: 0, executionFailed: 0 };
  for (const ticket of tickets) {
    if (ticket.status === "OPEN") metrics.pending += 1;
    if (isExecutionException(ticket.executionStatus)) metrics.executionFailed += 1;
    if (ticket.status === "PENDING_APPROVAL") metrics.pendingApproval += 1;
    const lastActivity = new Date(ticket.updatedAt).getTime();
    if (
      isOpenStatus(ticket.status) &&
      Number.isFinite(lastActivity) &&
      (now - lastActivity) / 3_600_000 > SLA_RISK_THRESHOLD_HOURS
    ) {
      metrics.slaRisk += 1;
    }
  }
  return metrics;
}

export type TicketFilters = {
  search: string;
  country?: string;
  issueType?: string;
  status?: string;
  slaRisk?: boolean;
};

export function filterTickets(tickets: AfterSalesTicket[], filters: TicketFilters): AfterSalesTicket[] {
  const keyword = filters.search.trim().toLowerCase();
  return tickets.filter((ticket) => {
    if (filters.country && ticketCountry(ticket) !== filters.country) return false;
    if (filters.issueType && ticket.issueType !== filters.issueType) return false;
    if (filters.status && ticket.status !== filters.status) return false;
    if (filters.slaRisk && !isTicketSlaRisk(ticket)) return false;
    if (keyword) {
      const haystack = [ticket.ticketNo, ticket.orderId, ticket.customerMessage, ticket.userId]
        .filter(Boolean)
        .join(" ")
        .toLowerCase();
      if (!haystack.includes(keyword)) return false;
    }
    return true;
  });
}

/** 国家：优先后端已知订单推导；否则回退工单详情 finalAnswer；都缺失则「未知」，不伪造。 */
export function ticketCountry(ticket: AfterSalesTicket): string {
  return ticket.country || ticket.run?.finalAnswer?.order?.country || "未知";
}

export function countryLabel(code: string): string {
  const labels: Record<string, string> = {
    VN: "越南", SG: "新加坡", MY: "马来西亚", TH: "泰国", ID: "印尼", CN: "中国",
  };
  return labels[code] || code;
}

/** 非终态状态集合：还处于流程中的工单。 */
function isOpenStatus(status: string): boolean {
  return status === "OPEN" || status === "ANALYZING" || status === "PENDING_APPROVAL";
}

function isTicketSlaRisk(ticket: AfterSalesTicket): boolean {
  return isOpenStatus(ticket.status) && ageHours(ticket.updatedAt) > SLA_RISK_THRESHOLD_HOURS;
}

function ageHours(timestamp: string | undefined): number {
  if (!timestamp) return 0;
  const value = new Date(timestamp).getTime();
  return Number.isFinite(value) ? (Date.now() - value) / 3_600_000 : 0;
}

/** 风险等级（规则评估）：只由动作/金额 vs 政策上限与物流延迟天数等确定性规则输入驱动。
 *  客户紧急度（Intake urgency）不属于动作风险，绝不参与本评估；风险只用于提案/审批上下文。 */
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

export type RiskAssessment = {
  level: RiskLevel;
  /** 规则描述：为什么得到该等级，展示在审批栏。 */
  rule: string;
};

export function assessRiskLevel(input: {
  amount: number;
  currency: string;
  maxCompensation: number;
  delayDays?: number;
}): RiskAssessment {
  const { amount, currency, maxCompensation, delayDays = 0 } = input;
  if (amount > 0 && maxCompensation > 0 && amount > maxCompensation * 0.5) {
    return { level: "HIGH", rule: `金额 ${formatMoney(amount, currency)} 超过政策上限 ${formatMoney(maxCompensation, currency)} 的 50%` };
  }
  if (delayDays >= 10) {
    return { level: "HIGH", rule: `物流延迟 ${delayDays} 天 ≥ 10 天，紧急度高` };
  }
  if (delayDays >= 5 || amount > 0) {
    return { level: "MEDIUM", rule: `物流延迟 ${delayDays} 天或产生补偿金额，需人工确认` };
  }
  return { level: "LOW", rule: "无延迟风险且无补偿金额" };
}

export function riskLevelLabel(level: RiskLevel): string {
  return ({ LOW: "低", MEDIUM: "中", HIGH: "高" } as Record<RiskLevel, string>)[level];
}

export function riskLevelColor(level: RiskLevel): string {
  return ({ LOW: "success", MEDIUM: "warning", HIGH: "error" } as Record<RiskLevel, string>)[level];
}

/** 证据分类：证据 ID 由后端工具写入，前缀固定（order:/shipment:/policy:/calculation:）。 */
export type EvidenceCategory = "ORDER" | "SHIPMENT" | "POLICY" | "CALCULATION" | "OTHER";

export function evidenceCategory(evidenceId: string): EvidenceCategory {
  if (evidenceId.startsWith("order:")) return "ORDER";
  if (evidenceId.startsWith("shipment:")) return "SHIPMENT";
  if (evidenceId.startsWith("policy:")) return "POLICY";
  if (evidenceId.startsWith("calculation:")) return "CALCULATION";
  return "OTHER";
}

export function evidenceCategoryLabel(category: EvidenceCategory): string {
  return ({ ORDER: "订单", SHIPMENT: "物流", POLICY: "政策", CALCULATION: "计算", OTHER: "其他" } as Record<EvidenceCategory, string>)[category];
}

export const EVIDENCE_CATEGORIES: EvidenceCategory[] = ["ORDER", "SHIPMENT", "POLICY", "CALCULATION"];

export type EvidenceItem = {
  category: EvidenceCategory;
  evidenceId?: string;
  verified: boolean;
};

/** 从 run 事件中收集每个类别已产生的证据 ID（tool_completed 事件成功才会带 evidenceIds）。 */
export function collectEvidence(events: AfterSalesEvent[]): EvidenceItem[] {
  const byCategory = new Map<EvidenceCategory, string>();
  for (const event of events) {
    const ids = event.data.evidenceIds || [];
    for (const id of ids) {
      const category = evidenceCategory(id);
      if (category !== "OTHER" && !byCategory.has(category)) byCategory.set(category, id);
    }
  }
  return EVIDENCE_CATEGORIES.map((category) => {
    const evidenceId = byCategory.get(category);
    return { category, evidenceId, verified: Boolean(evidenceId) };
  });
}

export type ApprovalChecks = {
  /** 证据完整：订单/物流/政策/计算四类证据全部产生 */
  evidenceComplete: boolean | undefined;
  /** 符合政策：补偿规则判定 eligible */
  policyCompliant: boolean | undefined;
  /** 金额未超限：proposal 金额 ≤ 政策上限 */
  amountWithinLimit: boolean | undefined;
};

/** 三个只读检查项。数据不足（如无 finalAnswer）返回 undefined，展示「待分析」。 */
export function deriveApprovalChecks(input: {
  evidenceIds?: string[];
  eligible?: boolean;
  amount: number;
  maximumCompensation: number;
}): ApprovalChecks {
  const { evidenceIds = [], eligible, amount, maximumCompensation } = input;
  const categories = new Set(evidenceIds.map(evidenceCategory));
  return {
    evidenceComplete: evidenceIds.length > 0
      ? EVIDENCE_CATEGORIES.every((category) => categories.has(category))
      : undefined,
    policyCompliant: typeof eligible === "boolean" ? eligible : undefined,
    amountWithinLimit: maximumCompensation > 0 ? amount <= maximumCompensation : undefined,
  };
}

export function formatMoney(amount: number | null | undefined, currency: string): string {
  const value = Number(amount ?? 0);
  if (!Number.isFinite(value)) return "—";
  return `${currency} ${value.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function formatDateTime(timestamp?: string): string {
  if (!timestamp) return "—";
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return "—";
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function ticketStatusLabel(status?: string): string {
  const labels: Record<string, string> = {
    OPEN: "待分析",
    ANALYZING: "分析中",
    PENDING_APPROVAL: "待审批",
    RESOLVED: "已完成",
    FAILED: "失败",
  };
  return status ? labels[status] || status : "未创建";
}

export function ticketStatusColor(status?: string): string {
  const colors: Record<string, string> = {
    OPEN: "default",
    ANALYZING: "processing",
    PENDING_APPROVAL: "warning",
    RESOLVED: "success",
    FAILED: "error",
  };
  return status ? colors[status] || "default" : "default";
}

export function proposalStatusLabel(status?: string): string {
  return ({ PENDING: "待审批", APPROVED: "已批准", REJECTED: "已驳回" } as Record<string, string>)[status || ""] || status || "—";
}

export function executionStatusLabel(status?: string): string {
  const labels: Record<string, string> = {
    PENDING: "等待执行",
    RUNNING: "执行中",
    RETRY_WAIT: "等待重试",
    SUCCEEDED: "执行成功",
    DEAD_LETTER: "死信",
  };
  return status ? labels[status] || status : "未执行";
}

/** 执行异常判定：ExecutionJob 进入 RETRY_WAIT / DEAD_LETTER 才算；Agent 分析 FAILED 不计入。 */
export function isExecutionException(status?: string): boolean {
  return status === "RETRY_WAIT" || status === "DEAD_LETTER";
}

/** 政策版本展示：输入 v3 显示 v3，输入 3 显示 v3，空值显示 —。Evidence 与审批栏统一使用。 */
export function policyVersionLabel(version?: string): string {
  if (!version) return "—";
  const trimmed = version.trim();
  return trimmed.startsWith("v") ? trimmed : `v${trimmed}`;
}

export function issueTypeLabel(issueType?: string): string {
  const labels: Record<string, string> = {
    SHIPMENT_DELAY: "物流延迟",
  };
  return issueType ? labels[issueType] || issueType : "—";
}

export function actionLabel(action?: string): string {
  const labels: Record<string, string> = {
    get_order_detail: "订单查询",
    get_shipment_trace: "物流查询",
    search_after_sales_policy: "政策检索",
    calculate_compensation: "规则计算",
    create_action_proposal: "生成方案",
  };
  return action ? labels[action] || action : "状态事件";
}

export function eventTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    run_started: "开始分析",
    intake_started: "开始提取",
    intake_completed: "提取完成",
    planning_started: "开始取证规划",
    planning_completed: "取证规划完成",
    planning_fallback: "规划降级",
    tool_started: "工具调用",
    tool_completed: "工具完成",
    retrieval_completed: "检索完成",
    decision_completed: "决策完成",
    run_completed: "分析完成",
    approval_recorded: "审批记录",
    execution_started: "开始执行",
    execution_completed: "执行完成",
    execution_failed: "执行失败",
    error: "异常",
  };
  return labels[type] || type;
}

/** 决策路线展示名（与后端 DecisionRoute 枚举一致）。 */
export function decisionRouteLabel(route?: string): string {
  const labels: Record<string, string> = {
    ANSWER_ONLY: "直接答复",
    COMPENSATION_EVALUATION: "补偿评估",
    REQUEST_MORE_INFO: "补充信息",
    HUMAN_ESCALATION: "人工升级",
  };
  return route ? labels[route] || route : "—";
}

/** Planner 证据类型展示名（与后端 AfterSalesTypes.EvidenceType 一致）。 */
export function evidenceTypeLabel(type?: string): string {
  const labels: Record<string, string> = {
    ORDER: "订单",
    SHIPMENT: "物流",
    POLICY: "政策",
    READY_FOR_DECISION: "就绪决策",
  };
  return type ? labels[type] || type : "—";
}

/**
 * Planner 理由码 / 降级码的本地化展示。reasonCode（ORDER_CONTEXT_REQUIRED 等）与
 * fallbackReason（LLM_TIMEOUT / LLM_INVALID_PLAN 等）共用同一张表，只暴露安全错误码。
 */
export function plannerReasonLabel(code?: string): string {
  const labels: Record<string, string> = {
    ORDER_CONTEXT_REQUIRED: "缺少订单上下文",
    SHIPMENT_STATUS_REQUIRED: "缺少物流状态",
    POLICY_REQUIRED: "缺少适用政策",
    EVIDENCE_COMPLETE: "证据齐备，可进入决策",
    RULES_MODE: "规则模式规划",
    LLM_API_KEY_MISSING: "API Key 未配置，降级规则",
    LLM_TIMEOUT: "模型超时，降级规则",
    LLM_BUSY: "模型繁忙，降级规则",
    LLM_EMPTY_RESPONSE: "模型空响应，降级规则",
    LLM_INVALID_JSON: "模型输出非法 JSON，降级规则",
    LLM_INVALID_OUTPUT: "模型输出非法字段，降级规则",
    LLM_INVALID_PLAN: "模型规划无效，降级规则",
    LLM_ERROR: "模型异常，降级规则",
  };
  return code ? labels[code] || code : "—";
}

export function plannerSourceLabel(source?: string): string {
  return source === "LLM" ? "模型规划" : source === "RULE_FALLBACK" ? "规则规划" : source || "—";
}

export type EvidencePlanItem = {
  type: "ORDER" | "SHIPMENT" | "POLICY";
  /** Planner 已请求过该证据（planning_completed.nextEvidence） */
  requested: boolean;
  /** 该类别证据已实际产生（tool_completed.evidenceIds）——仅代表「已获取」，不代表人工核验。 */
  verified: boolean;
};

export type EvidencePlanState = {
  items: EvidencePlanItem[];
  readyForDecision: boolean;
};

/** 从 intake_completed 事件提取服务端路线重建的必需证据清单（直播流阶段）；无事件时返回 undefined。 */
export function intakeRequiredEvidence(events: AfterSalesEvent[]): string[] | undefined {
  let latest: string[] | undefined;
  for (const event of events) {
    if (event.type !== "intake_completed") continue;
    const value = event.data.requiredEvidence;
    if (Array.isArray(value) && value.length > 0) {
      latest = value.filter(
        (item): item is string => item === "ORDER" || item === "SHIPMENT" || item === "POLICY",
      );
    }
  }
  return latest;
}

/**
 * 从 run 事件推导紧凑证据规划：Planner 请求过（planning_completed.nextEvidence）+
 * 实际已产生证据（evidenceIds 前缀分类）。READY_FOR_DECISION 由 Planner 事件权威给出；
 * 兼容无 Planner 事件的历史 run：必需证据全部齐备也算就绪。
 *
 * 必需证据尊重服务端路线清单（requiredEvidence，来自 intake_completed 或 finalAnswer.intake）：
 * ANSWER_ONLY 只展示 ORDER/SHIPMENT，绝不把 POLICY 画成「缺失」；
 * 缺省（无 Intake 数据）时回退三类全量展示。
 */
export function deriveEvidencePlan(events: AfterSalesEvent[], requiredEvidence?: string[]): EvidencePlanState {
  const requested = new Set<string>();
  let readyForDecision = false;
  for (const event of events) {
    if (event.type !== "planning_completed") continue;
    const evidence = typeof event.data.nextEvidence === "string" ? event.data.nextEvidence : "";
    if (evidence === "READY_FOR_DECISION") readyForDecision = true;
    else if (evidence) requested.add(evidence);
  }
  const verified = new Set(
    collectEvidence(events).filter((item) => item.verified).map((item) => item.category),
  );
  const required = requiredEvidence?.length
    ? requiredEvidence.filter((item): item is "ORDER" | "SHIPMENT" | "POLICY" =>
        item === "ORDER" || item === "SHIPMENT" || item === "POLICY")
    : (["ORDER", "SHIPMENT", "POLICY"] as const);
  const items: EvidencePlanItem[] = required.map((type) => ({
    type,
    requested: requested.has(type),
    verified: verified.has(type),
  }));
  if (!readyForDecision && items.every((item) => item.verified)) readyForDecision = true;
  return { items, readyForDecision };
}

export function streamLabel(status: string): string {
  if (status === "connecting") return "SSE 连接中";
  if (status === "live") return "SSE 实时";
  if (status === "closed") return "运行已结束";
  if (status === "error") return "SSE 已断开";
  return "等待运行";
}

/** 事件去重合并：以 eventId / sequence 判重，按 sequence 升序排列。页面与 Workspace 共用。 */
export function appendEvent(events: AfterSalesEvent[], next: AfterSalesEvent): AfterSalesEvent[] {
  if (events.some((item) => item.eventId === next.eventId || item.sequence === next.sequence)) return events;
  return [...events, next].sort((a, b) => a.sequence - b.sequence);
}
