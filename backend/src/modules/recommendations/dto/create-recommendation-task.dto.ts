import { Type } from "class-transformer";
import { IsInt, IsObject, IsOptional, IsString, Max, Min } from "class-validator";

class RecommendationAgentConfigDto {
  @IsOptional()
  @IsString()
  model?: string;

  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(32)
  maxSteps?: number;

  @IsOptional()
  temperature?: number;

  @IsOptional()
  @IsInt()
  @Min(1)
  maxTokens?: number;

  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(20)
  ragTopK?: number;

  @IsOptional()
  toolWhitelist?: string[];
}

export class CreateRecommendationTaskDto {
  @IsString()
  userId!: string;

  @IsString()
  scene!: "homepage" | "campaign" | "retention" | string;

  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(20)
  numItems!: number;

  @IsObject()
  context!: Record<string, unknown>;

  @IsOptional()
  @IsObject()
  agentConfig?: RecommendationAgentConfigDto;
}

export type RecommendationTaskDto = {
  id: string;
  runId?: string;
  taskType: "product_recommendation";
  userId: string;
  scene: string;
  status: string;
  input: Record<string, unknown>;
  finalAnswer?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
};
