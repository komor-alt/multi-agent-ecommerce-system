export enum ErrorCode {
  Unauthorized = "UNAUTHORIZED",
  Forbidden = "FORBIDDEN",
  ValidationFailed = "VALIDATION_FAILED",
  ResourceNotFound = "RESOURCE_NOT_FOUND",
  AgentRunNotFound = "AGENT_RUN_NOT_FOUND",
  AgentRunAlreadyCompleted = "AGENT_RUN_ALREADY_COMPLETED",
  AgentServiceUnavailable = "AGENT_SERVICE_UNAVAILABLE",
  AgentServiceTimeout = "AGENT_SERVICE_TIMEOUT",
  AgentEventSequenceConflict = "AGENT_EVENT_SEQUENCE_CONFLICT",
  ToolNotAllowed = "TOOL_NOT_ALLOWED",
  ConfigNotFound = "CONFIG_NOT_FOUND",
  InternalError = "INTERNAL_ERROR",
}
