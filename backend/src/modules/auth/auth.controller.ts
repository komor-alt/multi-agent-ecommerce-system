import { Controller, Get } from "@nestjs/common";
import { AuthService } from "./auth.service";

@Controller("auth")
export class AuthController {
  constructor(private readonly service: AuthService) {}

  @Get("me")
  me() {
    return { success: true, data: { role: "viewer" }, requestId: "local-auth-me" };
  }
}
