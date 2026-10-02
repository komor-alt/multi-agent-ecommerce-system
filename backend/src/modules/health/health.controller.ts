import { Controller, Get } from "@nestjs/common";
import { Public } from "../auth/auth.decorators";

@Controller("health")
@Public()
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
