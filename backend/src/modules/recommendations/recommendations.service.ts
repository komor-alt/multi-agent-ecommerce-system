import { Injectable } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { Prisma } from "@prisma/client";
import { randomUUID } from "node:crypto";
import { AgentClientService } from "../../infrastructure/agent-client/agent-client.service";
import type { AgentServiceRawEvent } from "../../infrastructure/agent-client/agent-service.contract";
import { AgentRunsService } from "../agent-runs/agent-runs.service";
import { CreateRecommendationTaskDto } from "./dto/create-recommendation-task.dto";
import { RecommendationsRepository } from "./recommendations.repository";

@Injectable()
export class RecommendationsService {
  constructor(
    private readonly repository: RecommendationsRepository,
    private readonly agentRunsService: AgentRunsService,
    private readonly agentClient: AgentClientService,
    private readonly config: ConfigService,
  ) {}

  list() {
    return { items: [], page: 1, pageSize: 20, total: 0 };
  }

  async create(dto: CreateRecommendationTaskDto) {
    const taskId = randomUUID();
    const runId = randomUUID();
    const model = dto.agentConfig?.model || this.config.get<string>("DEFAULT_AGENT_MODEL") || "deepseek-v4-flash";
    const input = {
      scene: dto.scene,
      num_items: dto.numItems,
      context: (dto.context || {}) as Prisma.InputJsonObject,
    } satisfies Prisma.InputJsonObject;

    await this.repository.createTaskWithRun({
      taskId,
      runId,
      userId: dto.userId,
      scene: dto.scene,
      input,
      modelName: model,
    });

    void this.executeAgentRun({ taskId, runId, dto, model, input });

    return {
      id: taskId,
      runId,
      status: "running",
      streamUrl: `/api/v1/agent-runs/${runId}/stream`,
    };
  }

  private async executeAgentRun(params: {
    taskId: string;
    runId: string;
    dto: CreateRecommendationTaskDto;
    model: string;
    input: Prisma.InputJsonObject;
  }) {
    let finalAnswer: Record<string, unknown> = {};
    let latencyMs = 0;
    let eventCount = 0;

    try {
      for await (const event of this.agentClient.streamRecommendationRun({
        run_id: params.runId,
        task_type: "product_recommendation",
        user_id: params.dto.userId,
        input: params.input,
        agent_config: {
          model: params.model,
          max_steps: params.dto.agentConfig?.maxSteps || 8,
          temperature: params.dto.agentConfig?.temperature ?? 0.3,
          max_tokens: params.dto.agentConfig?.maxTokens || 1024,
          rag_top_k: params.dto.agentConfig?.ragTopK || 5,
          tool_whitelist: params.dto.agentConfig?.toolWhitelist || ["get_user_profile", "search_products", "check_inventory", "generate_copy"],
        },
      })) {
        eventCount += 1;
        await this.agentRunsService.persistAndPublishEvent(params.runId, event);
        if (event.name === "run.completed") {
          finalAnswer = extractFinalAnswer(event);
          latencyMs = event.metrics?.latency_ms || latencyMs;
        }
      }

      await this.agentRunsService.completeRealtimeRun(params.runId, finalAnswer, {
        latency_ms: latencyMs,
        input_tokens: 0,
        output_tokens: 0,
        total_tokens: 0,
        tool_call_count: 0,
      });
      await this.repository.completeTask(params.taskId, finalAnswer as Prisma.InputJsonObject);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      await this.agentRunsService.failRealtimeRun(params.runId, message).catch(() => undefined);
      await this.repository.failTask(params.taskId).catch(() => undefined);
      if (eventCount === 0) {
        // No subscriber may exist yet, but the failure is persisted in agent_runs for detail pages.
      }
    }
  }
}

function extractFinalAnswer(event: AgentServiceRawEvent) {
  const response = event.data.response;
  if (response && typeof response === "object" && !Array.isArray(response)) {
    return response as Record<string, unknown>;
  }
  return event.data;
}
