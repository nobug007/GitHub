import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadEnvFile } from "./env.js";

const serverDir = fileURLToPath(new URL(".", import.meta.url));
await loadEnvFile(join(serverDir, "..", ".env.ai"));
const { createApp } = await import("./app.js");
const port = Number(process.env.PORT || 8787);
createApp().listen(port, "127.0.0.1", () => {
  console.log(`MAG harness API: http://127.0.0.1:${port}`);
});
