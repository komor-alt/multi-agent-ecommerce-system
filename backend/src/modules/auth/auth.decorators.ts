import { createParamDecorator, ExecutionContext, SetMetadata } from "@nestjs/common";
import type { AuthenticatedRequest, OperatorRole } from "./auth.types";

export const PUBLIC_ROUTE = "auth.public";
export const REQUIRED_ROLES = "auth.roles";
export const Public = () => SetMetadata(PUBLIC_ROUTE, true);
export const Roles = (...roles: OperatorRole[]) => SetMetadata(REQUIRED_ROLES, roles);
export const CurrentPrincipal = createParamDecorator((_data: unknown, context: ExecutionContext) =>
  context.switchToHttp().getRequest<AuthenticatedRequest>().principal);
