import { Module } from "@nestjs/common";
import { AgentRunEventBus } from "./agent-run-event-bus";
import { AgentRunsController } from "./agent-runs.controller";
import { AgentRunsService } from "./agent-runs.service";
import { AgentRunsRepository } from "./agent-runs.repository";

@Module({
  controllers: [AgentRunsController],
  providers: [AgentRunsService, AgentRunsRepository, AgentRunEventBus],
  exports: [AgentRunsService, AgentRunsRepository, AgentRunEventBus],
})
export class AgentRunsModule {}
