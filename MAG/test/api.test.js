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

test("serves client and API from relative subpaths", async () => {
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const base = `http://127.0.0.1:${port}`;

  try {
    const page = await fetch(`${base}/mag/`);
    const pageHtml = await page.text();
    assert.equal(page.status, 200);
    assert.match(pageHtml, /href="styles\.css"/);
    assert.match(pageHtml, /src="app\.js"/);

    const script = await fetch(`${base}/mag/app.js`);
    assert.equal(script.status, 200);
    assert.match(await script.text(), /relativeUrl/);

    const response = await fetch(`${base}/mag/api/health`);
    const data = await response.json();
    assert.equal(response.status, 200);
    assert.equal(data.ok, true);
  } finally {
    server.close();
  }
});
