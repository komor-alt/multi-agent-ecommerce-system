import "reflect-metadata";
import { ValidationPipe } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { NestFactory } from "@nestjs/core";
import { AppModule } from "./app.module";
import { AuthService } from "./modules/auth/auth.service";

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  app.setGlobalPrefix("api/v1");
  const auth = app.get(AuthService);
  app.enableCors({ origin: auth.settings.origins, credentials: true });
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));

  const config = app.get(ConfigService);
  // Set only to the trusted ingress CIDRs/hops; never trust arbitrary forwarded headers by default.
  const trustedProxies = config.get<string>("HTTP_TRUST_PROXY");
  if (trustedProxies) app.getHttpAdapter().getInstance().set("trust proxy", trustedProxies.split(",").map((value: string) => value.trim()));
  const port = config.get<number>("PORT") || 3000;
  await app.listen(port);
}

void bootstrap();
