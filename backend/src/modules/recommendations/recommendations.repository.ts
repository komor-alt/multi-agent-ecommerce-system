import { Injectable } from "@nestjs/common";
import { Prisma, AgentRunStatus, AgentTaskType, RecommendationTaskStatus } from "@prisma/client";
import { PrismaService } from "../../infrastructure/database/prisma.service";

@Injectable()
export class RecommendationsRepository {
  constructor(private readonly prisma: PrismaService) {}

  list() {
    return this.prisma.recommendationTask.findMany({
      orderBy: { createdAt: "desc" },
      take: 20,
      include: {
        agentRun: {
          select: {
            id: true,
            status: true,
            completedAt: true,
            latencyMs: true,
            toolCallCount: true,
          },
        },
      },
    });
  }

  findDetail(taskId: string) {
    return this.prisma.recommendationTask.findUnique({
      where: { id: taskId },
      include: {
        agentRun: {
          include: {
            events: { orderBy: { sequence: "asc" } },
          },
        },
      },
    });
  }
  async ensureUser(userId: string) {
    return this.prisma.user.upsert({
      where: { id: userId },
      update: {},
      create: {
        id: userId,
        email: `${userId}@local.ecommerce`,
        name: userId,
      },
    });
  }

  async createTaskWithRun(params: {
    taskId: string;
    runId: string;
    userId: string;
    scene: string;
    input: Prisma.InputJsonValue;
    modelName: string;
  }) {
    await this.ensureUser(params.userId);
    return this.prisma.$transaction(async (tx) => {
      const run = await tx.agentRun.create({
        data: {
          id: params.runId,
          taskType: AgentTaskType.PRODUCT_RECOMMENDATION,
          userId: params.userId,
          status: AgentRunStatus.RUNNING,
          modelName: params.modelName,
          startedAt: new Date(),
          requestPayload: params.input,
        },
      });
      const task = await tx.recommendationTask.create({
        data: {
          id: params.taskId,
          taskType: AgentTaskType.PRODUCT_RECOMMENDATION,
          userId: params.userId,
          runId: params.runId,
          scene: params.scene,
          status: RecommendationTaskStatus.RUNNING,
          input: params.input,
        },
      });
      return { task, run };
    });
  }

  async completeTask(taskId: string, finalAnswer: Prisma.InputJsonValue) {
    return this.prisma.recommendationTask.update({
      where: { id: taskId },
      data: {
        status: RecommendationTaskStatus.COMPLETED,
        completedAt: new Date(),
        finalAnswer,
      },
    });
  }

  async failTask(taskId: string) {
    return this.prisma.recommendationTask.update({
      where: { id: taskId },
      data: { status: RecommendationTaskStatus.FAILED, completedAt: new Date() },
    });
  }
}
