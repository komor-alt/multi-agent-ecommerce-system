import { Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { DatabaseModule } from "./infrastructure/database/database.module";
import { AgentClientModule } from "./infrastructure/agent-client/agent-client.module";
import { HealthModule } from "./modules/health/health.module";
import { DashboardModule } from "./modules/dashboard/dashboard.module";
import { AuthModule } from "./modules/auth/auth.module";
import { UsersModule } from "./modules/users/users.module";
import { ProductsModule } from "./modules/products/products.module";
import { OrdersModule } from "./modules/orders/orders.module";
import { RecommendationsModule } from "./modules/recommendations/recommendations.module";
import { AgentRunsModule } from "./modules/agent-runs/agent-runs.module";
import { EvaluationsModule } from "./modules/evaluations/evaluations.module";
import { SettingsModule } from "./modules/settings/settings.module";

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    DatabaseModule,
    AgentClientModule,
    HealthModule,
    DashboardModule,
    AuthModule,
    UsersModule,
    ProductsModule,
    OrdersModule,
    RecommendationsModule,
    AgentRunsModule,
    EvaluationsModule,
    SettingsModule,
  ],
})
export class AppModule {}
