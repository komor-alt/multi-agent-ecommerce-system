import { BadGatewayException, Injectable, MessageEvent } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { Observable } from "rxjs";
import { CreateAfterSalesTicketDto, ReviewAfterSalesProposalDto } from "./dto/after-sales.dto";

type SseFrame = {
  event: string;
  id?: string;
  data: Record<string, unknown>;
};

@Injectable()
export class AfterSalesService {
  constructor(private readonly config: ConfigService) {}

  listTickets() {
    return this.request<Record<string, unknown>>("/tickets");
  }

  createTicket(dto: CreateAfterSalesTicketDto) {
    return this.request<Record<string, unknown>>("/tickets", {
      method: "POST",
      body: JSON.stringify(dto),
    });
  }

  getTicket(ticketId: string) {
    return this.request<Record<string, unknown>>(`/tickets/${encodeURIComponent(ticketId)}`);
  }

  analyzeTicket(ticketId: string, dto?: { deferred?: boolean }) {
    const suffix = dto?.deferred ? "?deferred=true" : "";
    return this.request<Record<string, unknown>>(
      `/tickets/${encodeURIComponent(ticketId)}/analyze${suffix}`,
      { method: "POST", body: "{}" },
    );
  }

  startRun(runId: string) {
    return this.request<Record<string, unknown>>(`/runs/${encodeURIComponent(runId)}/start`, {
      method: "POST",
      body: "{}",
    });
  }

  approveProposal(proposalId: string, dto: ReviewAfterSalesProposalDto) {
    return this.request<Record<string, unknown>>(`/proposals/${encodeURIComponent(proposalId)}/approve`, {
      method: "POST",
      body: JSON.stringify(dto),
    });
  }

  rejectProposal(proposalId: string, dto: ReviewAfterSalesProposalDto) {
    return this.request<Record<string, unknown>>(`/proposals/${encodeURIComponent(proposalId)}/reject`, {
      method: "POST",
      body: JSON.stringify(dto),
    });
  }

  retryExecution(jobId: string) {
    return this.request<Record<string, unknown>>(`/execution-jobs/${encodeURIComponent(jobId)}/retry`, {
      method: "POST",
      body: "{}",
    });
  }

  streamRun(runId: string, lastEventId?: string): Observable<MessageEvent> {
    return new Observable<MessageEvent>((subscriber) => {
      const abortController = new AbortController();
      void this.consumeSse(runId, lastEventId, abortController.signal, subscriber)
        .catch((error) => subscriber.error(error));
      return () => abortController.abort();
    });
  }

  private async consumeSse(
    runId: string,
    lastEventId: string | undefined,
    signal: AbortSignal,
    subscriber: { next(value: MessageEvent): void; complete(): void },
  ) {
    const headers: Record<string, string> = { Accept: "text/event-stream" };
    if (lastEventId) headers["Last-Event-ID"] = lastEventId;

    const response = await fetch(
      `${this.baseUrl()}/runs/${encodeURIComponent(runId)}/stream`,
      { headers, signal },
    );
    if (!response.ok || !response.body) {
      throw new BadGatewayException(`Java after-sales stream failed: HTTP ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split("\n\n");
      buffer = chunks.pop() || "";
      for (const chunk of chunks) {
        const frame = this.parseFrame(chunk);
        if (frame) {
          subscriber.next({
            id: frame.id || String(frame.data.eventId || ""),
            type: frame.event,
            data: frame.data,
          });
        }
      }
    }
    subscriber.complete();
  }

  private parseFrame(chunk: string): SseFrame | null {
    let event = "message";
    let id: string | undefined;
    const dataLines: string[] = [];
    for (const line of chunk.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      if (line.startsWith("id:")) id = line.slice(3).trim();
      if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
    }
    if (!dataLines.length) return null;
    return {
      event,
      id,
      data: JSON.parse(dataLines.join("\n")) as Record<string, unknown>,
    };
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${this.baseUrl()}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        ...init?.headers,
      },
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      throw new BadGatewayException(
        typeof payload?.message === "string"
          ? payload.message
          : `Java after-sales service failed: HTTP ${response.status}`,
      );
    }
    return payload as T;
  }

  private baseUrl() {
    const root = this.config.get<string>("JAVA_AGENT_SERVICE_BASE_URL") || "http://localhost:8080";
    return `${root.replace(/\/$/, "")}/api/v1/after-sales`;
  }
}
