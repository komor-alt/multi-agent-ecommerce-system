import { apiGet } from "./client";

export type HealthResponse = {
  status: "ok" | "degraded";
  service: string;
  timestamp: string;
};

export function getGatewayHealth() {
  return apiGet<HealthResponse>("/health");
}
