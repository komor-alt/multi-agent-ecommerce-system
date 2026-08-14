import {
  BadGatewayException,
  HttpException,
  Injectable,
  InternalServerErrorException,
  MessageEvent,
} from "@nestjs/common";
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
  /** 与 Java 侧 OperatorContext 校验一致：^[A-Za-z0-9._-]{2,64}$。 */
  private static readonly OPERATOR_ID_PATTERN = /^[A-Za-z0-9._-]{2,64}$/;
  private static readonly OPERATOR_HEADER_NAME = "X-Authenticated-Operator";

  constructor(private readonly config: ConfigService) {}

  /**
   * 当前 Gateway 可信审批人（只读，供前端展示「当前审批人」）。
   * 注意：这是 Demo 的可信 Gateway Header 方案，不构成生产认证 —— 生产环境必须由认证中间件
   * 从登录会话/Token 生成身份，清洗验证后强制覆盖该 Header，本配置只用于本地演示。
   */
  getOperatorContext(): { operatorId: string } {
    return { operatorId: this.trustedOperatorId() };
  }

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
      headers: this.operatorIdentityHeaders(),
      // 重建 body 而非透传 dto：即使客户端附带 operatorId（ValidationPipe whitelist 已剥离），
      // 转发 payload 也不可能携带任何身份字段。
      body: JSON.stringify({ comment: dto.comment }),
    });
  }

  rejectProposal(proposalId: string, dto: ReviewAfterSalesProposalDto) {
    return this.request<Record<string, unknown>>(`/proposals/${encodeURIComponent(proposalId)}/reject`, {
      method: "POST",
      headers: this.operatorIdentityHeaders(),
      body: JSON.stringify({ comment: dto.comment }),
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

  /**
   * 强制设置可信审批人 Header（客户端传入的同名 Header/body 字段绝不透传——本方法总是重建请求头，
   * 不使用客户端任何输入）。Demo 默认 operator-vn-01，可用环境变量 AFTER_SALES_OPERATOR_ID 覆盖；
   * 生产环境必须由认证中间件生成并清洗覆盖，此配置不构成生产认证。
   */
  private operatorIdentityHeaders(): Record<string, string> {
    return { [AfterSalesService.OPERATOR_HEADER_NAME]: this.trustedOperatorId() };
  }

  private trustedOperatorId(): string {
    const raw = this.config.get<string>("AFTER_SALES_OPERATOR_ID") || "operator-vn-01";
    const id = raw.trim();
    if (!AfterSalesService.OPERATOR_ID_PATTERN.test(id)) {
      throw new InternalServerErrorException(
        `AFTER_SALES_OPERATOR_ID "${raw}" is not a valid operator id (^[A-Za-z0-9._-]{2,64}$)`,
      );
    }
    return id;
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
      const structured = this.structuredJavaError(payload, response.status);
      if (structured) throw structured;
      throw new BadGatewayException(
        typeof payload?.message === "string"
          ? payload.message
          : `Java after-sales service failed: HTTP ${response.status}`,
      );
    }
    return payload as T;
  }

  /**
   * 透传 Java 侧结构化安全错误（如审批策略违例 409、审批人身份 400/401，形如
   * {code, message, messageZh}），保留原始 4xx 状态。只提取白名单字段（code/message/messageZh
   * 且均为字符串），绝不透传上游错误体中的任意内容；非结构化或 5xx 上游失败保持 502。
   */
  private structuredJavaError(payload: unknown, status: number): HttpException | null {
    if (status < 400 || status >= 500) return null;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
    const body = payload as Record<string, unknown>;
    if (
      typeof body.code !== "string" ||
      typeof body.message !== "string" ||
      typeof body.messageZh !== "string"
    ) {
      return null;
    }
    return new HttpException(
      { code: body.code, message: body.message, messageZh: body.messageZh },
      status,
    );
  }

  private baseUrl() {
    const root = this.config.get<string>("JAVA_AGENT_SERVICE_BASE_URL") || "http://localhost:8080";
    return `${root.replace(/\/$/, "")}/api/v1/after-sales`;
  }
}
