import { Controller, Get, NotFoundException, Param, Sse } from "@nestjs/common";
import { AgentRunsService } from "./agent-runs.service";

@Controller("agent-runs")
export class AgentRunsController {
  constructor(private readonly service: AgentRunsService) {}

  @Get()
  async list() {
    return { success: true, data: await this.service.list(), requestId: "local-agent-runs-list" };
  }

  @Get(":runId")
  async detail(@Param("runId") runId: string) {
    const data = await this.service.detail(runId);
    if (!data) throw new NotFoundException("Agent run not found");
    return { success: true, data, requestId: runId };
  }

  @Get(":runId/events")
  async events(@Param("runId") runId: string) {
    return { success: true, data: await this.service.events(runId), requestId: runId };
  }

  @Sse(":runId/stream")
  stream(@Param("runId") runId: string) {
    return this.service.stream(runId);
  }
}
