import { Injectable } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import type { OperatorAccount } from "./auth.types";

@Injectable()
export class AuthSettings {
  readonly production: boolean;
  readonly demo: boolean;
  readonly secret: string;
  readonly issuer: string;
  readonly audience: string;
  readonly ttlSeconds: number;
  readonly origins: string[];
  readonly accounts: OperatorAccount[];
  readonly cookieName = "ecom_session";

  constructor(config: ConfigService) {
    this.production = config.get<string>("NODE_ENV") === "production";
    if (this.production && Buffer.byteLength(config.get<string>("JAVA_INTERNAL_SERVICE_TOKEN") || "") < 32) {
      throw new Error("JAVA_INTERNAL_SERVICE_TOKEN must contain at least 32 bytes in production");
    }
    const mode = config.get<string>("AUTH_MODE") || (this.production ? "required" : "demo");
    if (!["demo", "required"].includes(mode) || (this.production && mode !== "required")) {
      throw new Error("AUTH_MODE must be required in production; demo is only available in development");
    }
    this.demo = mode === "demo";
    const configuredSecret = config.get<string>("AUTH_JWT_SECRET") || "";
    if (!this.demo && Buffer.byteLength(configuredSecret) < 32) {
      throw new Error("AUTH_JWT_SECRET must contain at least 32 bytes in required mode");
    }
    this.secret = configuredSecret || randomBytes(48).toString("base64url");
    this.issuer = config.get<string>("AUTH_JWT_ISSUER") || "ecom-operations-gateway";
    this.audience = config.get<string>("AUTH_JWT_AUDIENCE") || "ecom-operations";
    this.ttlSeconds = Number(config.get("AUTH_SESSION_TTL_SECONDS") || 900);
    if (!Number.isInteger(this.ttlSeconds) || this.ttlSeconds < 60 || this.ttlSeconds > 3600) {
      throw new Error("AUTH_SESSION_TTL_SECONDS must be between 60 and 3600");
    }
    this.origins = (config.get<string>("AUTH_ALLOWED_ORIGINS") || (this.production ? "" : "http://localhost:5173,http://localhost:8000,http://localhost:3000"))
      .split(",").map((value) => value.trim()).filter(Boolean);
    if (!this.demo && this.origins.length === 0) {
      throw new Error("AUTH_ALLOWED_ORIGINS must list the exact browser origins");
    }
    for (const origin of this.origins) {
      const url = new URL(origin);
      if (url.origin !== origin || (this.production && url.protocol !== "https:")) {
        throw new Error("AUTH_ALLOWED_ORIGINS must contain exact origins (HTTPS in production)");
      }
    }
    const accountFile = config.get<string>("AUTH_ACCOUNTS_FILE");
    const rawAccounts = accountFile ? readFileSync(accountFile, "utf8") : config.get<string>("AUTH_ACCOUNTS_JSON") || "[]";
    let parsed: unknown;
    try { parsed = JSON.parse(rawAccounts); } catch { throw new Error("AUTH_ACCOUNTS must be valid JSON"); }
    if (!Array.isArray(parsed)) throw new Error("AUTH_ACCOUNTS must be a JSON array");
    const subjects = new Set<string>();
    const usernames = new Set<string>();
    this.accounts = parsed.map((value: unknown) => {
      if (!value || typeof value !== "object") throw new Error("Invalid operator account");
      const account = value as OperatorAccount;
      if (typeof account.subject !== "string" || !/^[A-Za-z0-9._-]{2,64}$/.test(account.subject)
        || typeof account.username !== "string" || !account.username.trim() || account.username.length > 128
        || typeof account.passwordHash !== "string" || !/^\$2[aby]\$(1[0-6])\$[./A-Za-z0-9]{53}$/.test(account.passwordHash)
        || !Array.isArray(account.roles) || account.roles.length === 0
        || account.roles.some((role) => !["ADMIN", "OPERATOR", "VIEWER"].includes(role))
        || (account.disabled !== undefined && typeof account.disabled !== "boolean")
        || subjects.has(account.subject) || usernames.has(account.username)) {
        throw new Error("Invalid or duplicate operator account; bcrypt cost must be 10-16");
      }
      subjects.add(account.subject);
      usernames.add(account.username);
      return account;
    });
    if (!this.demo && !this.accounts.some((account) => !account.disabled && account.roles.includes("ADMIN"))) {
      throw new Error("Required auth needs at least one enabled ADMIN account; no default passwords are provided");
    }
  }
}
