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

/** 风险等级（规则评估）：金额占政策上限比例 + 物流紧急度（延迟天数）驱动。 */
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
  /** Intake 识别的紧急度优先于金额/物流规则：HIGH 紧急度直接进入 HIGH 风险。 */
  intakeUrgency?: RiskLevel;
}): RiskAssessment {
  const { amount, currency, maxCompensation, delayDays = 0, intakeUrgency } = input;
  if (intakeUrgency === "HIGH") {
    return { level: "HIGH", rule: "Intake 识别为 HIGH 紧急度，需优先人工审批" };
  }
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
    tool_started: "工具调用",
    tool_completed: "工具完成",
    retrieval_completed: "检索完成",
    run_completed: "分析完成",
    approval_recorded: "审批记录",
    execution_started: "开始执行",
    execution_completed: "执行完成",
    execution_failed: "执行失败",
    error: "异常",
  };
  return labels[type] || type;
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
