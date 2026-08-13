import { Injectable, MessageEvent } from "@nestjs/common";
import { Subject } from "rxjs";
import type { AgentServiceRawEvent } from "../../infrastructure/agent-client/agent-service.contract";

@Injectable()
export class AgentRunEventBus {
  private readonly streams = new Map<string, Subject<MessageEvent>>();

  stream(runId: string) {
    let subject = this.streams.get(runId);
    if (!subject) {
      subject = new Subject<MessageEvent>();
      this.streams.set(runId, subject);
    }
    return subject.asObservable();
  }

  publish(event: AgentServiceRawEvent) {
    this.subject(event.run_id).next({ id: event.event_id, type: event.type, data: this.toSsePayload(event) });
  }

  heartbeat(runId: string) {
    this.subject(runId).next({ data: { type: "heartbeat", runId, timestamp: new Date().toISOString() } });
  }

  complete(runId: string) {
    const subject = this.streams.get(runId);
    if (subject) {
      subject.complete();
      this.streams.delete(runId);
    }
  }

  error(runId: string, error: unknown) {
    const subject = this.streams.get(runId);
    if (subject) {
      subject.error(error);
      this.streams.delete(runId);
    }
  }

  private subject(runId: string) {
    let subject = this.streams.get(runId);
    if (!subject) {
      subject = new Subject<MessageEvent>();
      this.streams.set(runId, subject);
    }
    return subject;
  }

  private toSsePayload(event: AgentServiceRawEvent) {
    return {
      eventId: event.event_id,
      runId: event.run_id,
      sequence: event.sequence,
      type: event.type,
      name: event.name,
      status: event.status,
      timestamp: event.timestamp,
      data: event.data,
      metrics: {
        latencyMs: event.metrics?.latency_ms || 0,
        inputTokens: event.metrics?.input_tokens || 0,
        outputTokens: event.metrics?.output_tokens || 0,
        totalTokens: event.metrics?.total_tokens || 0,
      },
    };
  }
}
