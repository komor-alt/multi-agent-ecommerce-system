import { IsBoolean, IsOptional, IsString, MinLength } from "class-validator";

export class CreateAfterSalesTicketDto {
  @IsString()
  @MinLength(3)
  orderId!: string;

  @IsString()
  @MinLength(5)
  customerMessage!: string;
}

export class AnalyzeAfterSalesTicketDto {
  @IsOptional()
  @IsBoolean()
  deferred?: boolean;
}

/**
 * 审批请求 DTO：只保留审批意见 comment。
 * 审批人身份绝不来自客户端 body —— 由 Gateway 从已验证 JWT 的 subject 读取，
 * 清洗验证后强制写入 X-Authenticated-Operator 头转发给 Java（客户端同名字段一律不采用）。
 */
export class ReviewAfterSalesProposalDto {
  @IsOptional()
  @IsString()
  comment?: string;
}
