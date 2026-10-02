import { Body, Controller, Get, Headers, Param, Post, Query, Sse } from "@nestjs/common";
import { AfterSalesService } from "./after-sales.service";
import {
  AnalyzeAfterSalesTicketDto,
  CreateAfterSalesTicketDto,
  ReviewAfterSalesProposalDto,
} from "./dto/after-sales.dto";
import { CurrentPrincipal, Roles } from "../auth/auth.decorators";
import type { AuthPrincipal } from "../auth/auth.types";

@Controller("after-sales")
export class AfterSalesController {
  constructor(private readonly service: AfterSalesService) {}

  @Get("tickets")
  async listTickets() {
    return this.success(await this.service.listTickets(), "after-sales-list");
  }

  /** Identity comes from the authenticated session, never from browser-supplied IDs. */
  @Get("operator-context")
  async getOperatorContext(@CurrentPrincipal() principal: AuthPrincipal) {
    return this.success(this.service.getOperatorContext(principal), "after-sales-operator-context");
  }

  @Post("tickets")
  async createTicket(@Body() dto: CreateAfterSalesTicketDto) {
    const data = await this.service.createTicket(dto);
    return this.success(data, String(data.id || "after-sales-create"));
  }

  @Get("tickets/:ticketId")
  async getTicket(@Param("ticketId") ticketId: string) {
    return this.success(await this.service.getTicket(ticketId), ticketId);
  }

  @Post("tickets/:ticketId/analyze")
  async analyzeTicket(@Param("ticketId") ticketId: string, @Body() dto: AnalyzeAfterSalesTicketDto) {
    const data = await this.service.analyzeTicket(ticketId, dto);
    return this.success(data, String(data.runId || ticketId));
  }

  @Post("runs/:runId/start")
  async startRun(@Param("runId") runId: string) {
    const data = await this.service.startRun(runId);
    return this.success(data, runId);
  }

  @Sse("runs/:runId/stream")
  streamRun(
    @Param("runId") runId: string,
    @Headers("last-event-id") headerLastEventId?: string,
    @Query("lastEventId") queryLastEventId?: string,
  ) {
    return this.service.streamRun(runId, headerLastEventId || queryLastEventId);
  }

  @Post("proposals/:proposalId/approve")
  @Roles("ADMIN")
  async approveProposal(
    @Param("proposalId") proposalId: string,
    @Body() dto: ReviewAfterSalesProposalDto,
    @CurrentPrincipal() principal: AuthPrincipal,
  ) {
    return this.success(await this.service.approveProposal(proposalId, dto, principal), proposalId);
  }

  @Post("proposals/:proposalId/reject")
  @Roles("ADMIN")
  async rejectProposal(
    @Param("proposalId") proposalId: string,
    @Body() dto: ReviewAfterSalesProposalDto,
    @CurrentPrincipal() principal: AuthPrincipal,
  ) {
    return this.success(await this.service.rejectProposal(proposalId, dto, principal), proposalId);
  }

  @Post("execution-jobs/:jobId/retry")
  @Roles("ADMIN")
  async retryExecution(@Param("jobId") jobId: string) {
    return this.success(await this.service.retryExecution(jobId), jobId);
  }

  private success(data: Record<string, unknown>, requestId: string) {
    return { success: true, data, requestId };
  }
}
