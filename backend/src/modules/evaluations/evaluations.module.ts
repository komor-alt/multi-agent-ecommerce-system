import { Module } from "@nestjs/common";
import { EvaluationsController } from "./evaluations.controller";
import { EvaluationsService } from "./evaluations.service";
import { EvaluationsRepository } from "./evaluations.repository";

@Module({
  controllers: [EvaluationsController],
  providers: [EvaluationsService, EvaluationsRepository],
  exports: [EvaluationsService],
})
export class EvaluationsModule {}
