import { Body, Controller, Get, Post } from "@nestjs/common";
import { RecommendationsService } from "./recommendations.service";
import { CreateRecommendationTaskDto } from "./dto/create-recommendation-task.dto";

@Controller("recommendation-tasks")
export class RecommendationsController {
  constructor(private readonly service: RecommendationsService) {}

  @Get()
  list() {
    return { success: true, data: this.service.list(), requestId: "local-recommendations-list" };
  }

  @Post()
  async create(@Body() body: CreateRecommendationTaskDto) {
    const data = await this.service.create(body);
    return { success: true, data, requestId: data.runId };
  }
}
