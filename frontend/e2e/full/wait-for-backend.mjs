const url = process.env.E2E_API_BASE
  ? `${process.env.E2E_API_BASE}/actuator/health/readiness`
  : "http://localhost:8080/actuator/health/readiness";

const deadline = Date.now() + 60_000;

while (Date.now() < deadline) {
  try {
    const r = await fetch(url);
    if (r.ok) {
      console.log(`backend ready at ${url}`);
      process.exit(0);
    }
  } catch {
    // not ready yet
  }
  await new Promise((res) => setTimeout(res, 1000));
}
console.error(`timed out waiting for backend at ${url}`);
process.exit(1);
