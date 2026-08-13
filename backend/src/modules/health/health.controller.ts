import { Controller, Get } from "@nestjs/common";

@Controller("health")
export class HealthController {
  @Get()
  getHealth() {
    return {
      success: true,
      data: {
        status: "ok",
        service: "multi-agent-ecommerce-gateway",
        timestamp: new Date().toISOString(),
      },
      requestId: "local-health-check",
    };
  }
}
