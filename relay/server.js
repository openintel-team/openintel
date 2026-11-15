const { createRelay } = require("./src/app");
async function main() {
  if (process.argv.length > 2) {
    console.error("Usage: node server.js (configure paths with environment variables; run npm test for self-tests)");
    process.exitCode = 1;
    return;
  }
  const relay = createRelay();
  const shutdown = () => relay.stop().catch(() => { console.error("Relay shutdown failed"); process.exitCode = 1; });
  process.once("SIGINT", shutdown);
  process.once("SIGTERM", shutdown);
  try { await relay.start(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
if (require.main === module) void main();
module.exports = { createRelay };
