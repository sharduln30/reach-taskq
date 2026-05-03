import { Route, Routes } from "react-router-dom";
import AppShell from "@/components/layout/AppShell";
import Overview from "@/pages/Overview";
import Jobs from "@/pages/Jobs";
import JobDetail from "@/pages/JobDetail";
import SubmitJob from "@/pages/SubmitJob";
import DLQ from "@/pages/DLQ";
import Tenants from "@/pages/Tenants";
import Workers from "@/pages/Workers";
import NotFound from "@/pages/NotFound";

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Overview />} />
        <Route path="jobs" element={<Jobs />} />
        <Route path="jobs/new" element={<SubmitJob />} />
        <Route path="jobs/:id" element={<JobDetail />} />
        <Route path="dlq" element={<DLQ />} />
        <Route path="tenants" element={<Tenants />} />
        <Route path="workers" element={<Workers />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
