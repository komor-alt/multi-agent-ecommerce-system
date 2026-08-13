import { Card, Table, Typography } from "antd";

export function OrdersPage() {
  return <div className="page-stack"><div><Typography.Title level={3}>Orders</Typography.Title><Typography.Text type="secondary">订单列表和跨境平台原始载荷。</Typography.Text></div><Card><Table rowKey="id" dataSource={[]} columns={[{ title: "订单号", dataIndex: "orderNo" }, { title: "用户", dataIndex: "userId" }, { title: "状态", dataIndex: "status" }, { title: "金额", dataIndex: "totalAmount" }, { title: "平台", dataIndex: "platform" }]} /></Card></div>;
}
