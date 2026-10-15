import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { forgetCsrf, rememberCsrf } from "./auth.ts";
import { settingsOf } from "./firebasePhone.ts";
import { getPhoneStatus, sendPhoneCode, verifyPhone } from "./phone.ts";

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
  rememberCsrf(new Headers({ "X-CSRF-Token": "csrf-phone" }));
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

test("the status is read and its shape is checked", async () => {
  answers = [
    new Response(JSON.stringify({ configured: true, verified: false, phoneHint: "010-****-5678", projectId: "p", apiKey: "k" }), {
      status: 200,
    }),
    new Response(JSON.stringify({ nope: 1 }), { status: 200 }),
  ];
  const status = await getPhoneStatus();
  assert.equal(status.phoneHint, "010-****-5678");
  assert.deepEqual(settingsOf(status), { apiKey: "k", projectId: "p" });
  await assert.rejects(getPhoneStatus(), /계약/);
});

test("without settings from the server there is nothing to start", () => {
  assert.equal(settingsOf({ configured: false, verified: false }), null);
});

test("a send posts the key and the challenge with the csrf token, and the answers are told apart", async () => {
  answers = [
    new Response(JSON.stringify({ reference: "ref-1" }), { status: 200 }),
    new Response(JSON.stringify({ detail: "send-denied:DAILY_LIMIT" }), { status: 429 }),
    new Response(JSON.stringify({ detail: "send-denied:COOLDOWN" }), { status: 429 }),
    new Response(null, { status: 409 }),
    new Response(null, { status: 502 }),
    new Response(null, { status: 503 }),
    new Response(null, { status: 400 }),
  ];
  assert.deepEqual(await sendPhoneCode("key-1", "captcha"), { kind: "ok", reference: "ref-1" });
  assert.deepEqual(await sendPhoneCode("key-2", "captcha"), { kind: "denied", reason: "daily" });
  assert.deepEqual(await sendPhoneCode("key-3", "captcha"), { kind: "denied", reason: "cooldown" });
  assert.deepEqual(await sendPhoneCode("key-4", "captcha"), { kind: "handled" });
  assert.deepEqual(await sendPhoneCode("key-5", "captcha"), { kind: "uncertain" });
  assert.deepEqual(await sendPhoneCode("key-6", "captcha"), { kind: "off" });
  assert.deepEqual(await sendPhoneCode("key-7", "captcha"), { kind: "invalid" });
  assert.equal(seen[0]?.path, "/api/phone/send");
  assert.equal(seen[0]?.method, "POST");
  assert.equal(seen[0]?.headers.get("X-CSRF-Token"), "csrf-phone");
  assert.deepEqual(JSON.parse(seen[0]?.body ?? "{}"), { requestKey: "key-1", challenge: "captcha" });
});

test("a proof is accepted with 204 and refused with 400", async () => {
  answers = [new Response(null, { status: 204 }), new Response(null, { status: 400 })];
  assert.equal(await verifyPhone("token"), true);
  assert.equal(await verifyPhone("token"), false);
  assert.equal(seen[0]?.path, "/api/phone/verify");
});
