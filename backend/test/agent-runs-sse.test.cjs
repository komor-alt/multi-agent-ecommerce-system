const { test } = require("node:test");
const assert = require("node:assert/strict");
require("reflect-metadata");
const { BadRequestException } = require("@nestjs/common");
const { Test } = require("@nestjs/testing");
const { AgentRunsController } = require("../dist/modules/agent-runs/agent-runs.controller");
const { AgentRunEventBus } = require("../dist/modules/agent-runs/agent-run-event-bus");
const { AgentRunsService } = require("../dist/modules/agent-runs/agent-runs.service");

function rawEvent(runId, sequence) {
  return {
    run_id: runId,
    event_id: `event-${sequence}`,
    sequence,
    type: "tool_completed",
    name: `tool-${sequence}`,
    status: "success",
    timestamp: new Date(sequence * 1000).toISOString(),
    data: { summary: `event ${sequence}` },
    metrics: {},
  };
}

function storedEvent(runId, sequence) {
  const raw = rawEvent(runId, sequence);
  return {
    id: raw.event_id,
    runId,
    sequence,
    eventType: "TOOL_COMPLETED",
    eventName: raw.name,
    status: "SUCCESS",
    createdAt: new Date(raw.timestamp),
    rawPayload: raw,
    latencyMs: null,
  };
}

function nextTurn() {
  return new Promise((resolve) => setImmediate(resolve));
}

test("Gateway SSE resumes after Last-Event-ID and merges a racing live event once", async () => {
  let resolveHistory;
  let requestedAfter;
  let statusReads = 0;
  const repository = {
    findRunStatus: async () => (++statusReads, "RUNNING"),
    findEventSequence: async (_runId, eventId) => eventId === "event-2" ? 2 : null,
    findEventsAfterSequence: async (_runId, sequence) => {
      requestedAfter = sequence;
      return new Promise((resolve) => { resolveHistory = resolve; });
    },
  };
  const bus = new AgentRunEventBus();
  const service = new AgentRunsService(repository, bus);
  const received = [];
  const stream = await service.stream("run-1", "event-2");
  const subscription = stream.subscribe({ next: (event) => received.push(event) });

  while (!resolveHistory) await nextTurn();
  bus.publish(rawEvent("run-1", 3));
  resolveHistory([storedEvent("run-1", 3), storedEvent("run-1", 4)]);
  await nextTurn();

  assert.equal(requestedAfter, 2);
  assert.deepEqual(received.map((event) => event.id), ["event-3", "event-4"]);
  bus.publish(rawEvent("run-1", 5));
  assert.deepEqual(received.map((event) => event.id), ["event-3", "event-4", "event-5"]);
  subscription.unsubscribe();
});

test("Gateway SSE closes for terminal history instead of leaking a heartbeat subscription", async () => {
  const repository = {
    findRunStatus: async () => "COMPLETED",
    findEventSequence: async () => null,
    findEventsAfterSequence: async () => [storedEvent("run-2", 1), storedEvent("run-2", 2)],
  };
  const bus = new AgentRunEventBus();
  const service = new AgentRunsService(repository, bus);
  const received = [];
  let completed = false;
  const stream = await service.stream("run-2");
  stream.subscribe({
    next: (event) => received.push(event),
    complete: () => { completed = true; },
  });
  await nextTurn();
  await nextTurn();

  assert.deepEqual(received.map((event) => event.id), ["event-1", "event-2"]);
  assert.equal(completed, true);
  assert.equal(bus.streams.has("run-2"), false);
});

test("Gateway SSE rejects a non-empty event ID outside the run before reading history", async () => {
  let historyRead = false;
  const repository = {
    findRunStatus: async () => "RUNNING",
    findEventSequence: async () => null,
    findEventsAfterSequence: async () => { historyRead = true; return []; },
  };
  const bus = new AgentRunEventBus();
  const service = new AgentRunsService(repository, bus);

  await assert.rejects(service.stream("run-3", "event-from-another-run"), (error) => {
    assert.equal(error.getStatus(), 400);
    assert.equal(error.message, "Last-Event-ID is not valid for this run");
    return true;
  });
  assert.equal(historyRead, false);
  assert.equal(bus.streams.has("run-3"), false);
});

test("Gateway returns HTTP 400 before opening SSE for an invalid cursor", async () => {
  const module = await Test.createTestingModule({
    controllers: [AgentRunsController],
    providers: [{
      provide: AgentRunsService,
      useValue: { stream: async () => { throw new BadRequestException("Last-Event-ID is not valid for this run"); } },
    }],
  }).compile();
  const app = module.createNestApplication();
  app.setGlobalPrefix("api/v1");
  await app.listen(0, "127.0.0.1");
  try {
    const response = await fetch(`${await app.getUrl()}/api/v1/agent-runs/run-4/stream?lastEventId=foreign-event`);
    const body = await response.json();
    assert.equal(response.status, 400);
    assert.match(body.message, /Last-Event-ID is not valid for this run/);
  } finally {
    await app.close();
  }
});

test("AgentRunsController prefers Last-Event-ID header and accepts frontend query fallback", async () => {
  const calls = [];
  const controller = new AgentRunsController({
    stream: async (...args) => { calls.push(args); return "stream"; },
  });

  await controller.stream("run-3", "event-header", "event-query");
  await controller.stream("run-3", undefined, "event-query");
  assert.deepEqual(calls, [["run-3", "event-header"], ["run-3", "event-query"]]);
});
