import { Alert, Card, Col, Row, Statistic, Table, Tag, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getDashboardOverview } from "../../api/dashboard";
import type { AgentRunSummary } from "../../types/contracts";

export function DashboardPage() {
  const overviewQuery = useQuery({ queryKey: ["dashboard-overview"], queryFn: getDashboardOverview, refetchInterval: 15000 });
  const metrics = overviewQuery.data?.metrics;

  return (
    <div className="page-stack">
      <div>
        <Typography.Title level={3}>Dashboard</Typography.Title>
        <Typography.Text type="secondary">来自 Gateway 与 PostgreSQL 的实时 Agent 平台指标。</Typography.Text>
      </div>

      {overviewQuery.error ? <Alert type="error" showIcon message="Dashboard 暂不可用" description={(overviewQuery.error as Error).message} /> : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="今日 Agent Run" value={metrics?.todayRuns ?? 0} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="Agent 成功率" value={metrics?.successRate ?? 0} suffix="%" precision={1} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="平均延迟" value={metrics?.avgLatencyMs ?? 0} suffix="ms" /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="Token 使用量" value={metrics?.totalTokens ?? 0} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="运行中任务" value={metrics?.runningRuns ?? 0} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="商品总数" value={metrics?.productCount ?? 0} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card loading={overviewQuery.isLoading}><Statistic title="Embedding Ready" value={metrics?.readyProductCount ?? 0} /></Card></Col>
      </Row>

      <Card title="最近执行任务">
        <Table<AgentRunSummary>
          rowKey="id"
          loading={overviewQuery.isLoading}
          dataSource={overviewQuery.data?.recentRuns || []}
          pagination={false}
          columns={[
            { title: "Run ID", dataIndex: "id", render: (id: string) => <Link to={`/runs/${id}`}>{id.slice(0, 8)}</Link> },
            { title: "状态", dataIndex: "status", render: (status: string) => <Tag color={status === "completed" ? "success" : status === "failed" ? "error" : "processing"}>{status}</Tag> },
            { title: "模型", dataIndex: "modelName" },
            { title: "步骤", dataIndex: "stepCount" },
            { title: "延迟", dataIndex: "latencyMs", render: (value?: number) => value == null ? "-" : `${value} ms` },
            { title: "创建时间", dataIndex: "createdAt" },
          ]}
        />
      </Card>
    </div>
  );
}
