import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { forgetCsrf, rememberCsrf } from "./auth.ts";
import { createInvite, redeemInvite } from "./invites.ts";

interface Seen {
  path: string;
  method: string;
  headers: Headers;
  body: string | null;
}

const realFetch = globalThis.fetch;
let seen: Seen[] = [];
let answers: Response[] = [];

beforeEach(() => {
  seen = [];
  answers = [];
  forgetCsrf();
  rememberCsrf(new Headers({ "X-CSRF-Token": "csrf-invite" }));
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    seen.push({
      path: String(input),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : null,
    });
    const next = answers.shift();
    if (next === undefined) throw new Error("unexpected request");
    return next;
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

test("a code is made with the csrf token and read back once", async () => {
  answers = [
    new Response(JSON.stringify({ code: "ABCD-EFGH-JKMN", centerCodes: ["KRSNA", "KRHWN"], role: "member", expiresAt: "2026-10-19T00:00:00Z" }), {
      status: 201,
    }),
    new Response(null, { status: 400 }),
  ];
  const made = await createInvite(["KRSNA", "KRHWN"], "member");
  assert.equal(made.kind, "ok");
  assert.equal(made.kind === "ok" ? made.code : "", "ABCD-EFGH-JKMN");
  assert.equal(seen[0]?.path, "/api/center-invites");
  assert.equal(seen[0]?.headers.get("X-CSRF-Token"), "csrf-invite");
  assert.deepEqual(JSON.parse(seen[0]?.body ?? "{}"), { centerCodes: ["KRSNA", "KRHWN"], role: "member" });
  assert.deepEqual(await createInvite(["x"], "member"), { kind: "invalid" });
});

test("a code entry tells apart done, wrong, owner and locked", async () => {
  answers = [
    new Response(JSON.stringify({ centerCodes: ["KRSNA"], role: "viewer" }), { status: 200 }),
    new Response(JSON.stringify({ centerCodes: ["KRHWN"] }), { status: 200 }),
    new Response(null, { status: 400 }),
    new Response(null, { status: 409 }),
    new Response(null, { status: 429, headers: { "Retry-After": "540" } }),
  ];
  assert.deepEqual(await redeemInvite("abcd"), { kind: "ok", centerCodes: ["KRSNA"], role: "viewer" });
  assert.deepEqual(await redeemInvite("abcd"), { kind: "ok", centerCodes: ["KRHWN"], role: null });
  assert.deepEqual(await redeemInvite("abcd"), { kind: "wrong" });
  assert.deepEqual(await redeemInvite("abcd"), { kind: "owner" });
  assert.deepEqual(await redeemInvite("abcd"), { kind: "locked", retryAfterSeconds: 540 });
  assert.equal(seen[0]?.path, "/api/center-invites/redeem");
});
