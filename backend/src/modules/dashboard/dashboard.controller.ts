import { Controller, Get } from "@nestjs/common";
import { DashboardService } from "./dashboard.service";

@Controller("dashboard")
export class DashboardController {
  constructor(private readonly service: DashboardService) {}

  @Get("overview")
  async overview() {
    return { success: true, data: await this.service.overview(), requestId: "local-dashboard-overview" };
  }
}
