import { Controller, Get, Patch } from "@nestjs/common";
import { Roles } from "../auth/auth.decorators";

@Controller("settings")
@Roles("ADMIN")
export class SettingsController {
  @Get()
  list() {
    return { success: true, data: { items: [] }, requestId: "local-settings-list" };
  }

  @Patch()
  update() {
    return { success: true, data: { status: "audit_required" }, requestId: "local-settings-update" };
  }
}
