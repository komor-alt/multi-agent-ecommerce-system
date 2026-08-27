import React, { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Badge, Button, Card, Col, Descriptions, Empty, Form, Input, InputNumber, Radio, Row, Select, Space, Statistic, Table, Tag, Timeline, Typography } from "antd";
import { ApiOutlined, BranchesOutlined, CheckCircleOutlined, ClockCircleOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { createRecommendationTask, listRecommendationTasks, RecommendationTaskSummary } from "../../api/recommendations";

type RunStatus = "idle" | "running" | "done" | "error";
type ScenarioKey = "homepage" | "campaign" | "retention";
type ExecutionMode = "gateway" | "fixed";

type Scenario = {
  user_id: string;
  scene: ScenarioKey;
  num_items: number;
  recent_views: string;
  purchase_count_30d: number;
  avg_order_amount: number;
  note: string;
};

type Product = {
  product_id: string;
  name: string;
  category: string;
  price: number;
  brand?: string;
  seller_id?: string;
  stock: number;
  tags: string[];
  score?: number;
};

type UserProfile = {
  user_id: string;
  segments?: string[];
  preferred_categories?: string[];
  price_range?: [number, number] | number[];
  rfm_score?: Record<string, number>;
  real_time_tags?: Record<string, unknown>;
};

type AgentResult = {
  agent_name: string;
  success: boolean;
  latency_ms: number;
  error?: string | null;
  data?: Record<string, unknown>;
  profile?: UserProfile | null;
  products?: Product[];
  copies?: Array<{ product_id: string; copy: string }>;
  prompt_template_used?: string;
  available_products?: string[];
  low_stock_alerts?: Array<{ product_id: string; level?: string }>;
};

type RecommendationResponse = {
  request_id: string;
  user_id: string;
  products: Product[];
  marketing_copies: Array<{ product_id: string; copy: string }>;
  experiment_group: string;
  agent_results: Record<string, AgentResult>;
  total_latency_ms: number;
};

type ProcessEvent = {
  id: string;
  ts: number;
  event: string;
  title: string;
  detail: string;
  status: "running" | "done" | "error" | "info";
  elapsedMs?: number;
};

type RunState = {
  status: RunStatus;
  elapsedMs: number;
  events: ProcessEvent[];
};

type SseFrame = {
  event: string;
  data: Record<string, unknown>;
};

const scenarios: Record<ScenarioKey, Scenario> = {
  homepage: {
    user_id: "user_001",
    scene: "homepage",
    num_items: 5,
    recent_views: "手机, 耳机, 充电宝",
    purchase_count_30d: 3,
    avg_order_amount: 500,
    note: "用户最近晚上活跃，偏好 Apple、降噪耳机和便携配件。",
  },
  campaign: {
    user_id: "user_208",
    scene: "campaign",
    num_items: 6,
    recent_views: "平板, 配件, 显示器",
    purchase_count_30d: 5,
    avg_order_amount: 260,
    note: "用户对价格较敏感，近期多次查看性价比商品和促销活动。",
  },
  retention: {
    user_id: "user_889",
    scene: "retention",
    num_items: 4,
    recent_views: "游戏机, 无人机, 穿戴",
    purchase_count_30d: 0,
    avg_order_amount: 1200,
    note: "用户 30 天未购买，适合召回权益和场景化推荐。",
  },
};

export function RecommendationConsole() {
  const navigate = useNavigate();
  const [activeScenario, setActiveScenario] = useState<ScenarioKey>("homepage");
  const [form, setForm] = useState<Scenario>(scenarios.homepage);
  const [run, setRun] = useState<RunState>(createRunState("idle"));
  const [products, setProducts] = useState<Product[]>([]);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [copies, setCopies] = useState<Array<{ product_id: string; copy: string }>>([]);
  const [experimentGroup, setExperimentGroup] = useState("control");
  const [executionMode, setExecutionMode] = useState<ExecutionMode>("fixed");
  const [gatewayError, setGatewayError] = useState("");
  const [gatewayAvailable, setGatewayAvailable] = useState(false);
  const [health, setHealth] = useState<{ ok: boolean; text: string; model: string }>({ ok: false, text: "检查中", model: "-" });
  const [tasks, setTasks] = useState<RecommendationTaskSummary[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  const submitInFlightRef = useRef(false);

  useEffect(() => {
    void checkHealth().then(setHealth);
    void listRecommendationTasks()
      .then((page) => {
        setTasks(page.items);
        setGatewayAvailable(true);
      })
      .catch(() => {
        setGatewayAvailable(false);
        setExecutionMode("fixed");
      });
    return () => abortRef.current?.abort();
  }, []);

  const isRunning = run.status === "running";
  const scenarioOptions = useMemo(
    () => (Object.keys(scenarios) as ScenarioKey[]).map((key) => ({ value: key, label: `${scenarioTitle(key)} - ${scenarioMeta(key)}` })),
    [],
  );

  function applyScenario(key: ScenarioKey) {
    setActiveScenario(key);
    setForm(scenarios[key]);
  }

  async function submit(event?: FormEvent) {
    event?.preventDefault();
    if (submitInFlightRef.current) return;
    submitInFlightRef.current = true;
    try {
      abortRef.current?.abort();

    setRun(createRunState("running"));
    setProducts([]);
    setProfile(null);
    setCopies([]);
    setExperimentGroup("分组中");
    setGatewayError("");

    if (executionMode === "gateway" && gatewayAvailable) {
      try {
        const task = await createRecommendationTask({
          userId: form.user_id.trim() || "user_001",
          scene: form.scene,
          numItems: Number(form.num_items || 5),
          context: buildPayload(form).context,
          agentConfig: {
            model: "deepseek-v4-flash",
            maxSteps: 8,
            toolWhitelist: [
              "get_user_profile", "load_campaign_constraints", "get_recent_orders",
              "search_products", "check_fulfillment", "check_inventory",
              "rerank", "generate_localized_copy", "generate_retention_copy", "final_answer",
            ],
          },
        });
        void listRecommendationTasks().then((page) => setTasks(page.items)).catch(() => undefined);
        setRun((current) => ({
          ...current,
          status: "done",
          events: [...current.events, createProcessEvent("gateway.created", "Gateway 任务已创建", `Run ID: ${task.runId}，正在跳转到执行详情。`, "info", current.elapsedMs)],
        }));
        navigate(`/runs/${task.runId}`);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setGatewayError(message);
        setRun((current) => ({
          ...current,
          status: "error",
          events: [...current.events, createProcessEvent("gateway.error", "Gateway 链路不可用", message, "error", current.elapsedMs)],
        }));
      }
      return;
    }

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const response = await fetch("/api/v1/recommend/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify(buildPayload(form)),
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        const text = await response.text();
        throw new Error(text || `HTTP ${response.status}`);
      }
      await readSseStream(response.body, (eventName, data) => {
        handleStreamEvent(eventName, data, {
          setRun,
          setProducts,
          setProfile,
          setCopies,
          setExperimentGroup,
        });
      });
    } catch (error) {
      if ((error as Error).name !== "AbortError") {
        const message = (error as Error).message;
        setRun((current) => ({
          ...current,
          status: "error",
          events: [...current.events, createProcessEvent("stream.error", "连接中断", message, "error", current.elapsedMs)],
        }));
      }
    } finally {
      abortRef.current = null;
    }
    } finally {
      submitInFlightRef.current = false;
    }
  }

  return (
    <div className="page-stack">
      <div>
        <Typography.Title level={3}>Recommendations</Typography.Title>
        <Typography.Text type="secondary">创建推荐 Agent 任务，或通过 Fixed Workflow baseline 直接观察流式执行过程。</Typography.Text>
      </div>

      {gatewayError ? <Alert type="error" showIcon message="Gateway 链路暂不可用" description={gatewayError} /> : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card title="请求输入" extra={<Badge status={health.ok ? "success" : "error"} text={`${health.text} · ${health.model}`} />}>
            <Form layout="vertical" onSubmitCapture={submit}>
              <Form.Item label="推荐场景">
                <Select value={activeScenario} options={scenarioOptions} onChange={applyScenario} />
              </Form.Item>
              <Form.Item
                label="执行链路"
                extra={!gatewayAvailable ? "Gateway 需要 Nest :3000，本机未启动，已固定走 Java。" : undefined}
              >
                <Radio.Group value={executionMode} onChange={(event) => setExecutionMode(event.target.value)} optionType="button" buttonStyle="solid">
                  <Radio.Button value="gateway" disabled={!gatewayAvailable}>Gateway</Radio.Button>
                  <Radio.Button value="fixed">Fixed Workflow Baseline</Radio.Button>
                </Radio.Group>
              </Form.Item>
              <Form.Item label="用户 ID">
                <Input value={form.user_id} onChange={(e) => setForm({ ...form, user_id: e.target.value })} />
              </Form.Item>
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item label="场景 Key">
                    <Select value={form.scene} onChange={(value) => setForm({ ...form, scene: value })} options={[{ value: "homepage" }, { value: "campaign" }, { value: "retention" }]} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item label="推荐数量">
                    <InputNumber style={{ width: "100%" }} min={1} max={10} value={form.num_items} onChange={(value) => setForm({ ...form, num_items: Number(value || 1) })} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label="近期浏览类目">
                <Input value={form.recent_views} onChange={(e) => setForm({ ...form, recent_views: e.target.value })} />
              </Form.Item>
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item label="30 天购买次数">
                    <InputNumber style={{ width: "100%" }} min={0} value={form.purchase_count_30d} onChange={(value) => setForm({ ...form, purchase_count_30d: Number(value || 0) })} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item label="客单价">
                    <InputNumber style={{ width: "100%" }} min={0} value={form.avg_order_amount} onChange={(value) => setForm({ ...form, avg_order_amount: Number(value || 0) })} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label="补充上下文">
                <Input.TextArea rows={4} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
              </Form.Item>
              <Space style={{ width: "100%" }}>
                <Button onClick={() => setForm(scenarios[activeScenario])}>重置</Button>
                <Button type="primary" htmlType="submit" loading={isRunning} icon={executionMode === "gateway" ? <BranchesOutlined /> : <ThunderboltOutlined />}>
                  {executionMode === "gateway" ? "创建 Gateway 任务" : "运行 Fixed Workflow"}
                </Button>
              </Space>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={8}><Card><Statistic title="执行状态" value={statusLabel(run.status)} prefix={statusIcon(run.status)} /></Card></Col>
            <Col xs={24} sm={8}><Card><Statistic title="过程事件" value={run.events.length} /></Card></Col>
            <Col xs={24} sm={8}><Card><Statistic title="当前耗时" value={Math.round(run.elapsedMs)} suffix="ms" /></Card></Col>
          </Row>
          <Card title="思考过程" extra={<Tag color={run.status === "running" ? "processing" : run.status === "error" ? "error" : run.status === "done" ? "success" : "default"}>{statusLabel(run.status)}</Tag>} style={{ marginTop: 16 }}>
            <ProcessTimeline run={run} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <Card title="最终答案：关键商品" extra={<Tag color="blue">{experimentGroup}</Tag>}>
            <ProductsTable products={products} status={run.status} />
          </Card>
        </Col>
        <Col xs={24} xl={6}>
          <Card title="用户画像">
            <Profile profile={profile} />
          </Card>
        </Col>
        <Col xs={24} xl={6}>
          <Card title="营销文案">
            <Copies copies={copies} />
          </Card>
        </Col>
      </Row>
      <Card title="推荐任务历史" extra={<Tag color="blue">{tasks.length} 条</Tag>}>
        <Table<RecommendationTaskSummary>
          rowKey="id"
          dataSource={tasks}
          pagination={{ pageSize: 8 }}
          columns={[
            { title: "场景", dataIndex: "scene" },
            { title: "用户", dataIndex: "userId" },
            { title: "市场", render: (_: unknown, task: RecommendationTaskSummary) => task.market.country + " / " + task.market.currency },
            { title: "状态", dataIndex: "status", render: (status: string) => <Tag color={status === "completed" ? "success" : status === "failed" ? "error" : "processing"}>{status}</Tag> },
            { title: "Run", dataIndex: "runId", render: (runId?: string) => runId ? <Button type="link" onClick={() => navigate("/runs/" + runId)}>{runId.slice(0, 8)}</Button> : "-" },
            { title: "创建时间", dataIndex: "createdAt", render: (value: string) => new Date(value).toLocaleString() },
          ]}
        />
      </Card>
    </div>
  );
}

function ProcessTimeline({ run }: { run: RunState }) {
  if (!run.events.length) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="运行后，SSE 事件会在这里逐条追加。" />;
  }

  return (
    <Timeline
      items={run.events.map((item) => ({
        color: item.status === "error" ? "red" : item.status === "running" ? "blue" : item.status === "done" ? "green" : "gray",
        children: (
          <div className="event-line">
            <Space wrap>
              <Typography.Text strong>{item.title}</Typography.Text>
              <Tag>{item.event}</Tag>
              <Typography.Text type="secondary">{item.elapsedMs === undefined ? "-" : `${Math.round(item.elapsedMs)} ms`}</Typography.Text>
            </Space>
            <div className="event-line-summary">{item.detail}</div>
          </div>
        ),
      }))}
    />
  );
}

function ProductsTable({ products, status }: { products: Product[]; status: RunStatus }) {
  if (!products.length) {
    const text = status === "running" ? "Agent 正在执行，最终关键商品会在完成后出现。" : "还没有可展示的商品。";
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />;
  }
  return (
    <Table<Product>
      rowKey="product_id"
      dataSource={products}
      pagination={false}
      columns={[
        { title: "Product ID", dataIndex: "product_id" },
        { title: "名称", dataIndex: "name" },
        { title: "类目", dataIndex: "category", render: (value: string) => <Tag>{value}</Tag> },
        { title: "品牌", dataIndex: "brand", render: (value?: string) => value || "-" },
        { title: "价格", dataIndex: "price", render: (value: number) => `CNY ${formatNumber(value)}` },
        { title: "库存", dataIndex: "stock", render: (value: number) => <Tag color={value <= 20 ? "warning" : "success"}>{value}</Tag> },
        { title: "Score", dataIndex: "score", render: formatScore },
      ]}
    />
  );
}

function Profile({ profile }: { profile: UserProfile | null }) {
  if (!profile) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="等待用户画像 Agent 返回。" />;
  return (
    <Descriptions column={1} size="small" items={[
      { key: "segments", label: "用户分群", children: joinOr(profile.segments, "active") },
      { key: "categories", label: "偏好类目", children: joinOr(profile.preferred_categories, "未识别") },
      { key: "price", label: "价格区间", children: Array.isArray(profile.price_range) ? profile.price_range.join(" - ") : "-" },
      { key: "rfm", label: "RFM", children: <pre className="json-view">{JSON.stringify(profile.rfm_score || {}, null, 2)}</pre> },
    ]} />
  );
}

function Copies({ copies }: { copies: Array<{ product_id: string; copy: string }> }) {
  if (!copies.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="等待营销文案 Agent 返回。" />;
  return (
    <Space direction="vertical" size={10} style={{ width: "100%" }}>
      {copies.map((item) => (
        <Card size="small" key={item.product_id} title={item.product_id}>
          <Typography.Paragraph style={{ marginBottom: 0 }}>{item.copy}</Typography.Paragraph>
        </Card>
      ))}
    </Space>
  );
}

async function checkHealth() {
  try {
    const res = await fetch("/health");
    const data = await res.json() as { status?: string; model?: string };
    return { ok: true, text: data.status === "healthy" ? "运行中" : "可访问", model: data.model || "-" };
  } catch {
    return { ok: false, text: "未连接", model: "-" };
  }
}

async function readSseStream(body: ReadableStream<Uint8Array>, onEvent: (event: string, data: Record<string, unknown>) => void) {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split("\n\n");
    buffer = chunks.pop() || "";
    chunks.map(parseSseFrame).filter(Boolean).forEach((frame) => onEvent(frame!.event, frame!.data));
  }
  if (buffer.trim()) {
    const frame = parseSseFrame(buffer);
    if (frame) onEvent(frame.event, frame.data);
  }
}

function parseSseFrame(chunk: string): SseFrame | null {
  let event = "message";
  const dataLines: string[] = [];
  chunk.split(/\r?\n/).forEach((line) => {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
  });
  if (!dataLines.length) return null;
  return { event, data: JSON.parse(dataLines.join("\n")) as Record<string, unknown> };
}

function handleStreamEvent(
  event: string,
  raw: Record<string, unknown>,
  setters: {
    setRun: React.Dispatch<React.SetStateAction<RunState>>;
    setProducts: React.Dispatch<React.SetStateAction<Product[]>>;
    setProfile: React.Dispatch<React.SetStateAction<UserProfile | null>>;
    setCopies: React.Dispatch<React.SetStateAction<Array<{ product_id: string; copy: string }>>>;
    setExperimentGroup: React.Dispatch<React.SetStateAction<string>>;
  },
) {
  const data = unwrapEventData(raw);
  const elapsed = numberValue(data.elapsed_ms ?? data.elapsedMs ?? raw.elapsedMs);
  const processEvent = buildProcessEvent(event, data);
  setters.setRun((current) => ({
    ...current,
    elapsedMs: elapsed ?? current.elapsedMs,
    events: processEvent ? [...current.events, processEvent] : current.events,
  }));

  if (event === "experiment.assigned") {
    setters.setExperimentGroup(stringValue(data.group) || "control");
  }

  if (event === "agent.completed") {
    const key = stringValue(data.key);
    const result = normalizeAgentResult(data.result);
    if (key === "user_profile" || key === "get_user_profile") setters.setProfile(result.profile || null);
    if (key === "marketing_copy" || key === "generate_localized_copy" || key === "generate_retention_copy") {
      setters.setCopies(result.copies || []);
    }
  }

  if (event === "run.completed") {
    const response = normalizeRecommendation(data.response);
    setters.setRun((current) => ({
      ...current,
      status: "done",
      elapsedMs: numberValue(data.elapsed_ms ?? data.elapsedMs ?? raw.elapsedMs) ?? current.elapsedMs,
    }));
    setters.setProducts(response.products || []);
    setters.setProfile(response.agent_results?.user_profile?.profile || null);
    setters.setCopies(response.marketing_copies || []);
    setters.setExperimentGroup(response.experiment_group || "control");
  }
}

function buildProcessEvent(event: string, data: Record<string, unknown>): ProcessEvent | null {
  const elapsed = numberValue(data.elapsed_ms);
  if (event === "run.started") {
    return createProcessEvent(event, "收到推荐请求", `user_id=${stringValue(data.user_id)}，scene=${stringValue(data.scene)}，num_items=${numberValue(data.num_items) ?? "-"}`, "info", elapsed);
  }
  if (event === "experiment.assigned") {
    return createProcessEvent(event, "A/B 分组完成", `group=${stringValue(data.group) || "control"}，strategy=${stringValue(data.strategy) || "default"}`, "done", elapsed);
  }
  if (event === "phase.started") {
    return createProcessEvent(event, `${stringValue(data.phase)} 开始`, stringValue(data.summary), "running", elapsed);
  }
  if (event === "phase.completed") {
    const products = Array.isArray(data.products) ? data.products as Product[] : [];
    const suffix = products.length ? ` 关键商品候选：${products.map((item) => item.product_id).join(" / ")}` : "";
    return createProcessEvent(event, `${stringValue(data.phase)} 完成`, `${stringValue(data.summary)}${suffix}`, "done", elapsed);
  }
  if (event === "agent.started") {
    const title = stringValue(data.title) || agentLabel(stringValue(data.key));
    return createProcessEvent(event, `${title} 开始`, stringValue(data.summary), "running", elapsed);
  }
  if (event === "agent.completed") {
    const key = stringValue(data.key);
    const success = data.success !== false;
    const latency = numberValue(data.latency_ms);
    const detail = `${stringValue(data.summary) || summarizeAgentResult(key, data.result as AgentResult)}${latency === undefined ? "" : ` 耗时 ${Math.round(latency)} ms。`}`;
    return createProcessEvent(event, `${agentLabel(key)} ${success ? "完成" : "失败"}`, detail, success ? "done" : "error", elapsed);
  }
  if (event === "run.completed") {
    const response = normalizeRecommendation(data.response);
    const ids = (response.products || []).map((item) => item.product_id).join(" / ") || "无";
    return createProcessEvent(event, "最终答案已生成", `关键商品：${ids}。总耗时 ${Math.round(response.total_latency_ms || elapsed || 0)} ms。`, "done", elapsed);
  }
  return createProcessEvent(event, event, JSON.stringify(data), "info", elapsed);
}

function createProcessEvent(event: string, title: string, detail: string, status: ProcessEvent["status"], elapsedMs?: number): ProcessEvent {
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    ts: Date.now(),
    event,
    title,
    detail: detail || "-",
    status,
    elapsedMs,
  };
}

function summarizeAgentResult(key: string, result?: AgentResult) {
  if (!result) return "未返回结构化结果。";
  if (result.success === false) return result.error || "调用失败，进入降级。";
  if (key === "user_profile") return "用户画像已完成。";
  if (key === "product_recall" || key === "product_rec") return `输出 ${(result.products || []).length} 个商品。`;
  if (key === "inventory") return `可售商品 ${(result.available_products || []).length} 个。`;
  if (key === "marketing_copy") return `生成 ${(result.copies || []).length} 条营销文案。`;
  return "Agent 已完成。";
}

function agentLabel(key: string) {
  const labels: Record<string, string> = {
    user_profile: "用户画像 Agent",
    product_recall: "商品召回 Agent",
    product_rec: "商品重排 Agent",
    inventory: "库存决策 Agent",
    marketing_copy: "营销文案 Agent",
  };
  return labels[key] || key || "Agent";
}

function unwrapEventData(raw: Record<string, unknown>): Record<string, unknown> {
  const inner = raw.data;
  if (inner && typeof inner === "object" && !Array.isArray(inner)) {
    return inner as Record<string, unknown>;
  }
  return raw;
}

function normalizeAgentResult(value: unknown): AgentResult {
  const raw = (value && typeof value === "object" ? value : {}) as Record<string, unknown>;
  const nested = raw.data && typeof raw.data === "object" ? raw.data as Record<string, unknown> : {};
  const profile = normalizeProfile(raw.profile || nested.profile);
  const copies = normalizeCopies(raw.copies || nested.copies);
  return {
    agent_name: stringValue(raw.agent_name ?? raw.agentName),
    success: raw.success !== false,
    latency_ms: numberValue(raw.latency_ms ?? raw.latencyMs) || 0,
    error: stringValue(raw.error) || null,
    data: nested,
    profile,
    products: normalizeProducts(raw.products || nested.products),
    copies,
    available_products: Array.isArray(raw.available_products || nested.available_products)
      ? (raw.available_products || nested.available_products) as string[]
      : [],
  };
}

function normalizeRecommendation(value: unknown): RecommendationResponse {
  const raw = (value && typeof value === "object" ? value : {}) as Record<string, unknown>;
  const agentResultsRaw = (raw.agent_results || raw.agentResults || {}) as Record<string, unknown>;
  const agent_results: Record<string, AgentResult> = {};
  Object.entries(agentResultsRaw).forEach(([key, item]) => {
    agent_results[key] = normalizeAgentResult(item);
  });
  return {
    request_id: stringValue(raw.request_id ?? raw.requestId),
    user_id: stringValue(raw.user_id ?? raw.userId),
    products: normalizeProducts(raw.products),
    marketing_copies: normalizeCopies(raw.marketing_copies || raw.marketingCopies),
    experiment_group: stringValue(raw.experiment_group ?? raw.experimentGroup) || "control",
    agent_results,
    total_latency_ms: numberValue(raw.total_latency_ms ?? raw.totalLatencyMs) || 0,
  };
}

function normalizeProducts(value: unknown): Product[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    const raw = (item && typeof item === "object" ? item : {}) as Record<string, unknown>;
    return {
      product_id: stringValue(raw.product_id ?? raw.productId) || "unknown",
      name: stringValue(raw.name) || "-",
      category: stringValue(raw.category) || "-",
      price: numberValue(raw.price) || 0,
      brand: stringValue(raw.brand) || undefined,
      seller_id: stringValue(raw.seller_id ?? raw.sellerId) || undefined,
      stock: numberValue(raw.stock) || 0,
      tags: Array.isArray(raw.tags) ? raw.tags.map(String) : [],
      score: numberValue(raw.score),
    };
  });
}

function normalizeCopies(value: unknown): Array<{ product_id: string; copy: string }> {
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    const raw = (item && typeof item === "object" ? item : {}) as Record<string, unknown>;
    return {
      product_id: stringValue(raw.product_id ?? raw.productId) || "unknown",
      copy: stringValue(raw.copy) || "",
    };
  });
}

function normalizeProfile(value: unknown): UserProfile | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  return {
    user_id: stringValue(raw.user_id ?? raw.userId),
    segments: Array.isArray(raw.segments) ? raw.segments.map(String) : [],
    preferred_categories: Array.isArray(raw.preferred_categories || raw.preferredCategories)
      ? (raw.preferred_categories || raw.preferredCategories) as string[]
      : [],
    price_range: Array.isArray(raw.price_range || raw.priceRange)
      ? (raw.price_range || raw.priceRange) as number[]
      : [],
    rfm_score: (raw.rfm_score || raw.rfmScore || {}) as Record<string, number>,
    real_time_tags: (raw.real_time_tags || raw.realTimeTags || {}) as Record<string, unknown>,
  };
}

function buildPayload(form: Scenario) {
  return {
    userId: form.user_id.trim() || "user_001",
    scene: form.scene,
    numItems: Number(form.num_items || 5),
    platform: "shopify",
    region: "SEA",
    country: "SG",
    locale: "en-SG",
    currency: "SGD",
    context: {
      recent_views: splitList(form.recent_views),
      purchase_count_30d: Number(form.purchase_count_30d || 0),
      avg_order_amount: Number(form.avg_order_amount || 0),
      note: form.note.trim(),
      active_hours: [20, 21, 22],
    },
  };
}

function createRunState(status: RunStatus): RunState {
  return { status, elapsedMs: 0, events: [] };
}

function statusLabel(status: RunStatus) {
  if (status === "running") return "运行中";
  if (status === "done") return "已完成";
  if (status === "error") return "异常";
  return "待运行";
}

function statusIcon(status: RunStatus) {
  if (status === "running") return <ClockCircleOutlined />;
  if (status === "done") return <CheckCircleOutlined />;
  if (status === "error") return <ApiOutlined />;
  return <BranchesOutlined />;
}

function scenarioTitle(key: ScenarioKey) {
  return key === "homepage" ? "首页精选" : key === "campaign" ? "大促会场" : "流失召回";
}

function scenarioMeta(key: ScenarioKey) {
  return key === "homepage" ? "手机 / 耳机 / 充电宝" : key === "campaign" ? "价格敏感 + 限时促销" : "低活跃 / 专属优惠";
}

function splitList(value: string) {
  return value.split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean);
}

function joinOr(value: string[] | undefined, fallback: string) {
  return Array.isArray(value) && value.length ? value.join(" / ") : fallback;
}

function formatNumber(value: unknown) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN") : "0";
}

function formatScore(value: unknown) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toFixed(2) : "0.00";
}

function stringValue(value: unknown) {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

export default RecommendationConsole;
