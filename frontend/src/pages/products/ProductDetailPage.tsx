import { Card, Descriptions, Empty, Typography } from "antd";
import { useParams } from "react-router-dom";

export function ProductDetailPage() {
  const { productId } = useParams();
  return <div className="page-stack"><div><Typography.Title level={3}>Product Detail</Typography.Title><Typography.Text type="secondary">Product ID: {productId}</Typography.Text></div><Card><Descriptions column={1} items={[{ key: "status", label: "状态", children: "待加载" }, { key: "embedding", label: "Embedding", children: "待加载" }]} /><Empty description="等待接入商品详情接口" /></Card></div>;
}
