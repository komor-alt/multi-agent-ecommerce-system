import { Body, Controller, Get, Header, Headers, Post, Res, UseGuards } from "@nestjs/common";
import { IsString, MaxLength, MinLength } from "class-validator";
import { ThrottlerGuard } from "@nestjs/throttler";
import type { Response } from "express";
import { AuthService } from "./auth.service";
import { CurrentPrincipal, Public, Roles } from "./auth.decorators";
import type { AuthPrincipal } from "./auth.types";

export class LoginDto {
  @IsString() @MinLength(1) @MaxLength(128) username!: string;
  @IsString() @MinLength(1) @MaxLength(72) password!: string;
}

@Controller("auth")
export class AuthController {
  constructor(private readonly service: AuthService) {}

  @Get("me")
  @Header("Cache-Control", "no-store")
  me(@CurrentPrincipal() principal: AuthPrincipal) {
    return { success: true, data: principal };
  }

  @Public()
  @UseGuards(ThrottlerGuard)
  @Post("login")
  async login(@Body() dto: LoginDto, @Headers("origin") origin: string | undefined, @Res({ passthrough: true }) response: Response) {
    this.service.assertBrowserOrigin(origin);
    const { token, principal } = await this.service.login(dto.username, dto.password);
    response.cookie(this.service.settings.cookieName, token, this.service.cookieOptions());
    response.setHeader("Cache-Control", "no-store");
    return { success: true, data: principal };
  }

  @Roles("ADMIN", "OPERATOR", "VIEWER")
  @Post("logout")
  @Header("Cache-Control", "no-store")
  logout(@Res({ passthrough: true }) response: Response) {
    const { maxAge: _maxAge, ...options } = this.service.cookieOptions();
    response.clearCookie(this.service.settings.cookieName, options);
    return { success: true, data: { loggedOut: true } };
  }
}
