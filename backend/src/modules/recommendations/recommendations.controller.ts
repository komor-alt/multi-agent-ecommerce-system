import { Body, Controller, Get, NotFoundException, Param, Post } from "@nestjs/common";
import { RecommendationsService } from "./recommendations.service";
import { CreateRecommendationTaskDto } from "./dto/create-recommendation-task.dto";

@Controller(["recommendations", "recommendation-tasks"])
export class RecommendationsController {
  constructor(private readonly service: RecommendationsService) {}

  @Get()
  async list() {
    return { success: true, data: await this.service.list(), requestId: "local-recommendations-list" };
  }

  @Get(":id")
  async detail(@Param("id") id: string) {
    const data = await this.service.detail(id);
    if (!data) throw new NotFoundException("Recommendation task not found");
    return { success: true, data, requestId: id };
  }

  @Post()
  async create(@Body() body: CreateRecommendationTaskDto) {
    const data = await this.service.create(body);
    return { success: true, data, requestId: data.runId };
  }
}
