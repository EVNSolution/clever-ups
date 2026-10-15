import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { csrfToken, forgetCsrf, refreshSession } from "./auth.ts";
import { getJson } from "./http.ts";
import { login, logout } from "./session.ts";

type Step = { status: number; body?: unknown; headers?: Record<string, string> };

const realFetch = globalThis.fetch;
let calls: { path: string; method: string; headers: Headers }[] = [];
let script: Step[] = [];

function respond(step: Step): Response {
  const body = step.body === undefined ? null : JSON.stringify(step.body);
  return new Response(step.status === 204 ? null : body, { status: step.status, headers: step.headers });
}

beforeEach(() => {
  calls = [];
  script = [];
  forgetCsrf();
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ path: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers) });
    const step = script.shift();
    if (step === undefined) throw new Error(`unexpected request ${String(input)}`);
    return respond(step);
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

test("a 401 renews the session once and repeats the request", async () => {
  script = [
    { status: 401 },
    { status: 204, headers: { "X-CSRF-Token": "csrf-2" } },
    { status: 200, body: [{ id: "a" }] },
  ];
  const result = await getJson("/api/orders");
  assert.deepEqual(result.body, [{ id: "a" }]);
  assert.deepEqual(calls.map(call => call.path), ["/api/orders", "/api/auth/refresh", "/api/orders"]);
  assert.equal(calls[1]?.method, "POST");
  assert.equal(calls[1]?.headers.get("X-UPS-Refresh"), "1");
  assert.equal(csrfToken(), "csrf-2");
});

test("when the renewal is refused the 401 is the answer, and nothing loops", async () => {
  script = [{ status: 401 }, { status: 401 }];
  const result = await getJson("/api/me", [401]);
  assert.equal(result.status, 401);
  assert.equal(calls.length, 2);
});

test("a request that is still 401 after a renewal is not renewed again", async () => {
  script = [{ status: 401 }, { status: 204 }, { status: 401 }];
  const result = await getJson("/api/me", [401]);
  assert.equal(result.status, 401);
  assert.equal(calls.length, 3);
});

test("calls made together share one renewal", async () => {
  let release: () => void = () => undefined;
  const gate = new Promise<void>(resolve => {
    release = resolve;
  });
  let refreshes = 0;
  globalThis.fetch = (async (input: string | URL | Request) => {
    if (String(input) === "/api/auth/refresh") {
      refreshes += 1;
      await gate;
      return respond({ status: 204 });
    }
    return respond({ status: 200, body: [] });
  }) as typeof fetch;
  const all = Promise.all([refreshSession(), refreshSession(), refreshSession()]);
  release();
  assert.deepEqual(await all, [true, true, true]);
  assert.equal(refreshes, 1);
});

test("the sign-in paths are never renewed", async () => {
  script = [{ status: 401 }];
  const outcome = await login("someone", "Wrong-Pw-1!");
  assert.deepEqual(outcome, { kind: "invalid" });
  assert.equal(calls.length, 1);
});

test("login says locked with the wait, and ok with the account", async () => {
  script = [{ status: 429, headers: { "Retry-After": "540" } }];
  assert.deepEqual(await login("someone", "x"), { kind: "locked", retryAfterSeconds: 540 });
  script = [
    {
      status: 200,
      body: { id: "1", loginId: "someone", role: null, membership: "none" },
      headers: { "X-CSRF-Token": "csrf-1" },
    },
  ];
  const outcome = await login("someone", "Right-Pw-1!");
  assert.equal(outcome.kind, "ok");
  assert.equal(csrfToken(), "csrf-1");
});

test("logout sends the CSRF token and forgets it", async () => {
  script = [{ status: 200, body: [], headers: { "X-CSRF-Token": "csrf-9" } }, { status: 204 }];
  await getJson("/api/drivers");
  await logout();
  assert.equal(calls[1]?.headers.get("X-CSRF-Token"), "csrf-9");
  assert.equal(csrfToken(), null);
});
