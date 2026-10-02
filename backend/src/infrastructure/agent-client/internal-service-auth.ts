import { ConfigService } from "@nestjs/config";

/** Only server-owned credentials are forwarded. Browser headers are never copied. */
export function internalServiceHeaders(config: ConfigService): Record<string, string> {
  const token = config.get<string>("JAVA_INTERNAL_SERVICE_TOKEN");
  if (config.get<string>("NODE_ENV") === "production" && (!token || Buffer.byteLength(token) < 32)) {
    throw new Error("JAVA_INTERNAL_SERVICE_TOKEN must contain at least 32 bytes in production");
  }
  return token ? { "X-Internal-Service-Token": token } : {};
}
