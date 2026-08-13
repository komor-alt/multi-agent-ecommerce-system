import { Body, Controller, Get, Post } from "@nestjs/common";
import { EvaluationsService } from "./evaluations.service";
import { CreateEvaluationRunDto } from "./dto/evaluation.dto";

@Controller("evaluations")
export class EvaluationsController {
  constructor(private readonly service: EvaluationsService) {}

  @Get("runs")
  listRuns() {
    return { success: true, data: this.service.list(), requestId: "local-evaluations-list" };
  }

  @Post("runs")
  createRun(@Body() body: CreateEvaluationRunDto) {
    return { success: true, data: { status: "contract_ready", input: body }, requestId: "local-evaluation-create" };
  }
}
