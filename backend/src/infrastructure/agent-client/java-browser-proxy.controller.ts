import { BadGatewayException, Body, Controller, Get, Post, Res } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type { Response } from "express";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import type { ReadableStream } from "node:stream/web";
import { internalServiceHeaders } from "./internal-service-auth";

/** Explicit browser routes only: no arbitrary URL proxy and no browser access to service credentials. */
@Controller()
export class JavaBrowserProxyController {
  constructor(private readonly config: ConfigService) {}

  @Get("data/catalog")
  catalog() { return this.json("/api/v1/data/catalog"); }

  @Get("data/catalog/summary")
  catalogSummary() { return this.json("/api/v1/data/catalog/summary"); }

  @Post("recommend/stream")
  async stream(@Body() body: Record<string, unknown>, @Res() response: Response) {
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), this.timeoutMs());
    const onClose = () => abort.abort();
    response.on("close", onClose);
    try {
      const upstream = await fetch(`${this.baseUrl()}/api/v1/recommend/stream`, {
        method: "POST", headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...internalServiceHeaders(this.config) },
        body: JSON.stringify(body), signal: abort.signal,
      });
      if (!upstream.ok || !upstream.body) throw new BadGatewayException("Recommendation service unavailable");
      response.setHeader("Content-Type", "text/event-stream");
      response.setHeader("Cache-Control", "no-cache, no-transform");
      response.setHeader("X-Accel-Buffering", "no");
      await pipeline(Readable.fromWeb(upstream.body as ReadableStream<Uint8Array>), response);
    } finally {
      clearTimeout(timeout);
      response.off("close", onClose);
    }
  }

  private async json(path: string) {
    const response = await fetch(`${this.baseUrl()}${path}`, {
      headers: { Accept: "application/json", ...internalServiceHeaders(this.config) },
      signal: AbortSignal.timeout(this.timeoutMs()),
    });
    if (!response.ok) throw new BadGatewayException("Catalog service unavailable");
    return response.json();
  }

  private baseUrl() { return (this.config.get<string>("JAVA_AGENT_SERVICE_BASE_URL") || "http://localhost:8080").replace(/\/$/, ""); }
  private timeoutMs() { return Number(this.config.get("JAVA_BROWSER_PROXY_TIMEOUT_MS") || 120000); }
}
