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

  async list() {
    const tasks = await this.repository.list();
    return {
      items: tasks.map(toTaskSummary),
      page: 1,
      pageSize: tasks.length,
      total: tasks.length,
    };
  }

  async detail(taskId: string) {
    const task = await this.repository.findDetail(taskId);
    if (!task) return null;
    return {
      ...toTaskSummary(task),
      input: task.input,
      run: task.agentRun
        ? {
            id: task.agentRun.id,
            status: task.agentRun.status.toLowerCase(),
            completedAt: task.agentRun.completedAt?.toISOString() || null,
            latencyMs: task.agentRun.latencyMs,
            toolCallCount: task.agentRun.toolCallCount,
            events: task.agentRun.events.map((event) => ({
              id: event.id,
              sequence: event.sequence,
              name: event.eventName,
              status: event.status.toLowerCase(),
              createdAt: event.createdAt.toISOString(),
            })),
          }
        : null,
    };
  }

  async create(dto: CreateRecommendationTaskDto) {
    const taskId = randomUUID();
    const runId = randomUUID();
    const model = dto.agentConfig?.model || this.config.get<string>("DEFAULT_AGENT_MODEL") || "deepseek-v4-flash";
    const context = (dto.context || {}) as Prisma.InputJsonObject;
    const input = {
      scene: dto.scene,
      num_items: dto.numItems,
      platform: dto.platform || stringValue(context.platform) || "shopify",
      region: dto.region || stringValue(context.region) || "SEA",
      country: dto.country || stringValue(context.country) || "SG",
      locale: dto.locale || stringValue(context.locale) || "en-SG",
      currency: dto.currency || stringValue(context.currency) || "SGD",
      context,
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
    let toolCallCount = 0;
    let completed = false;

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
          tool_whitelist: params.dto.agentConfig?.toolWhitelist || [
            "get_user_profile", "load_campaign_constraints", "get_recent_orders",
            "search_products", "check_fulfillment", "check_inventory",
            "rerank", "generate_localized_copy", "generate_retention_copy", "final_answer",
          ],
        },
      })) {
        eventCount += 1;
        if (event.name === "tool.completed" && event.status === "success") toolCallCount += 1;
        await this.agentRunsService.persistAndPublishEvent(params.runId, event);
        if (event.name === "run.completed") {
          completed = true;
          finalAnswer = extractFinalAnswer(event);
          latencyMs = event.metrics?.latency_ms || latencyMs;
        }
      }

      if (!completed) throw new Error("JAVA_AGENT_STREAM_ENDED_WITHOUT_RUN_COMPLETED");

      await this.agentRunsService.completeRealtimeRun(params.runId, finalAnswer, {
        latency_ms: latencyMs,
        input_tokens: nullableNumber(objectValue(finalAnswer.llmMetrics).promptTokens),
        output_tokens: nullableNumber(objectValue(finalAnswer.llmMetrics).completionTokens),
        total_tokens: nullableNumber(objectValue(finalAnswer.llmMetrics).totalTokens),
        tool_call_count: toolCallCount,
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

function toTaskSummary(task: any) {
  const input = objectValue(task.input);
  const market = {
    platform: stringValue(input.platform) || "shopify",
    region: stringValue(input.region) || "SEA",
    country: stringValue(input.country) || "SG",
    locale: stringValue(input.locale) || "en-SG",
    currency: stringValue(input.currency) || "SGD",
  };
  return {
    id: task.id,
    taskType: task.taskType.toLowerCase(),
    userId: task.userId,
    scene: task.scene,
    market,
    status: task.status.toLowerCase(),
    runId: task.runId,
    finalRecommendationPlan: task.finalAnswer || null,
    createdAt: task.createdAt.toISOString(),
    completedAt: task.completedAt?.toISOString() || task.agentRun?.completedAt?.toISOString() || null,
  };
}

function objectValue(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}
function extractFinalAnswer(event: AgentServiceRawEvent) {
  const answer = event.data.final_answer || event.data.response;
  if (answer && typeof answer === "object" && !Array.isArray(answer)) {
    return answer as Record<string, unknown>;
  }
  return event.data;
}

function nullableNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? Math.round(value) : null;
}

function stringValue(value: unknown) {
  return typeof value === "string" && value.trim() ? value : undefined;
}
