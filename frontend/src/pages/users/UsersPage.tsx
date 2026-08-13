import { Card, Table, Typography } from "antd";

export function UsersPage() {
  return <div className="page-stack"><div><Typography.Title level={3}>Users</Typography.Title><Typography.Text type="secondary">用户和角色管理，预留 admin/operator/viewer。</Typography.Text></div><Card><Table rowKey="id" dataSource={[]} columns={[{ title: "用户", dataIndex: "name" }, { title: "邮箱", dataIndex: "email" }, { title: "角色", dataIndex: "role" }, { title: "状态", dataIndex: "status" }]} /></Card></div>;
}
