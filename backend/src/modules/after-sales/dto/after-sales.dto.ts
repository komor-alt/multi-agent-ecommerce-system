import { IsOptional, IsString, MinLength } from "class-validator";

export class CreateAfterSalesTicketDto {
  @IsString()
  @MinLength(3)
  orderId!: string;

  @IsString()
  @MinLength(5)
  customerMessage!: string;
}

export class ReviewAfterSalesProposalDto {
  @IsString()
  @MinLength(2)
  operatorId!: string;

  @IsOptional()
  @IsString()
  comment?: string;
}
