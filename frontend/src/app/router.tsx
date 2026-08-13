import { Navigate, RouterProvider, createBrowserRouter } from "react-router-dom";
import { AppLayout } from "../layouts/AppLayout";
import { DashboardPage } from "../pages/dashboard/DashboardPage";
import { RunsPage } from "../pages/runs/RunsPage";
import { RunDetailPage } from "../pages/runs/RunDetailPage";
import RecommendationConsole from "../pages/recommendations/RecommendationConsole";
import { ProductsPage } from "../pages/products/ProductsPage";
import { ProductDetailPage } from "../pages/products/ProductDetailPage";
import { UsersPage } from "../pages/users/UsersPage";
import { OrdersPage } from "../pages/orders/OrdersPage";
import { EvaluationsPage } from "../pages/evaluations/EvaluationsPage";
import { SettingsPage } from "../pages/settings/SettingsPage";

const router = createBrowserRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: "dashboard", element: <DashboardPage /> },
      { path: "runs", element: <RunsPage /> },
      { path: "runs/:runId", element: <RunDetailPage /> },
      { path: "recommendations", element: <RecommendationConsole /> },
      { path: "products", element: <ProductsPage /> },
      { path: "products/:productId", element: <ProductDetailPage /> },
      { path: "users", element: <UsersPage /> },
      { path: "orders", element: <OrdersPage /> },
      { path: "evaluations", element: <EvaluationsPage /> },
      { path: "settings", element: <SettingsPage /> },
    ],
  },
]);

export function AppRouter() {
  return <RouterProvider router={router} />;
}
