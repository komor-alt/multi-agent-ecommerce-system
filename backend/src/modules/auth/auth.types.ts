export type OperatorRole = "ADMIN" | "OPERATOR" | "VIEWER";

export interface AuthPrincipal {
  sub: string;
  username: string;
  roles: OperatorRole[];
  demo: boolean;
}

export interface OperatorAccount {
  subject: string;
  username: string;
  passwordHash: string;
  roles: OperatorRole[];
  disabled?: boolean;
}

export interface AuthenticatedRequest {
  method: string;
  headers: Record<string, string | string[] | undefined>;
  principal?: AuthPrincipal;
}
