/**
 * 售后运营中心 · 队列展示区（无状态展示组件）。
 *
 * 指标带 + 筛选栏 + 工单表格，全部由父页面传入派生数据：
 * - metrics：由 afterSalesLogic.deriveTicketMetrics 推导；
 * - filters / options：父页面维护筛选状态与选项；
 * - tickets：父页面过滤后的结果集。
 * 不持有任何查询/变更/SSE 状态，交互仅通过回调上报。
 */

import {
  Button,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import type { AfterSalesTicket } from "../../types/afterSales";
import {
  countryLabel,
  executionStatusLabel,
  formatDateTime,
  isExecutionException,
  issueTypeLabel,
  SLA_RISK_THRESHOLD_HOURS,
  ticketCountry,
  ticketStatusColor,
  ticketStatusLabel,
} from "./afterSalesLogic";
import type { TicketFilters, TicketMetrics } from "./afterSalesLogic";

const statusFilterOptions = ["OPEN", "ANALYZING", "PENDING_APPROVAL", "RESOLVED", "FAILED"].map((status) => ({
  value: status,
  label: ticketStatusLabel(status),
}));

export function AfterSalesQueue({
  metrics,
  filters,
  onFiltersChange,
  onClearFilters,
  countryOptions,
  issueTypeOptions,
  tickets,
  totalCount,
  loading,
  selectedTicketId,
  onSelectTicket,
}: {
  metrics: TicketMetrics;
  filters: TicketFilters;
  onFiltersChange: (filters: TicketFilters) => void;
  onClearFilters: () => void;
  countryOptions: Array<{ value: string; label: string }>;
  issueTypeOptions: Array<{ value: string; label: string }>;
  tickets: AfterSalesTicket[];
  totalCount: number;
  loading: boolean;
  selectedTicketId: string;
  onSelectTicket: (ticketId: string) => void;
}) {
  return (
    <>
      <div className="metric-band">
        <MetricCell label="待处理" value={metrics.pending} hint="未开始分析" />
        <MetricCell label="SLA 风险" value={metrics.slaRisk} hint={`最后活动超 ${SLA_RISK_THRESHOLD_HOURS} 小时`} />
        <MetricCell label="待审批" value={metrics.pendingApproval} hint="方案待人工确认" />
        <MetricCell label="执行异常" value={metrics.executionFailed} hint="执行重试等待 / 死信" />
      </div>

      <div className="table-toolbar filter-bar">
        <Input
          allowClear
          placeholder="搜索工单号 / 订单号 / 诉求"
          value={filters.search}
          onChange={(event) => onFiltersChange({ ...filters, search: event.target.value })}
          style={{ maxWidth: 260 }}
        />
        <Select
          allowClear
          placeholder="国家"
          options={countryOptions}
          value={filters.country}
          onChange={(value) => onFiltersChange({ ...filters, country: value })}
          style={{ minWidth: 120 }}
        />
        <Select
          allowClear
          placeholder="问题类型"
          options={issueTypeOptions}
          value={filters.issueType}
          onChange={(value) => onFiltersChange({ ...filters, issueType: value })}
          style={{ minWidth: 120 }}
        />
        <Select
          allowClear
          placeholder="状态"
          options={statusFilterOptions}
          value={filters.status}
          onChange={(value) => onFiltersChange({ ...filters, status: value })}
          style={{ minWidth: 120 }}
        />
        <Select
          allowClear
          placeholder="SLA"
          options={[{ value: "risk", label: "有风险" }]}
          value={filters.slaRisk ? "risk" : undefined}
          onChange={(value) => onFiltersChange({ ...filters, slaRisk: value === "risk" })}
          style={{ minWidth: 100 }}
        />
        <Button onClick={onClearFilters}>清空</Button>
        <Typography.Text type="secondary" className="filter-count">
          共 {tickets.length} / {totalCount} 张
        </Typography.Text>
      </div>

      <Table<AfterSalesTicket>
        rowKey="id"
        size="small"
        className="queue-table"
        dataSource={tickets}
        loading={loading}
        pagination={{ pageSize: 12, showSizeChanger: false, showTotal: (total) => `共 ${total} 张工单` }}
        scroll={{ x: 720 }}
        rowClassName={(record) => (record.id === selectedTicketId ? "queue-row-selected" : "")}
        onRow={(record) => ({
          onClick: () => onSelectTicket(record.id),
          style: { cursor: "pointer" },
        })}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loading ? "加载中" : "暂无工单"} /> }}
        columns={queueColumns}
      />
    </>
  );
}

function MetricCell({ label, value, hint }: { label: string; value: number; hint: string }) {
  return (
    <div className="metric-cell">
      <Typography.Text type="secondary" className="metric-cell-label">{label}</Typography.Text>
      <div className="metric-cell-value">{value}</div>
      <Typography.Text type="secondary" className="metric-cell-hint">{hint}</Typography.Text>
    </div>
  );
}

const queueColumns: ColumnsType<AfterSalesTicket> = [
  {
    title: "工单号",
    dataIndex: "ticketNo",
    width: 130,
    render: (value: string) => <Typography.Text strong>{value}</Typography.Text>,
  },
  {
    title: "订单号",
    dataIndex: "orderId",
    width: 130,
    render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
  },
  {
    title: "问题 / 国家",
    key: "issue",
    width: 140,
    render: (_, record) => (
      <div className="queue-cell">
        <Typography.Text>{issueTypeLabel(record.issueType)}</Typography.Text>
        <Typography.Text type="secondary" className="queue-cell-sub">
          {countryLabel(ticketCountry(record))}
        </Typography.Text>
      </div>
    ),
  },
  {
    title: "状态",
    dataIndex: "status",
    width: 140,
    render: (value: string, record) => (
      <Space size={4} wrap>
        <Tag color={ticketStatusColor(value)}>{ticketStatusLabel(value)}</Tag>
        {/* 执行异常附加标记：让运营在队列中直接看到 RETRY_WAIT / DEAD_LETTER。 */}
        {isExecutionException(record.executionStatus) ? (
          <Tag color="error">{executionStatusLabel(record.executionStatus)}</Tag>
        ) : null}
      </Space>
    ),
  },
  {
    title: "更新时间",
    dataIndex: "updatedAt",
    width: 160,
    render: (value: string) => (
      <Typography.Text type="secondary" className="tabular">{formatDateTime(value)}</Typography.Text>
    ),
  },
];
