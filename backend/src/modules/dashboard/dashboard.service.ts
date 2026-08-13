import { Injectable } from "@nestjs/common";
import { AgentRunStatus } from "@prisma/client";
import { PrismaService } from "../../infrastructure/database/prisma.service";

@Injectable()
export class DashboardService {
  constructor(private readonly prisma: PrismaService) {}

  async overview() {
    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);

    const [todayRuns, completedRuns, failedRuns, runningRuns, metrics, productCount, readyProducts, recentRuns, recentEvents] = await this.prisma.$transaction([
      this.prisma.agentRun.count({ where: { createdAt: { gte: todayStart } } }),
      this.prisma.agentRun.count({ where: { status: AgentRunStatus.COMPLETED } }),
      this.prisma.agentRun.count({ where: { status: AgentRunStatus.FAILED } }),
      this.prisma.agentRun.count({ where: { status: AgentRunStatus.RUNNING } }),
      this.prisma.agentRun.aggregate({
        where: { status: AgentRunStatus.COMPLETED },
        _avg: { latencyMs: true },
        _sum: { totalTokens: true },
        _count: { id: true },
      }),
      this.prisma.product.count(),
      this.prisma.product.count({ where: { embeddingStatus: "READY" } }),
      this.prisma.agentRun.findMany({
        orderBy: { createdAt: "desc" },
        take: 8,
        include: { events: { select: { id: true } } },
      }),
      this.prisma.agentEvent.findMany({
        orderBy: { createdAt: "desc" },
        take: 10,
        include: { run: { select: { id: true, status: true } } },
      }),
    ]);

    const finishedRuns = completedRuns + failedRuns;
    const successRate = finishedRuns === 0 ? 0 : Math.round((completedRuns / finishedRuns) * 1000) / 10;

    return {
      metrics: {
        todayRuns,
        successRate,
        avgLatencyMs: Math.round(metrics._avg.latencyMs || 0),
        totalTokens: metrics._sum.totalTokens || 0,
        runningRuns,
        productCount,
        readyProductCount: readyProducts,
      },
      recentRuns: recentRuns.map((run) => ({
        id: run.id,
        taskType: run.taskType.toLowerCase(),
        userId: run.userId,
        status: run.status.toLowerCase(),
        modelName: run.modelName,
        promptVersion: run.promptVersion,
        stepCount: run.events.length,
        toolCallCount: run.toolCallCount,
        latencyMs: run.latencyMs,
        inputTokens: run.inputTokens,
        outputTokens: run.outputTokens,
        totalTokens: run.totalTokens,
        createdAt: run.createdAt.toISOString(),
      })),
      recentEvents: recentEvents.map((event) => ({
        eventId: event.id,
        runId: event.runId,
        sequence: event.sequence,
        type: event.eventType.toLowerCase(),
        name: event.eventName,
        status: event.status.toLowerCase(),
        timestamp: event.createdAt.toISOString(),
        data: event.rawPayload,
        metrics: { latencyMs: event.latencyMs || 0 },
      })),
    };
  }
}
