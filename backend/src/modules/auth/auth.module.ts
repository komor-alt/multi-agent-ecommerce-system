import { Module } from "@nestjs/common";
import { AuthController } from "./auth.controller";
import { AuthService } from "./auth.service";
import { APP_GUARD } from "@nestjs/core";
import { JwtModule } from "@nestjs/jwt";
import { ThrottlerModule } from "@nestjs/throttler";
import { ConfigService } from "@nestjs/config";
import { AuthSettings } from "./auth.settings";
import { AuthGuard } from "./auth.guard";

@Module({
  imports: [JwtModule.register({}), ThrottlerModule.forRootAsync({
    inject: [ConfigService],
    useFactory: (config: ConfigService) => {
      const ttl = Number(config.get("AUTH_LOGIN_WINDOW_MS") || 60000);
      const limit = Number(config.get("AUTH_LOGIN_MAX_ATTEMPTS") || 10);
      if (!Number.isSafeInteger(ttl) || ttl < 1000 || !Number.isSafeInteger(limit) || limit < 1) {
        throw new Error("Login rate limits must be positive integers; AUTH_LOGIN_WINDOW_MS must be >= 1000");
      }
      return [{ ttl, limit }];
    },
  })],
  controllers: [AuthController],
  providers: [AuthService, AuthSettings, { provide: APP_GUARD, useClass: AuthGuard }],
  exports: [AuthService],
})
export class AuthModule {}
