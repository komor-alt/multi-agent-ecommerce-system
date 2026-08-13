export enum UserRole {
  Admin = "admin",
  Operator = "operator",
  Viewer = "viewer",
}

export enum AgentTaskType {
  ProductRecommendation = "product_recommendation",
  AfterSalesAssistant = "after_sales_assistant",
  Evaluation = "evaluation",
}

export enum AgentRunStatus {
  Pending = "pending",
  Running = "running",
  Completed = "completed",
  Failed = "failed",
  Cancelled = "cancelled",
  Timeout = "timeout",
}

export enum AgentEventType {
  RunStarted = "run_started",
  ModelStarted = "model_started",
  ModelCompleted = "model_completed",
  ToolStarted = "tool_started",
  ToolCompleted = "tool_completed",
  RetrievalCompleted = "retrieval_completed",
  Warning = "warning",
  Error = "error",
  RunCompleted = "run_completed",
  Heartbeat = "heartbeat",
}

export enum AgentEventStatus {
  Pending = "pending",
  Running = "running",
  Success = "success",
  Failed = "failed",
  Skipped = "skipped",
}

export enum ReviewStatus {
  NotRequired = "not_required",
  Pending = "pending",
  Approved = "approved",
  Rejected = "rejected",
}
