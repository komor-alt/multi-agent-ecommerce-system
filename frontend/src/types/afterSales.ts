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

export type AfterSalesFinalAnswer = {
  ticketId: string;
  order: OrderSnapshot;
  shipment: ShipmentSnapshot;
  policy: PolicyEvidence;
  compensation: CompensationResult;
  proposalId: string;
  requiresApproval: boolean;
  evidenceIds: string[];
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
  userId?: string;
  issueType: string;
  customerMessage: string;
  status: AfterSalesTicketStatus;
  runId?: string;
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
    finalAnswer?: AfterSalesFinalAnswer;
  };
  events?: AfterSalesEvent[];
};

export type AnalyzeTicketResponse = {
  ticketId: string;
  runId: string;
  status: string;
  streamUrl: string;
};
