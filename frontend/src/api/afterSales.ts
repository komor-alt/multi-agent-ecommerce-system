import { apiGet, apiPost, getApiBaseUrl } from "./client";
import type {
  ActionProposal,
  AfterSalesEvent,
  AfterSalesTicket,
  AnalyzeTicketResponse,
  ExecutionJob,
  StartRunResponse,
} from "../types/afterSales";

export function listAfterSalesTickets() {
  return apiGet<{ items: AfterSalesTicket[]; total: number }>("/after-sales/tickets");
}

export function createAfterSalesTicket(input: { orderId: string; customerMessage: string }) {
  return apiPost<AfterSalesTicket>("/after-sales/tickets", input);
}

export function getAfterSalesTicket(ticketId: string) {
  return apiGet<AfterSalesTicket>(`/after-sales/tickets/${encodeURIComponent(ticketId)}`);
}

export function analyzeAfterSalesTicket(ticketId: string, input: { deferred?: boolean } = {}) {
  return apiPost<AnalyzeTicketResponse>(`/after-sales/tickets/${encodeURIComponent(ticketId)}/analyze`, input);
}

export function startAfterSalesRun(runId: string) {
  return apiPost<StartRunResponse>(`/after-sales/runs/${encodeURIComponent(runId)}/start`, {});
}

/** 当前 Gateway 可信审批人（只读展示；身份由 Gateway 强制注入，客户端从不发送）。 */
export function getAfterSalesOperatorContext() {
  return apiGet<{ operatorId: string }>("/after-sales/operator-context");
}

/** 审批请求只携带审批意见 comment；审批人身份由 Gateway 可信 Header 注入，绝不从客户端发送。 */
export function approveAfterSalesProposal(
  proposalId: string,
  input: { comment?: string },
) {
  return apiPost<ExecutionJob>(`/after-sales/proposals/${encodeURIComponent(proposalId)}/approve`, input);
}

export function rejectAfterSalesProposal(
  proposalId: string,
  input: { comment?: string },
) {
  return apiPost<ActionProposal>(`/after-sales/proposals/${encodeURIComponent(proposalId)}/reject`, input);
}

export function retryAfterSalesExecution(jobId: string) {
  return apiPost<ExecutionJob>(`/after-sales/execution-jobs/${encodeURIComponent(jobId)}/retry`, {});
}

export function createAfterSalesEventSource(runId: string, lastEventId?: string) {
  const params = lastEventId ? `?lastEventId=${encodeURIComponent(lastEventId)}` : "";
  return new EventSource(
    `${getApiBaseUrl()}/after-sales/runs/${encodeURIComponent(runId)}/stream${params}`,
  );
}

export function parseAfterSalesEvent(event: MessageEvent<string>) {
  return JSON.parse(event.data) as AfterSalesEvent;
}
