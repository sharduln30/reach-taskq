export type RouteSpec = {
  path: string;
  heading: string;
  navLabel: string;
};

export const ROUTES: RouteSpec[] = [
  { path: "/", heading: "Overview", navLabel: "Overview" },
  { path: "/jobs", heading: "Jobs", navLabel: "Jobs" },
  { path: "/jobs/new", heading: "Submit job", navLabel: "Submit" },
  { path: "/dlq", heading: "Dead-letter queue", navLabel: "DLQ" },
  { path: "/tenants", heading: "Tenant settings", navLabel: "Tenants" },
  { path: "/workers", heading: "Workers", navLabel: "Workers" },
];
