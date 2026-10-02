import { CanActivate, ExecutionContext, ForbiddenException, Injectable, UnauthorizedException } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { AuthService } from "./auth.service";
import { PUBLIC_ROUTE, REQUIRED_ROLES } from "./auth.decorators";
import type { AuthenticatedRequest, OperatorRole } from "./auth.types";

@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly reflector: Reflector, private readonly auth: AuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    if (this.reflector.getAllAndOverride<boolean>(PUBLIC_ROUTE, [context.getHandler(), context.getClass()])) return true;
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const authorization = request.headers.authorization;
    const cookie = this.readCookie(request.headers.cookie);
    let token: string | undefined;
    if (authorization !== undefined) {
      if (typeof authorization !== "string" || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) {
        throw new UnauthorizedException("Invalid Authorization header");
      }
      token = authorization.slice(7);
    } else {
      token = cookie;
    }
    request.principal = await this.auth.authenticate(token);
    const unsafe = !["GET", "HEAD", "OPTIONS"].includes(request.method.toUpperCase());
    if (unsafe && cookie && authorization === undefined) this.auth.assertBrowserOrigin(request.headers.origin);
    const required = this.reflector.getAllAndOverride<OperatorRole[]>(REQUIRED_ROLES, [context.getHandler(), context.getClass()])
      || (unsafe ? ["ADMIN", "OPERATOR"] : []);
    if (required.length > 0 && !required.some((role) => request.principal!.roles.includes(role))) {
      throw new ForbiddenException("Insufficient role for this operation");
    }
    return true;
  }

  private readCookie(header: string | string[] | undefined): string | undefined {
    if (typeof header !== "string") return undefined;
    const values = header.split(";").map((part) => part.trim())
      .filter((part) => part.startsWith(`${this.auth.settings.cookieName}=`));
    if (values.length > 1) throw new UnauthorizedException("Ambiguous session cookie");
    return values[0]?.slice(this.auth.settings.cookieName.length + 1);
  }
}
