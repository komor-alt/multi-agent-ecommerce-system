export type AgentServiceRawEvent = {
  event_id: string;
  run_id: string;
  sequence: number;
  type: string;
  name: string;
  status: string;
  timestamp: string;
  data: Record<string, unknown>;
  metrics?: {
    latency_ms?: number;
    input_tokens?: number;
    output_tokens?: number;
    total_tokens?: number;
  };
};

export type AgentServiceRunRequest = {
  run_id: string;
  task_type: string;
  user_id: string;
  input: Record<string, unknown>;
  agent_config: {
    model: string;
    max_steps: number;
    temperature?: number;
    max_tokens?: number;
    rag_top_k?: number;
    tool_whitelist: string[];
  };
};

export type AgentServiceRunResponse = {
  run_id: string;
  status: "pending" | "running" | "completed" | "failed" | "cancelled" | "timeout";
  final_answer?: Record<string, unknown> | null;
  events: AgentServiceRawEvent[];
  metrics: {
    latency_ms?: number;
    input_tokens: number;
    output_tokens: number;
    total_tokens: number;
    tool_call_count: number;
  };
  error?: {
    code?: string;
    message?: string;
  } | null;
};
