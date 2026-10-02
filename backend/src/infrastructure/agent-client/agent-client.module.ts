import { Module } from "@nestjs/common";
import { AgentClientService } from "./agent-client.service";
import { JavaBrowserProxyController } from "./java-browser-proxy.controller";

@Module({
  controllers: [JavaBrowserProxyController],
  providers: [AgentClientService],
  exports: [AgentClientService],
})
export class AgentClientModule {}
