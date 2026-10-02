import { ForbiddenException, Injectable, UnauthorizedException } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import { compare } from "bcryptjs";
import { createHash } from "node:crypto";
import { AuthSettings } from "./auth.settings";
import type { AuthPrincipal, OperatorAccount } from "./auth.types";

@Injectable()
export class AuthService {
  constructor(private readonly jwt: JwtService, readonly settings: AuthSettings) {}

  async login(username: string, password: string) {
    const account = this.settings.accounts.find((entry) => entry.username === username);
    // Perform a bcrypt comparison even for unknown accounts, and never disclose which field failed.
    const dummyHash = "$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxlYeQJVNJmA.5JEWPzgFtDc2Wm";
    const valid = Buffer.byteLength(password, "utf8") <= 72
      && await compare(password, account?.passwordHash || dummyHash);
    if (!account || account.disabled || !valid) throw new UnauthorizedException("Invalid username or password");
    const token = await this.jwt.signAsync({ username: account.username, credentialVersion: credentialVersion(account) }, {
      secret: this.settings.secret, algorithm: "HS256", subject: account.subject,
      issuer: this.settings.issuer, audience: this.settings.audience, expiresIn: this.settings.ttlSeconds,
    });
    return { token, principal: toPrincipal(account) };
  }

  async authenticate(token?: string): Promise<AuthPrincipal> {
    if (!token && this.settings.demo) {
      return { sub: "operator-vn-01", username: "development-demo", roles: ["ADMIN"], demo: true };
    }
    if (!token) throw new UnauthorizedException("Authentication required");
    try {
      const claims = await this.jwt.verifyAsync<Record<string, unknown>>(token, {
        secret: this.settings.secret, algorithms: ["HS256"], issuer: this.settings.issuer, audience: this.settings.audience,
      });
      const account = this.settings.accounts.find((entry) => entry.subject === claims.sub && !entry.disabled);
      if (!account || typeof claims.exp !== "number" || typeof claims.iat !== "number"
        || claims.username !== account.username || claims.credentialVersion !== credentialVersion(account)) {
        throw new Error("Invalid session");
      }
      return toPrincipal(account);
    } catch {
      throw new UnauthorizedException("Invalid or expired session");
    }
  }

  assertBrowserOrigin(origin: string | string[] | undefined) {
    if (typeof origin !== "string" || !this.settings.origins.includes(origin)) {
      throw new ForbiddenException("Request origin is not allowed");
    }
  }

  cookieOptions() {
    return { httpOnly: true, secure: this.settings.production, sameSite: "strict" as const,
      path: "/api/v1", maxAge: this.settings.ttlSeconds * 1000 };
  }
}

function toPrincipal(account: OperatorAccount): AuthPrincipal {
  return { sub: account.subject, username: account.username, roles: [...account.roles], demo: false };
}

function credentialVersion(account: OperatorAccount) {
  return createHash("sha256").update(account.passwordHash).digest("hex");
}
