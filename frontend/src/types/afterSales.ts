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

export type AfterSalesFinalAnswer = {
  ticketId: string;
  order: OrderSnapshot;
  shipment: ShipmentSnapshot;
  policy: PolicyEvidence;
  compensation: CompensationResult;
  proposalId: string;
  requiresApproval: boolean;
  evidenceIds: string[];
  intake?: AfterSalesIntake;
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
