/**
 * 售后运营中心 · 主页面。
 *
 * 只保留编排层：query / mutation / SSE / 派生数据；
 * 展示层拆分到 AfterSalesQueue（指标/筛选/表格）与 AfterSalesWorkspace（三栏 + Trace）。
 */

import {
  PlusOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Typography,
} from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  analyzeAfterSalesTicket,
  approveAfterSalesProposal,
  createAfterSalesEventSource,
  createAfterSalesTicket,
  getAfterSalesTicket,
  listAfterSalesTickets,
  parseAfterSalesEvent,
  rejectAfterSalesProposal,
  retryAfterSalesExecution,
  startAfterSalesRun,
} from "../../api/afterSales";
import type { AfterSalesEvent, AfterSalesTicket } from "../../types/afterSales";
import {
  appendEvent,
  assessRiskLevel,
  collectEvidence,
  countryLabel,
  deriveApprovalChecks,
  deriveTicketMetrics,
  filterTickets,
  issueTypeLabel,
  streamLabel,
  ticketCountry,
} from "./afterSalesLogic";
import type { TicketFilters } from "./afterSalesLogic";
import { AfterSalesQueue } from "./AfterSalesQueue";
import { AfterSalesWorkspace } from "./AfterSalesWorkspace";

const streamEventTypes = [
  "run_started",
  "intake_started",
  "intake_completed",
  "tool_started",
  "tool_completed",
  "retrieval_completed",
  "run_completed",
  "error",
  "approval_recorded",
  "execution_started",
  "execution_completed",
  "execution_failed",
];

const demoMessage = "我的包裹十天没有更新了，现在到底是什么情况？能不能退款？";

/** 与 Java DemoFulfillmentDataFactory 同一批已知演示订单，仅作为「新建工单」的输入选项，不参与任何统计。 */
const knownDemoOrders = [
  { value: "O-VN-5002", label: "O-VN-5002 · 越南 VND" },
  { value: "O-VN-5001", label: "O-VN-5001 · 越南 VND" },
  { value: "O-SG-1001", label: "O-SG-1001 · 新加坡 SGD" },
  { value: "O-SG-1002", label: "O-SG-1002 · 新加坡 SGD" },
  { value: "O-SG-1003", label: "O-SG-1003 · 新加坡 SGD" },
  { value: "O-MY-2001", label: "O-MY-2001 · 马来西亚 MYR" },
  { value: "O-TH-3001", label: "O-TH-3001 · 泰国 THB" },
  { value: "O-ID-4001", label: "O-ID-4001 · 印尼 IDR" },
];

export function AfterSalesPage() {
  const queryClient = useQueryClient();
  const [ticketId, setTicketId] = useState<string>("");
  const [operatorId, setOperatorId] = useState("operator-vn-01");
  const [reviewComment, setReviewComment] = useState("订单与政策证据已核验，同意发放延迟补偿券。");
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [filters, setFilters] = useState<TicketFilters>({ search: "" });

  // 直播链路：只对「当前页面新建并分析」的 run 建立 SSE；历史工单只读取持久化事件。
  const [runId, setRunId] = useState("");
  const [liveTicketId, setLiveTicketId] = useState("");
  const [liveEvents, setLiveEvents] = useState<AfterSalesEvent[]>([]);
  const [streamStatus, setStreamStatus] = useState<"idle" | "connecting" | "live" | "closed" | "error">("idle");
  const [analysisStartedAt, setAnalysisStartedAt] = useState<number | null>(null);
  const [tickNow, setTickNow] = useState(0);
  const [backendDurationMs, setBackendDurationMs] = useState<number | null>(null);
  // 「先订阅、后启动」：stream_ready 握手后调用 start；重连/重试通过 epoch 重建 EventSource。
  const [streamEpoch, setStreamEpoch] = useState(0);
  const [startError, setStartError] = useState<string | null>(null);
  const sourceRef = useRef<EventSource | null>(null);
  const lastEventIdRef = useRef<string | undefined>(undefined);
  const closedRef = useRef(false);
  const reconnectAttemptsRef = useRef(0);
  const initialSelectionDoneRef = useRef(false);

  const listQuery = useQuery({
    queryKey: ["after-sales-tickets"],
    queryFn: async () => {
      const { items } = await listAfterSalesTickets();
      return items;
    },
  });

  const ticketQuery = useQuery({
    queryKey: ["after-sales-ticket", ticketId],
    queryFn: () => getAfterSalesTicket(ticketId),
    enabled: Boolean(ticketId),
  });

  const tickets = listQuery.data || [];
  const ticket = ticketQuery.data;

  // 初次进入：自动加载队列并优先选择最近一张工单（列表按 createdAt 倒序），不自动重新 analyze。
  useEffect(() => {
    if (initialSelectionDoneRef.current || !listQuery.isSuccess || ticketId) return;
    const latest = listQuery.data?.[0];
    if (latest) {
      initialSelectionDoneRef.current = true;
      setTicketId(latest.id);
    }
  }, [listQuery.isSuccess, listQuery.data, ticketId]);

  const metrics = useMemo(() => deriveTicketMetrics(tickets), [tickets]);
  const countryOptions = useMemo(() => {
    const seen = new Set<string>();
    const options: Array<{ value: string; label: string }> = [];
    for (const item of tickets) {
      const country = ticketCountry(item);
      if (!seen.has(country)) {
        seen.add(country);
        options.push({ value: country, label: countryLabel(country) });
      }
    }
    return options;
  }, [tickets]);
  const issueTypeOptions = useMemo(() => {
    const seen = new Set<string>();
    const options: Array<{ value: string; label: string }> = [];
    for (const item of tickets) {
      if (!seen.has(item.issueType)) {
        seen.add(item.issueType);
        options.push({ value: item.issueType, label: issueTypeLabel(item.issueType) });
      }
    }
    return options;
  }, [tickets]);
  const filteredTickets = useMemo(() => filterTickets(tickets, filters), [tickets, filters]);

  // 仅当「没有后端终值」且「直播 run 仍在进行」时启动本地计时。
  // liveRunActive 同时要求直播 run 属于当前选中工单，历史工单不会出现「运行中」假象。
  const liveRunActive = liveTicketId === ticketId && streamStatus !== "closed" && streamStatus !== "error";
  useEffect(() => {
    if (analysisStartedAt == null || backendDurationMs != null || !liveRunActive) return;
    const timer = window.setInterval(() => setTickNow(Date.now()), 200);
    return () => window.clearInterval(timer);
  }, [analysisStartedAt, backendDurationMs, liveRunActive]);

  // Trace 耗时：直播 run（属于当前选中工单）优先后端 durationMs，否则本地实时估算；
  // 历史工单只显示该工单持久化的 ticket.run.durationMs，不被后台 live run 的 backendDurationMs 串线。
  const traceDurationMs = useMemo(() => {
    if (liveTicketId === ticketId) {
      if (backendDurationMs != null) return backendDurationMs;
      if (analysisStartedAt != null && liveRunActive) return Date.now() - analysisStartedAt;
    }
    return ticket?.run?.durationMs ?? null;
  }, [liveTicketId, ticketId, backendDurationMs, analysisStartedAt, liveRunActive, tickNow, ticket?.run?.durationMs]);

  const createMutation = useMutation({
    mutationFn: async (input: { orderId: string; customerMessage: string }) => {
      const ticket = await createAfterSalesTicket(input);
      // deferred=true：只创建 READY run 并进入 ANALYZING，等 stream_ready 握手后再启动。
      const analysis = await analyzeAfterSalesTicket(ticket.id, { deferred: true });
      return { ticket, analysis };
    },
    onSuccess: ({ ticket, analysis }) => {
      setCreateModalOpen(false);
      setTicketId(ticket.id);
      setRunId(analysis.runId);
      setLiveTicketId(ticket.id);
      setLiveEvents([]);
      setStreamStatus("connecting");
      // 新 run 不继承旧值：计时与后端终值全部重置。
      setAnalysisStartedAt(null);
      setBackendDurationMs(null);
      setTickNow(0);
      setStartError(null);
      closedRef.current = false;
      reconnectAttemptsRef.current = 0;
      lastEventIdRef.current = undefined;
      setStreamEpoch((epoch) => epoch + 1);
      void queryClient.invalidateQueries({ queryKey: ["after-sales-tickets"] });
    },
  });

  // stream_ready 握手后调用 start；后端原子认领 READY->RUNNING，重连时重复调用是幂等的。
  const startRun = useCallback(async () => {
    try {
      await startAfterSalesRun(runId);
      setStartError(null);
    } catch (error) {
      setStartError(errorMessage(error));
      setStreamStatus("error");
      closedRef.current = true;
      sourceRef.current?.close();
      refreshTicket(liveTicketId, queryClient);
    }
  }, [queryClient, runId, liveTicketId]);

  const retryStart = () => {
    setStartError(null);
    closedRef.current = false;
    reconnectAttemptsRef.current = 0;
    setStreamStatus("connecting");
    setStreamEpoch((epoch) => epoch + 1);
  };

  const reviewMutation = useMutation({
    mutationFn: async (decision: "approve" | "reject") => {
      const proposalId = ticketQuery.data?.proposal?.id;
      if (!proposalId) throw new Error("当前没有可审批的方案");
      if (decision === "approve") {
        return approveAfterSalesProposal(proposalId, { operatorId, comment: reviewComment });
      }
      return rejectAfterSalesProposal(proposalId, { operatorId, comment: reviewComment });
    },
    onSuccess: () => refreshTicket(ticketId, queryClient),
  });

  const retryMutation = useMutation({
    mutationFn: (jobId: string) => retryAfterSalesExecution(jobId),
    onSuccess: () => refreshTicket(ticketId, queryClient),
  });

  useEffect(() => {
    if (!runId) return;
    // 重连时携带 Last-Event-ID 只回放缺失事件；服务端仍会先发 stream_ready，
    // 客户端再次调用 start —— 幂等，安全。
    const source = createAfterSalesEventSource(runId, lastEventIdRef.current);
    sourceRef.current = source;
    setStreamStatus("connecting");

    const handleEvent = (raw: Event) => {
      try {
        const event = parseAfterSalesEvent(raw as MessageEvent<string>);
        if (event.type === "stream_ready") {
          // 握手事件：只作「流已就绪、可以启动」的信号，不进入决策时间线。
          reconnectAttemptsRef.current = 0;
          setStreamStatus("live");
          void startRun();
          return;
        }
        if (event.eventId) lastEventIdRef.current = event.eventId;
        setStreamStatus("live");
        setLiveEvents((current) => appendEvent(current, event));
        if (event.type === "run_started") {
          setAnalysisStartedAt(Date.now());
          setTickNow(Date.now());
          setBackendDurationMs(null);
        }
        if (typeof event.data.durationMs === "number") {
          setBackendDurationMs(event.data.durationMs);
        }
        if (event.type === "run_completed") {
          refreshTicket(liveTicketId, queryClient);
        }
        const rejected = event.type === "approval_recorded" && event.data.decision === "REJECTED";
        if (event.type === "execution_completed" || event.type === "error" || rejected) {
          closedRef.current = true;
          setStreamStatus("closed");
          source.close();
          refreshTicket(liveTicketId, queryClient);
        }
      } catch {
        setStreamStatus("error");
      }
    };

    source.onopen = () => setStreamStatus("live");
    source.onmessage = handleEvent;
    streamEventTypes.forEach((type) => source.addEventListener(type, handleEvent));
    source.addEventListener("stream_ready", handleEvent);
    source.onerror = () => {
      if (closedRef.current) return;
      const attempts = reconnectAttemptsRef.current;
      if (attempts >= 3) {
        closedRef.current = true;
        setStreamStatus("error");
        refreshTicket(liveTicketId, queryClient);
        return;
      }
      reconnectAttemptsRef.current = attempts + 1;
      setStreamStatus("connecting");
      window.setTimeout(() => setStreamEpoch((epoch) => epoch + 1), 600 * attempts + 600);
    };
    return () => {
      sourceRef.current = null;
      source.close();
    };
  }, [queryClient, runId, startRun, streamEpoch, liveTicketId]);

  // 轨迹 = 该工单持久化事件 + 直播事件（仅当直播 run 属于当前选中工单）。
  const events = useMemo(() => {
    const persisted = ticket?.events || [];
    const live = liveTicketId === ticketId ? liveEvents : [];
    return [...persisted, ...live].reduce<AfterSalesEvent[]>(appendEvent, []);
  }, [ticket?.events, liveEvents, liveTicketId, ticketId]);

  const finalAnswer = ticket?.run?.finalAnswer;
  const proposal = ticket?.proposal;

  const evidenceItems = useMemo(() => collectEvidence(events), [events]);
  const verifiedEvidenceIds = useMemo(
    () => evidenceItems.filter((item) => item.verified && item.evidenceId).map((item) => item.evidenceId as string),
    [evidenceItems],
  );
  const checks = useMemo(
    () => deriveApprovalChecks({
      evidenceIds: finalAnswer?.evidenceIds || verifiedEvidenceIds,
      eligible: finalAnswer?.compensation?.eligible,
      amount: proposal?.amount ?? 0,
      maximumCompensation: finalAnswer?.policy?.maximumCompensation ?? 0,
    }),
    [finalAnswer, verifiedEvidenceIds, proposal?.amount],
  );
  // 币种链路：proposal.currency -> compensation.currency -> order.currency -> 空字符串，永远不用 country。
  // 风险规则优先使用 Intake 紧急度：HIGH 直接进入 HIGH 风险，其次才看金额/物流规则。
  const risk = useMemo(
    () => assessRiskLevel({
      amount: proposal?.amount ?? 0,
      currency: proposal?.currency ?? finalAnswer?.compensation?.currency ?? finalAnswer?.order?.currency ?? "",
      maxCompensation: finalAnswer?.policy?.maximumCompensation ?? 0,
      delayDays: finalAnswer?.shipment?.delayDays,
      intakeUrgency: finalAnswer?.intake?.urgency,
    }),
    [proposal?.amount, proposal?.currency, finalAnswer?.compensation?.currency, finalAnswer?.order?.currency, finalAnswer?.policy?.maximumCompensation, finalAnswer?.shipment?.delayDays, finalAnswer?.intake?.urgency],
  );

  const clearFilters = () => setFilters({ search: "" });
  const apiError = createMutation.error || ticketQuery.error || reviewMutation.error || retryMutation.error;

  return (
    <div className="page-stack after-sales-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={3}>售后运营中心</Typography.Title>
          <Typography.Text type="secondary">跨境物流异常 · 工单队列与人工审批</Typography.Text>
        </div>
        <Space>
          <Badge status={streamStatus === "live" ? "processing" : streamStatus === "error" ? "error" : "default"} />
          <Typography.Text type="secondary">{streamLabel(streamStatus)}</Typography.Text>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
            新建工单
          </Button>
        </Space>
      </div>

      {listQuery.isError ? (
        <Alert
          showIcon
          type="error"
          message="工单队列加载失败"
          description={errorMessage(listQuery.error)}
          action={<Button size="small" icon={<ReloadOutlined />} onClick={() => listQuery.refetch()}>重试</Button>}
        />
      ) : null}
      {apiError ? (
        <Alert
          showIcon
          type="error"
          message="售后链路暂不可用"
          description={errorMessage(apiError)}
        />
      ) : null}
      {startError ? (
        <Alert
          showIcon
          type="error"
          message="启动 Agent 失败"
          description={startError}
          action={<Button size="small" danger onClick={retryStart}>重试启动</Button>}
        />
      ) : null}

      <AfterSalesQueue
        metrics={metrics}
        filters={filters}
        onFiltersChange={setFilters}
        onClearFilters={clearFilters}
        countryOptions={countryOptions}
        issueTypeOptions={issueTypeOptions}
        tickets={filteredTickets}
        totalCount={tickets.length}
        loading={listQuery.isLoading}
        selectedTicketId={ticketId}
        onSelectTicket={setTicketId}
      />

      {ticketId ? (
        <AfterSalesWorkspace
          ticket={ticket}
          loading={ticketQuery.isLoading}
          events={events}
          evidenceItems={evidenceItems}
          checks={checks}
          risk={risk}
          operatorId={operatorId}
          reviewComment={reviewComment}
          onOperatorIdChange={setOperatorId}
          onReviewCommentChange={setReviewComment}
          onApprove={() => reviewMutation.mutate("approve")}
          onReject={() => reviewMutation.mutate("reject")}
          reviewPending={reviewMutation.isPending}
          onRetryExecution={(jobId) => retryMutation.mutate(jobId)}
          traceDurationMs={traceDurationMs}
          liveRunActive={liveRunActive}
        />
      ) : null}

      <Modal
        title="新建售后工单"
        open={createModalOpen}
        onCancel={() => setCreateModalOpen(false)}
        footer={null}
        destroyOnClose
      >
        <Form
          layout="vertical"
          initialValues={{ orderId: "O-VN-5002", customerMessage: demoMessage }}
          onFinish={(values) => createMutation.mutate(values as { orderId: string; customerMessage: string })}
        >
          <Form.Item label="订单" name="orderId" rules={[{ required: true, message: "请选择订单" }]}>
            <Select options={knownDemoOrders} />
          </Form.Item>
          <Form.Item label="用户诉求" name="customerMessage" rules={[{ required: true, message: "请填写用户诉求" }]}>
            <Input.TextArea rows={5} />
          </Form.Item>
          <Space>
            <Button onClick={() => setCreateModalOpen(false)}>取消</Button>
            <Button
              type="primary"
              htmlType="submit"
              icon={<PlusOutlined />}
              loading={createMutation.isPending}
            >
              创建并分析
            </Button>
          </Space>
        </Form>
      </Modal>
    </div>
  );
}

/**
 * 刷新工单详情 + 队列列表：审批成功、驳回、run_completed、execution_completed/failed、
 * 手动重试后都必须同时失效，否则顶部指标与队列状态不更新。
 * 延迟二次失效兜底异步执行任务的落库时差。
 */
function refreshTicket(ticketId: string, queryClient: ReturnType<typeof useQueryClient>) {
  if (!ticketId) return;
  void queryClient.invalidateQueries({ queryKey: ["after-sales-ticket", ticketId] });
  void queryClient.invalidateQueries({ queryKey: ["after-sales-tickets"] });
  window.setTimeout(() => {
    void queryClient.invalidateQueries({ queryKey: ["after-sales-ticket", ticketId] });
    void queryClient.invalidateQueries({ queryKey: ["after-sales-tickets"] });
  }, 900);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error || "未知错误");
}
