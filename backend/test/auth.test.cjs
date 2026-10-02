const { before, after, test } = require("node:test");
const assert = require("node:assert/strict");
require("reflect-metadata");
const { Test } = require("@nestjs/testing");
const { ValidationPipe } = require("@nestjs/common");
const { APP_GUARD } = require("@nestjs/core");
const { ConfigService } = require("@nestjs/config");
const { JwtModule, JwtService } = require("@nestjs/jwt");
const { ThrottlerModule } = require("@nestjs/throttler");
const { hash } = require("bcryptjs");
const { AuthService } = require("../dist/modules/auth/auth.service");
const { AuthSettings } = require("../dist/modules/auth/auth.settings");
const { AuthGuard } = require("../dist/modules/auth/auth.guard");
const { AuthController } = require("../dist/modules/auth/auth.controller");
const { AfterSalesController } = require("../dist/modules/after-sales/after-sales.controller");
const { AfterSalesService } = require("../dist/modules/after-sales/after-sales.service");
const { HealthController } = require("../dist/modules/health/health.controller");

let app, url, configValues, javaRequest;
const origin = "http://localhost:5173";
const realFetch = global.fetch;
const cookies = {};

before(async () => {
  const passwordHash = await hash("test-password-long-enough", 10);
  configValues = {
    NODE_ENV: "test", AUTH_MODE: "required", AUTH_JWT_SECRET: "test-jwt-signing-secret-more-than-32-bytes",
    AUTH_ALLOWED_ORIGINS: origin, JAVA_AGENT_SERVICE_BASE_URL: "http://java-service.local",
    JAVA_INTERNAL_SERVICE_TOKEN: "internal-credential-more-than-32-bytes",
    AUTH_ACCOUNTS_JSON: JSON.stringify(["ADMIN", "OPERATOR", "VIEWER"].map((role) => ({
      subject: `trusted-${role.toLowerCase()}`, username: role.toLowerCase(), passwordHash, roles: [role],
    }))),
  };
  const config = new ConfigService(configValues);
  const module = await Test.createTestingModule({
    imports: [JwtModule.register({}), ThrottlerModule.forRoot([{ ttl: 60000, limit: 50 }])],
    controllers: [AuthController, AfterSalesController, HealthController],
    providers: [AuthService, AuthSettings, AfterSalesService, { provide: ConfigService, useValue: config },
      { provide: APP_GUARD, useClass: AuthGuard }],
  }).compile();
  app = module.createNestApplication();
  app.setGlobalPrefix("api/v1");
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  await app.listen(0, "127.0.0.1");
  url = await app.getUrl();
  global.fetch = async (input, init) => {
    if (String(input).startsWith("http://java-service.local/")) {
      javaRequest = { input, init };
      if (String(input).endsWith("/stream")) {
        return new Response('id: event-1\nevent: run.completed\ndata: {"status":"COMPLETED"}\n\n', {
          headers: { "Content-Type": "text/event-stream" },
        });
      }
      return new Response(JSON.stringify({ ok: true }), { headers: { "Content-Type": "application/json" } });
    }
    return realFetch(input, init);
  };
  for (const role of ["admin", "operator", "viewer"]) {
    const response = await login(role);
    assert.equal(response.status, 201);
    cookies[role] = response.headers.get("set-cookie").split(";")[0];
  }
});

after(async () => { global.fetch = realFetch; await app?.close(); });

function request(path, init) { return fetch(`${url}/api/v1${path}`, init); }
function login(username, password = "test-password-long-enough", customOrigin = origin) {
  return request("/auth/login", { method: "POST", headers: { "Content-Type": "application/json", Origin: customOrigin },
    body: JSON.stringify({ username, password }) });
}

test("health stays public; missing and tampered sessions are rejected", async () => {
  assert.equal((await request("/health")).status, 200);
  assert.equal((await request("/after-sales/tickets")).status, 401);
  assert.equal((await request("/auth/me", { headers: { Cookie: "ecom_session=forged.jwt.signature" } })).status, 401);
  assert.equal((await request("/auth/me", { headers: { Authorization: "Basic admin:password" } })).status, 401);
});

test("login verifies bcrypt password and sets an HttpOnly cookie without exposing a token", async () => {
  assert.equal((await login("admin", "incorrect")).status, 401);
  assert.equal((await login("missing-user")).status, 401);
  const response = await login("admin");
  const cookie = response.headers.get("set-cookie");
  assert.match(cookie, /HttpOnly/);
  assert.match(cookie, /SameSite=Strict/);
  assert.match(cookie, /Path=\/api\/v1/);
  const body = await response.json();
  assert.equal(body.data.sub, "trusted-admin");
  assert.equal(body.data.token, undefined);
  assert.equal(body.data.passwordHash, undefined);
});

test("browser login and cookie writes reject missing or foreign Origin", async () => {
  assert.equal((await login("admin", undefined, "https://evil.example")).status, 403);
  for (const headers of [{ Cookie: cookies.admin }, { Cookie: cookies.admin, Origin: "https://evil.example" }]) {
    assert.equal((await request("/after-sales/proposals/p1/approve", {
      method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: "{}",
    })).status, 403);
  }
});

test("viewer reads but cannot write; operator cannot approve or retry", async () => {
  assert.equal((await request("/after-sales/tickets", { headers: { Cookie: cookies.viewer } })).status, 200);
  for (const role of ["viewer", "operator"]) {
    assert.equal((await request("/after-sales/proposals/p1/approve", {
      method: "POST", headers: { Cookie: cookies[role], Origin: origin, "Content-Type": "application/json" }, body: "{}",
    })).status, 403);
    assert.equal((await request("/after-sales/execution-jobs/j1/retry", {
      method: "POST", headers: { Cookie: cookies[role], Origin: origin, "Content-Type": "application/json" }, body: "{}",
    })).status, 403);
  }
  assert.equal((await request("/after-sales/tickets", {
    method: "POST", headers: { Cookie: cookies.viewer, Origin: origin, "Content-Type": "application/json" }, body: "{}",
  })).status, 403);
});

test("approval overwrites forged actor and internal credentials using the authenticated subject", async () => {
  const response = await request("/after-sales/proposals/p1/approve", {
    method: "POST", headers: { Cookie: cookies.admin, Origin: origin, "Content-Type": "application/json",
      "X-Authenticated-Operator": "forged", "X-Internal-Service-Token": "forged-service" },
    body: JSON.stringify({ operatorId: "forged", comment: "verified" }),
  });
  assert.equal(response.status, 201);
  assert.equal(javaRequest.init.headers["X-Authenticated-Operator"], "trusted-admin");
  assert.equal(javaRequest.init.headers["X-Internal-Service-Token"], configValues.JAVA_INTERNAL_SERVICE_TOKEN);
  assert.deepEqual(JSON.parse(javaRequest.init.body), { comment: "verified" });
});

test("expired tokens, wrong issuer and role escalation in a valid token are rejected or ignored", async () => {
  const jwt = app.get(JwtService);
  const auth = app.get(AuthService);
  const { token } = await auth.login("viewer", "test-password-long-enough");
  const decoded = jwt.decode(token);
  for (const change of [{ exp: 1 }, { iss: "attacker" }, { aud: "another-service" }]) {
    const bad = jwt.sign({ ...decoded, ...change }, { secret: configValues.AUTH_JWT_SECRET, algorithm: "HS256" });
    assert.equal((await request("/auth/me", { headers: { Authorization: `Bearer ${bad}` } })).status, 401);
  }
  const escalated = jwt.sign({ ...decoded, roles: ["ADMIN"] }, { secret: configValues.AUTH_JWT_SECRET, algorithm: "HS256" });
  assert.equal((await request("/after-sales/proposals/p1/approve", {
    method: "POST", headers: { Authorization: `Bearer ${escalated}`, "Content-Type": "application/json" }, body: "{}",
  })).status, 403);
});

test("SSE authentication accepts cookies without query-string tokens", async () => {
  assert.equal((await request("/after-sales/runs/r1/stream?token=ignored")).status, 401);
  const response = await request("/after-sales/runs/r1/stream", { headers: { Cookie: cookies.admin, "Last-Event-ID": "event-0" } });
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /text\/event-stream/);
  assert.match(await response.text(), /run.completed/);
  assert.equal(javaRequest.init.headers["X-Internal-Service-Token"], configValues.JAVA_INTERNAL_SERVICE_TOKEN);
  assert.equal(javaRequest.init.headers["Last-Event-ID"], "event-0");
});

test("production startup fails closed and forces Secure cookies", () => {
  const production = { ...configValues, NODE_ENV: "production", AUTH_ALLOWED_ORIGINS: "https://ops.example.com" };
  assert.throws(() => new AuthSettings(new ConfigService({ ...production, AUTH_MODE: "demo" })));
  assert.throws(() => new AuthSettings(new ConfigService({ ...production, AUTH_JWT_SECRET: "short" })));
  assert.throws(() => new AuthSettings(new ConfigService({ ...production, JAVA_INTERNAL_SERVICE_TOKEN: "" })));
  assert.throws(() => new AuthSettings(new ConfigService({ ...production, AUTH_ACCOUNTS_JSON: "[]" })));
  const service = new AuthService(app.get(JwtService), new AuthSettings(new ConfigService(production)));
  assert.equal(service.cookieOptions().secure, true);
});

test("login rate limiter rejects repeated attempts", async () => {
  let status = 0;
  for (let index = 0; index < 55 && status !== 429; index++) {
    // A rejected origin is enough to exercise the guard without consuming password hashing CPU.
    status = (await login("admin", undefined, "https://evil.example")).status;
  }
  assert.equal(status, 429);
});
