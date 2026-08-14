function resolveApiBaseUrl() {
  const configured = import.meta.env.VITE_API_BASE_URL as string | undefined;
  if (configured) return configured;
  if (typeof window !== "undefined" && window.location.port === "8000") {
    return "http://localhost:3000/api/v1";
  }
  return "/api/v1";
}

const API_BASE_URL = resolveApiBaseUrl();

export function getApiBaseUrl() {
  return API_BASE_URL;
}

export class ApiClientError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status: number,
    public readonly details?: Record<string, unknown>,
  ) {
    super(message);
  }
}

type ApiErrorPayload = {
  code?: string;
  message?: string;
  details?: Record<string, unknown>;
};

/**
 * 从错误响应体中提取 code 与可读 message。兼容两种形态：
 * 1. 既有信封 { success: false, error: { code, message, details } }；
 * 2. Nest 透传 Java 结构化错误 { code, message, messageZh }（message 优先取中文 messageZh）。
 * 其余形态返回 null，调用方回退到 HTTP 状态文本。
 */
function extractApiError(payload: unknown): ApiErrorPayload | null {
  if (!payload || typeof payload !== "object") return null;
  const body = payload as Record<string, unknown>;
  if (body.error && typeof body.error === "object") {
    const envelope = body.error as Record<string, unknown>;
    if (typeof envelope.code === "string" || typeof envelope.message === "string") {
      return {
        code: typeof envelope.code === "string" ? envelope.code : undefined,
        message: typeof envelope.message === "string" ? envelope.message : undefined,
        details:
          envelope.details && typeof envelope.details === "object"
            ? (envelope.details as Record<string, unknown>)
            : undefined,
      };
    }
  }
  if (typeof body.code === "string") {
    return {
      code: body.code,
      message:
        typeof body.messageZh === "string"
          ? body.messageZh
          : typeof body.message === "string"
            ? body.message
            : undefined,
    };
  }
  return null;
}

function throwApiError(payload: unknown, status: number): never {
  const error = extractApiError(payload);
  throw new ApiClientError(
    error?.message || `HTTP ${status}`,
    error?.code || "REQUEST_FAILED",
    status,
    error?.details,
  );
}

export async function apiGet<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...init?.headers,
    },
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.success === false) {
    throwApiError(payload, response.status);
  }
  return payload?.data ?? payload;
}

export async function apiPost<T>(path: string, body: unknown, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: "POST",
    ...init,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...init?.headers,
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.success === false) {
    throwApiError(payload, response.status);
  }
  return payload?.data ?? payload;
}
