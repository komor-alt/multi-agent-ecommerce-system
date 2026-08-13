function resolveApiBaseUrl() {
  const configured = import.meta.env.VITE_API_BASE_URL as string | undefined;
  if (configured) return configured;
  if (typeof window !== "undefined" && window.location.port === "8000") {
    return "http://localhost:3000/api/v1";
  }
  return "/api/v1";
}

const API_BASE_URL = resolveApiBaseUrl();

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
    throw new ApiClientError(
      payload?.error?.message || `HTTP ${response.status}`,
      payload?.error?.code || "REQUEST_FAILED",
      response.status,
      payload?.error?.details,
    );
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
    throw new ApiClientError(
      payload?.error?.message || `HTTP ${response.status}`,
      payload?.error?.code || "REQUEST_FAILED",
      response.status,
      payload?.error?.details,
    );
  }
  return payload?.data ?? payload;
}
