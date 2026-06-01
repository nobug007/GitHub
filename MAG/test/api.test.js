import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../server/app.js";

test("health endpoint exposes provider and stage counts", async () => {
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();

  try {
    const response = await fetch(`http://127.0.0.1:${port}/api/health`);
    const data = await response.json();
    assert.equal(response.status, 200);
    assert.equal(data.ok, true);
    assert.equal(data.providers, 3);
    assert.equal(data.stages, 14);
  } finally {
    server.close();
  }
});

