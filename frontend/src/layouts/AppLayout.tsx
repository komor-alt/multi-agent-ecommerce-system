import {
  AppstoreOutlined,
  BarChartOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  HomeOutlined,
  OrderedListOutlined,
  ProductOutlined,
  SettingOutlined,
  ShoppingCartOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { Layout, Menu, Space, Tag, Typography } from "antd";
import type { MenuProps } from "antd";
import { Outlet, useLocation, useNavigate } from "react-router-dom";

const { Header, Sider, Content } = Layout;

const menuItems: MenuProps["items"] = [
  { key: "/dashboard", icon: <HomeOutlined />, label: "Dashboard" },
  { key: "/runs", icon: <OrderedListOutlined />, label: "Agent Runs" },
  { key: "/recommendations", icon: <AppstoreOutlined />, label: "Recommendations" },
  { key: "/products", icon: <ProductOutlined />, label: "Products" },
  { key: "/users", icon: <TeamOutlined />, label: "Users" },
  { key: "/orders", icon: <ShoppingCartOutlined />, label: "Orders" },
  { key: "/evaluations", icon: <ExperimentOutlined />, label: "Evaluations" },
  { key: "/settings", icon: <SettingOutlined />, label: "Settings" },
];

export function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const selectedKey = `/${location.pathname.split("/")[1] || "dashboard"}`;

  return (
    <Layout className="app-shell">
      <Sider width={248} className="app-sider">
        <div className="app-brand">
          <div className="app-brand-mark"><DatabaseOutlined /></div>
          <div>
            <Typography.Text strong>Ecom Agent</Typography.Text>
            <div className="app-brand-subtitle">AI Operations Platform</div>
          </div>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="app-header">
          <div>
            <Typography.Title level={4}>跨境电商 AI Agent 工作台</Typography.Title>
            <Typography.Text type="secondary">任务管理、执行追踪、商品数据和评测配置的一体化运营平台</Typography.Text>
          </div>
          <Space>
            <Tag color="processing"><BarChartOutlined /> Gateway Ready</Tag>
            <Tag>viewer</Tag>
          </Space>
        </Header>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
