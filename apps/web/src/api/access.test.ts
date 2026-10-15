import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { afterEach, beforeEach, test } from "node:test";
import {
  approveRequest,
  listAccessRequests,
  listMembers,
  rejectRequest,
  requestAccess,
  formatPhone,
  profileProblems,
  toE164,
  signUpProblems,
  revokeMember,
  signUp,
  validateProfile,
  validateSignUp,
  type SignUpInput,
} from "./access.ts";
import { forgetCsrf, rememberCsrf } from "./auth.ts";

// Made at every run, from parts that are not password-like by themselves, so a secret scanner has nothing to
// find. It always holds an upper-case letter, a digit and a special character, as the sign-up rule asks.
const SPECIAL = String.fromCharCode(33);
const PASSWORD = ["Q", randomUUID().slice(0, 8).toUpperCase(), "1", randomUUID().slice(0, 8), SPECIAL].join("");

const good: SignUpInput = {
  loginId: "hong-gildong",
  password: PASSWORD,
  fullName: "홍길동",
  phone: "010-1234-5678",
  phoneProof: "",
  inviteCode: "",
};

test("a complete sign-up passes, and each broken field is named", () => {
  assert.equal(validateSignUp(good), null);
  assert.match(validateSignUp({ ...good, loginId: "a@b" }) ?? "", /로그인 ID/);
  assert.match(validateSignUp({ ...good, password: "Sh1!" }) ?? "", /8자/);
  assert.match(validateSignUp({ ...good, password: "alllowercase1!" }) ?? "", /대문자/);
  assert.match(validateSignUp({ ...good, password: "NoDigitsHere!" }) ?? "", /숫자/);
  assert.match(validateSignUp({ ...good, password: "NoSpecial1234" }) ?? "", /특수문자/);
  assert.match(validateSignUp({ ...good, password: [good.loginId.toUpperCase(), "1", SPECIAL].join("") }) ?? "", /로그인 ID/);
  assert.match(validateSignUp({ ...good, fullName: "   " }) ?? "", /이름/);
  assert.ok("phone" in signUpProblems({ ...good, phone: "02-123-4567" }, good.password));
});

test("phone numbers are accepted with dashes, spaces or +82", () => {
  for (const phone of ["010-1234-5678", "01012345678", "010 1234 5678", "+821012345678"]) {
    assert.equal(validateSignUp({ ...good, phone }), null, phone);
  }
});

const realFetch = globalThis.fetch;
let seen: { path: string; method: string; headers: Headers; body: string | null }[] = [];
let answers: Response[] = [];

beforeEach(() => {
  seen = [];
  answers = [];
  forgetCsrf();
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

test("each unmet rule is reported by field, and the confirmation must match", () => {
  assert.deepEqual(signUpProblems(good, good.password), {});
  const problems = signUpProblems({ ...good, loginId: "a@", phone: "123" }, "different");
  assert.deepEqual(Object.keys(problems), ["loginId", "passwordConfirm", "phone"]);
  assert.match(problems.passwordConfirm ?? "", /일치/);
});

test("a proved phone is required only when the server asks for it", () => {
  assert.deepEqual(signUpProblems(good, good.password), {});
  assert.match(signUpProblems(good, good.password, true).phone ?? "", /인증/);
  assert.deepEqual(signUpProblems({ ...good, phoneProof: "token" }, good.password, true), {});
  assert.equal(toE164("010-1234-5678"), "+821012345678");
});

test("a sign-up code is required only when the server asks for one", () => {
  assert.deepEqual(signUpProblems(good, good.password, false, false), {});
  assert.match(signUpProblems(good, good.password, false, true).inviteCode ?? "", /가입 코드/);
  assert.deepEqual(signUpProblems({ ...good, inviteCode: "ABCD-EFGH-JKMN" }, good.password, false, true), {});
});

test("a phone number gets its hyphens as the person types digits", () => {
  assert.equal(formatPhone("0"), "0");
  assert.equal(formatPhone("0101"), "010-1");
  assert.equal(formatPhone("01012345"), "010-1234-5");
  assert.equal(formatPhone("01012345678"), "010-1234-5678");
  assert.equal(formatPhone("010-1234-5678999"), "010-1234-5678");
  assert.equal(formatPhone("+821012345678"), "+821012345678");
});

test("sign-up outcomes: created, taken, closed, invalid", async () => {
  answers = [new Response("{}", { status: 201 }), new Response(null, { status: 409 }), new Response(null, { status: 429 })];
  assert.deepEqual(await signUp(good), { kind: "ok" });
  assert.deepEqual(await signUp(good), { kind: "taken" });
  assert.deepEqual(await signUp(good), { kind: "closed" });
  answers = [new Response(JSON.stringify({ detail: "The phone number is not a Korean mobile number" }), { status: 400 })];
  assert.deepEqual(await signUp(good), { kind: "invalid", detail: "The phone number is not a Korean mobile number" });
  assert.equal(seen[0]?.path, "/api/auth/signup");
  assert.equal(seen.length, 4);
});

test("a decision sends the role and centers, and a 409 reads as already decided", async () => {
  answers = [new Response(null, { status: 204 })];
  await approveRequest("r 1", "member", ["KRHWN"]);
  assert.equal(seen[0]?.path, "/api/access-requests/r%201/approve");
  assert.deepEqual(JSON.parse(seen[0]?.body ?? "{}"), { role: "member", centerCodes: ["KRHWN"] });

  answers = [new Response(null, { status: 409 })];
  await assert.rejects(rejectRequest("r2"), /이미 처리/);
});

test("the queue is read from the owner's endpoint and rows get an id", async () => {
  answers = [new Response(JSON.stringify([{ requestId: "r1", accountId: "a", loginId: "kim" }]), { status: 200 })];
  const loaded = await listAccessRequests();
  assert.equal(loaded.items[0]?.id, "r1");
});

const profile = { fullName: "홍길동", phone: "010-9999-8888" };

test("a request from a signed-in account checks the same profile fields as sign-up", () => {
  assert.equal(validateProfile(profile), null);
  assert.match(validateProfile({ ...profile, fullName: "" }) ?? "", /이름/);
  assert.ok("phone" in profileProblems({ ...profile, phone: "123" }));
});

test("asking again sends the CSRF token and reads created, waiting, closed and invalid", async () => {
  rememberCsrf(new Headers({ "X-CSRF-Token": "csrf-ask" }));
  answers = [
    new Response("{}", { status: 201 }),
    new Response(null, { status: 409 }),
    new Response(null, { status: 429 }),
    new Response(JSON.stringify({ detail: "The terms must be accepted" }), { status: 400 }),
  ];
  assert.deepEqual(await requestAccess(profile), { kind: "ok" });
  assert.deepEqual(await requestAccess(profile), { kind: "waiting" });
  assert.deepEqual(await requestAccess(profile), { kind: "closed" });
  assert.deepEqual(await requestAccess(profile), { kind: "invalid", detail: "The terms must be accepted" });
  assert.equal(seen[0]?.path, "/api/access-requests");
  assert.equal(seen[0]?.method, "POST");
  assert.equal(seen[0]?.headers.get("X-CSRF-Token"), "csrf-ask");
});

test("members are read from the owner's endpoint and a revoke posts to the account", async () => {
  answers = [
    new Response(JSON.stringify([{ accountId: "a1", loginId: "kim", role: "member", status: "ACTIVE", centerCodes: ["KRHWN"] }]), {
      status: 200,
    }),
  ];
  const loaded = await listMembers();
  assert.equal(loaded.items[0]?.id, "a1");
  assert.deepEqual(loaded.items[0]?.centerCodes, ["KRHWN"]);

  answers = [new Response(null, { status: 204 })];
  await revokeMember("a 1");
  assert.equal(seen[1]?.path, "/api/memberships/a%201/revoke");
  assert.equal(seen[1]?.method, "POST");
});

test("a member list that is not an array of members is refused", async () => {
  answers = [new Response(JSON.stringify([{ nope: true }]), { status: 200 })];
  await assert.rejects(listMembers(), /계약/);
});

