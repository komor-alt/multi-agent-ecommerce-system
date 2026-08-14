import { Body, Controller, Get, Headers, Param, Post, Query, Sse } from "@nestjs/common";
import { AfterSalesService } from "./after-sales.service";
import {
  AnalyzeAfterSalesTicketDto,
  CreateAfterSalesTicketDto,
  ReviewAfterSalesProposalDto,
} from "./dto/after-sales.dto";

@Controller("after-sales")
export class AfterSalesController {
  constructor(private readonly service: AfterSalesService) {}

  @Get("tickets")
  async listTickets() {
    return this.success(await this.service.listTickets(), "after-sales-list");
  }

  /** 当前 Gateway 可信审批人（只读，Demo 配置；生产由认证中间件生成，不构成生产认证）。 */
  @Get("operator-context")
  async getOperatorContext() {
    return this.success(await this.service.getOperatorContext(), "after-sales-operator-context");
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
  async approveProposal(
    @Param("proposalId") proposalId: string,
    @Body() dto: ReviewAfterSalesProposalDto,
  ) {
    return this.success(await this.service.approveProposal(proposalId, dto), proposalId);
  }

  @Post("proposals/:proposalId/reject")
  async rejectProposal(
    @Param("proposalId") proposalId: string,
    @Body() dto: ReviewAfterSalesProposalDto,
  ) {
    return this.success(await this.service.rejectProposal(proposalId, dto), proposalId);
  }

  @Post("execution-jobs/:jobId/retry")
  async retryExecution(@Param("jobId") jobId: string) {
    return this.success(await this.service.retryExecution(jobId), jobId);
  }

  private success(data: Record<string, unknown>, requestId: string) {
    return { success: true, data, requestId };
  }
}
