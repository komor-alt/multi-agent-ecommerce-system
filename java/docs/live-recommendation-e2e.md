# Java Recommendation Live LLM E2E

This test makes real provider calls and can incur charges. It is not run by default `mvn test`. Do not run it in CI or a shared environment unless the call budget and provider account are approved.

## Preconditions

- PostgreSQL is running and `SPRING_DATASOURCE_URL` points to it. The test checks the JDBC connection before Spring starts.
- `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` can access the recommendation schema.
- The database has the Java recommendation schema and a usable user row. The default test user is `user_889`; override it with `ECOM_RECOMMENDATION_E2E_USER_ID` when needed.
- A real provider key is supplied through a local secret manager or the current shell environment. Never put it in `.env.example`, source files, command history, test output, or CI logs.

## Explicit Opt-In

Set these values only in a private local shell/session. Keep the call budget at or below 32; the test guard rejects larger budgets. The same bounded budget is shared by the Supervisor, specialists and LLM-backed recommendation tools.

```powershell
$env:ECOM_RUN_LIVE_RECOMMENDATION_E2E = "true"
$env:ECOM_RECOMMEND_MODE = "LLM"
$env:ECOM_RECOMMEND_LIVE_ENABLED = "true"
$env:ECOM_RECOMMEND_MAX_LLM_CALLS = "24"
$env:ECOM_SUPERVISOR_MAX_LLM_CALLS = "24"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/ecommerce_agent"
$env:SPRING_DATASOURCE_USERNAME = "ecommerce"
# Provide SPRING_DATASOURCE_PASSWORD and ECOM_LLM_API_KEY from a secret manager.
cd java
mvn -Plive-recommendation-e2e test
```

The runner verifies the opt-in flag, mode, live switch, non-placeholder key, positive bounded budgets, and a working PostgreSQL connection before creating the application context. It then submits an `auto` retention request and checks the real LLM phases, database Run/Event persistence, tool execution, and final RecommendationPlan. Provider usage may be absent; in that case the trace and plan explicitly report usage as unavailable rather than estimating tokens.

## Offline Verification

The offline client, usage aggregation, trace redaction, mode configuration, and live-runner guard tests run under ordinary `mvn test`. They do not call the model provider. Do not use `-Plive-recommendation-e2e` unless a real paid run is intended.
