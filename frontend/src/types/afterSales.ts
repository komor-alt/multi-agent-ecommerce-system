export type AfterSalesTicketStatus = "OPEN" | "ANALYZING" | "PENDING_APPROVAL" | "RESOLVED" | "FAILED";
export type ProposalStatus = "PENDING" | "APPROVED" | "REJECTED";
export type ExecutionStatus = "PENDING" | "RUNNING" | "RETRY_WAIT" | "SUCCEEDED" | "DEAD_LETTER";

export type AfterSalesEvent = {
  eventId: string;
  runId: string;
  sequence: number;
  type: string;
  name: string;
  status: "running" | "success" | "failed";
  timestamp: string;
  data: {
    summary?: string;
    action?: string;
    arguments?: Record<string, unknown>;
    observation?: unknown;
    evidenceIds?: string[];
    latencyMs?: number;
    durationMs?: number;
    finalAnswer?: AfterSalesFinalAnswer;
    [key: string]: unknown;
  };
};

export type OrderSnapshot = {
  orderId: string;
  userId: string;
  platform: string;
  country: string;
  currency: string;
  warehouseRegion: string;
  paidAmount: number;
  paid: boolean;
  fulfillmentStatus: string;
  promisedDeliveryDays: number;
  trackingNumber: string;
};

export type ShipmentSnapshot = {
  trackingNumber: string;
  status: string;
  lastUpdatedAt: string;
  inactiveDays: number;
  delayDays: number;
  timeline: Array<{
    occurredAt: string;
    status: string;
    location: string;
    description: string;
  }>;
};

export type PolicyEvidence = {
  evidenceId: string;
  policyId: string;
  version: string;
  country: string;
  issueType: string;
  effectiveFrom: string;
  minimumInactiveDays: number;
  compensationRate: number;
  maximumCompensation: number;
  actionType: string;
  section: string;
  summary: string;
};

export type CompensationResult = {
  eligible: boolean;
  actionType: string;
  amount: number;
  currency: string;
  reason: string;
};

/** Intake Agent 的结构化分类结果（不可变，后端 AfterSalesTypes.IntakeResult 序列化而来）。 */
export type AfterSalesIntake = {
  issueType: string;
  intents: string[];
  urgency: "LOW" | "MEDIUM" | "HIGH";
  entities?: { deadline?: string };
  missingInfo: string[];
  requiredEvidence: string[];
  source: "LLM" | "RULE_FALLBACK";
  fallbackReason?: string;
};

/** 决策路线（后端 DecisionRoute，由服务端确定性解析，模型不能输出）。 */
export type DecisionRoute =
  | "ANSWER_ONLY"
  | "COMPENSATION_EVALUATION"
  | "REQUEST_MORE_INFO"
  | "HUMAN_ESCALATION";

/**
 * 终态答复。字段按路线可选：ANSWER_ONLY 只有 answerType/answer（物流状态答复），
 * 没有 policy/compensation/proposalId；COMPENSATION_EVALUATION 有 compensation，
 * eligible=true 时才有 proposalId。缺失字段不输出 null。
 */
export type AfterSalesFinalAnswer = {
  ticketId: string;
  order: OrderSnapshot;
  shipment: ShipmentSnapshot;
  policy?: PolicyEvidence;
  compensation?: CompensationResult;
  proposalId?: string;
  requiresApproval: boolean;
  evidenceIds: string[];
  intake?: AfterSalesIntake;
  /** 服务端解析的决策路线：直接答复 / 补偿评估（预留升级路线 MVP 不产生）。 */
  decisionRoute?: DecisionRoute;
  /** ANSWER_ONLY 路线的答复类型（当前固定 SHIPMENT_STATUS）。 */
  answerType?: string;
  /** ANSWER_ONLY 路线的结构化物流答复（确定性 Java 模板，非 LLM 文本）。 */
  answer?: {
    trackingNumber: string;
    status: string;
    lastUpdatedAt: string;
    inactiveDays: number;
    delayDays: number;
  };
  /** COMPENSATION_EVALUATION 路线的补偿资格结论（不可补偿时 false）。 */
  eligible?: boolean;
  /** 决策动作：可补偿 = 政策动作类型；不可补偿 = NO_ACTION。 */
  action?: string;
  /** 决策依据：不可补偿时为政策未达阈值原因（如 POLICY_THRESHOLD_NOT_REACHED）。 */
  reason?: string;
  decisionSummary: string;
};

export type ActionProposal = {
  id: string;
  ticketId: string;
  actionType: string;
  amount: number;
  currency: string;
  policyVersion: string;
  proposalVersion: string;
  decisionSummary: string;
  evidenceIds: string[];
  status: ProposalStatus;
  reviewedBy?: string;
  reviewComment?: string;
  reviewedAt?: string;
};

export type ExecutionJob = {
  id: string;
  proposalId: string;
  actionType: string;
  amount: number;
  currency: string;
  status: ExecutionStatus;
  attemptCount: number;
  nextRetryAt?: string;
  lastError?: string;
  result?: {
    externalReference?: string;
    status?: string;
    executedAt?: string;
  };
  updatedAt?: string;
};

export type AfterSalesTicket = {
  id: string;
  ticketNo: string;
  orderId: string;
  /** 后端从已知订单数据推导；未知订单为 undefined，前端展示「未知」。 */
  country?: string;
  userId?: string;
  issueType: string;
  customerMessage: string;
  status: AfterSalesTicketStatus;
  runId?: string;
  /** 队列列表可选字段：工单最新执行任务的执行状态（仅存在执行任务时由后端输出）。 */
  executionStatus?: ExecutionStatus;
  createdAt: string;
  updatedAt: string;
  proposal?: ActionProposal;
  executionJob?: ExecutionJob;
  approvals?: Array<{
    id: string;
    decision: string;
    operatorId: string;
    comment?: string;
    createdAt: string;
  }>;
  run?: {
    id: string;
    status: string;
    stepCount: number;
    maxSteps: number;
    stopReason?: string;
    durationMs?: number;
    finalAnswer?: AfterSalesFinalAnswer;
  };
  events?: AfterSalesEvent[];
};

export type AnalyzeTicketResponse = {
  ticketId: string;
  runId: string;
  status: string;
  runStatus?: RunStatus;
  streamUrl: string;
};

export type RunStatus = "READY" | "RUNNING" | "COMPLETED" | "FAILED";

export type StartRunResponse = {
  runId: string;
  ticketId: string;
  status: RunStatus;
  startedAt?: string;
  completedAt?: string;
  durationMs?: number;
  stopReason?: string;
};
