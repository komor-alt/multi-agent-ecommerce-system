import { apiGet, apiPost, getApiBaseUrl } from "./client";
import type {
  ActionProposal,
  AfterSalesEvent,
  AfterSalesTicket,
  AnalyzeTicketResponse,
  ExecutionJob,
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

export function analyzeAfterSalesTicket(ticketId: string) {
  return apiPost<AnalyzeTicketResponse>(`/after-sales/tickets/${encodeURIComponent(ticketId)}/analyze`, {});
}

export function approveAfterSalesProposal(
  proposalId: string,
  input: { operatorId: string; comment?: string },
) {
  return apiPost<ExecutionJob>(`/after-sales/proposals/${encodeURIComponent(proposalId)}/approve`, input);
}

export function rejectAfterSalesProposal(
  proposalId: string,
  input: { operatorId: string; comment?: string },
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
