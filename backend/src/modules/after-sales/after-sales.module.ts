import { Module } from "@nestjs/common";
import { AfterSalesController } from "./after-sales.controller";
import { AfterSalesService } from "./after-sales.service";

@Module({
  controllers: [AfterSalesController],
  providers: [AfterSalesService],
  exports: [AfterSalesService],
})
export class AfterSalesModule {}
