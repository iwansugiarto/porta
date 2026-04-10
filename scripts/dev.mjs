import path from "node:path";
import {
  commandName,
  ensureLogsDir,
  loadEnvFile,
  spawnLoggedProcess,
  terminateChild,
  waitForExit,
} from "./common.mjs";

// Load .env so we can check TELEGRAM_BOT_TOKEN
loadEnvFile();

const logsDir = ensureLogsDir();
const runners = [
  spawnLoggedProcess(
    "proxy",
    commandName("pnpm"),
    ["--filter", "@porta/proxy", "dev"],
    path.join(logsDir, "proxy.log"),
  ),
  spawnLoggedProcess(
    "web",
    commandName("pnpm"),
    ["--filter", "@porta/web", "dev"],
    path.join(logsDir, "web.log"),
  ),
];

// Conditionally start Telegram bot if configured
const labels = ["proxy", "web"];
if (process.env.TELEGRAM_BOT_TOKEN) {
  runners.push(
    spawnLoggedProcess(
      "telegram",
      commandName("pnpm"),
      ["--filter", "@porta/telegram", "dev"],
      path.join(logsDir, "telegram.log"),
    ),
  );
  labels.push("telegram");
  console.log("✓ Porta dev - tail logs/proxy.log, logs/web.log, and logs/telegram.log");
} else {
  console.log("✓ Porta dev - tail logs/proxy.log and logs/web.log");
}

let shuttingDown = false;

async function shutdown(code = 0) {
  if (shuttingDown) return;
  shuttingDown = true;

  await Promise.all(runners.map(({ child }) => terminateChild(child)));
  await Promise.all(runners.map(({ logStream }) => new Promise((resolve) => {
    logStream.end(resolve);
  })));
  process.exit(code);
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    void shutdown(0);
  });
}

const exits = runners.map(async ({ child }, index) => ({
  index,
  ...(await waitForExit(child)),
}));

const firstExit = await Promise.race(exits);
if (!shuttingDown) {
  const label = labels[firstExit.index] ?? "unknown";
  const code = typeof firstExit.code === "number" ? firstExit.code : 1;
  console.error(`${label} exited early`);
  await shutdown(code);
}
