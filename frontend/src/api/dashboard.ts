import type { DashboardOverview } from "../types/contracts";
import { apiGet } from "./client";

export function getDashboardOverview() {
  return apiGet<DashboardOverview>("/dashboard/overview");
}
