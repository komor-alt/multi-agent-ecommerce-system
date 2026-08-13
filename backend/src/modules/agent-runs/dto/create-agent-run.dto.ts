import { AgentRunStatus, AgentTaskType } from "../../../common/enums/agent.enums";

export class CreateAgentRunDto {
  runId!: string;
  taskType!: AgentTaskType;
  userId!: string;
  input!: Record<string, unknown>;
  agentConfig!: {
    model: string;
    maxSteps: number;
    temperature?: number;
    maxTokens?: number;
    ragTopK?: number;
    toolWhitelist: string[];
  };
}

export class AgentRunListQueryDto {
  page?: number;
  pageSize?: number;
  keyword?: string;
  taskType?: AgentTaskType;
  status?: AgentRunStatus;
  createdFrom?: string;
  createdTo?: string;
  sortBy?: "createdAt" | "latencyMs" | "totalTokens" | "toolCallCount";
  sortOrder?: "asc" | "desc";
}
