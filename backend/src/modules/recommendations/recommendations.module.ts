import { Module } from "@nestjs/common";
import { AgentClientModule } from "../../infrastructure/agent-client/agent-client.module";
import { AgentRunsModule } from "../agent-runs/agent-runs.module";
import { RecommendationsController } from "./recommendations.controller";
import { RecommendationsService } from "./recommendations.service";
import { RecommendationsRepository } from "./recommendations.repository";

@Module({
  imports: [AgentClientModule, AgentRunsModule],
  controllers: [RecommendationsController],
  providers: [RecommendationsService, RecommendationsRepository],
  exports: [RecommendationsService],
})
export class RecommendationsModule {}
