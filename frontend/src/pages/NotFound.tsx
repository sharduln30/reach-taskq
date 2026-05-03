import { Link } from "react-router-dom";

export default function NotFound() {
  return (
    <div data-testid="page-not-found" className="text-center py-16">
      <h1 className="text-5xl font-bold mb-2">404</h1>
      <p className="text-muted-foreground mb-6">This page does not exist.</p>
      <Link to="/" className="text-sm px-4 py-2 rounded-md bg-primary text-primary-foreground">
        Back to overview
      </Link>
    </div>
  );
}
