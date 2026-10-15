import { csrfToken } from "./auth.ts";
import { ApiError, getJson, sendWithRefresh, type Loaded } from "./http.ts";
import type { components } from "./schema";

export type AccessRequest = components["schemas"]["AccessRequest"];
export type Member = components["schemas"]["Member"];
export type MemberRow = Member & { id: string };
export type GrantRole = "admin" | "member" | "viewer";

export interface SignUpInput {
  loginId: string;
  password: string;
  fullName: string;
  phone: string;
  /** The Firebase ID token for the number, when the server asks for one. Empty otherwise. */
  phoneProof: string;
  /** The sign-up code from the 개발 관리자, when the server asks for one. */
  inviteCode: string;
}

export type SignUpOutcome = { kind: "ok" } | { kind: "taken" } | { kind: "closed" } | { kind: "invalid"; detail: string };

const LOGIN_ID = /^[A-Za-z0-9._-]{4,32}$/;
const PHONE = /^(\+82|0)1[0-9]{8,9}$/;

/** `010-1234-5678` → `+821012345678`. Anything else is returned without hyphens and spaces. */
export function toE164(text: string): string {
  const compact = text.replace(/[-\s]/g, "");
  return compact.startsWith("0") ? `+82${compact.slice(1)}` : compact;
}

/** Digits only, grouped as the person types: 010-1234-5678. A leading +82 is kept as it is. */
export function formatPhone(text: string): string {
  if (text.trim().startsWith("+")) return text.replace(/[^0-9+]/g, "").slice(0, 14);
  const digits = text.replace(/[^0-9]/g, "").slice(0, 11);
  if (digits.length <= 3) return digits;
  if (digits.length <= 7) return `${digits.slice(0, 3)}-${digits.slice(3)}`;
  return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`;
}

/**
 * The same rules as the server, in Korean, so a typo is caught before a request. The server checks again:
 * this is a convenience, not the guard.
 */
export interface AccessProfileInput {
  fullName: string;
  phone: string;
}

export type ProfileField = "fullName" | "phone";
export type SignUpField = "inviteCode" | "loginId" | "password" | "passwordConfirm" | ProfileField;
export type Problems = Partial<Record<SignUpField, string>>;

/** What is wrong with each field of an access request, by field. Shared by sign-up and by a later request. */
export function profileProblems(input: AccessProfileInput): Problems {
  const problems: Problems = {};
  const phone = input.phone.replace(/[-\s]/g, "");
  const name = input.fullName.trim();
  if (name === "" || name.length > 50) problems.fullName = "이름은 1~50자로 입력하세요.";
  if (!PHONE.test(phone)) problems.phone = "";
  return problems;
}

const firstOf = (problems: Problems): string | null => Object.values(problems)[0] ?? null;

export function validateProfile(input: AccessProfileInput): string | null {
  return firstOf(profileProblems(input));
}

function passwordProblem(password: string, loginId: string): string | undefined {
  if (password.length < 8 || password.length > 128) return "비밀번호는 8자 이상 128자 이하입니다.";
  if (!/[A-Z]/.test(password)) return "비밀번호에 영문 대문자가 필요합니다.";
  if (!/[0-9]/.test(password)) return "비밀번호에 숫자가 필요합니다.";
  if (/^[A-Za-z0-9]*$/.test(password)) return "비밀번호에 특수문자가 필요합니다.";
  if (loginId !== "" && password.toLowerCase().includes(loginId.toLowerCase())) return "비밀번호에 로그인 ID를 넣을 수 없습니다.";
  return undefined;
}

/** Every unmet rule of the sign-up form, by field, in the order the form shows them. */
export function signUpProblems(
  input: SignUpInput,
  passwordConfirm: string,
  phoneRequired = false,
  codeRequired = false,
): Problems {
  const problems: Problems = {};
  if (codeRequired && input.inviteCode.trim() === "") problems.inviteCode = "가입 코드를 입력하세요.";
  if (!LOGIN_ID.test(input.loginId)) problems.loginId = "로그인 ID는 4~32자의 영문, 숫자, 특수문자(. - _)만 쓸 수 있습니다.";
  const password = passwordProblem(input.password, input.loginId);
  if (password !== undefined) problems.password = password;
  if (passwordConfirm !== input.password) problems.passwordConfirm = "비밀번호가 일치하지 않습니다.";
  const rest = profileProblems(input);
  if (rest.phone === undefined && phoneRequired && input.phoneProof === "") rest.phone = "휴대폰 인증을 완료하세요.";
  return { ...problems, ...rest };
}

export async function getSignUpOptions(): Promise<{ codeRequired: boolean }> {
  const { body } = await getJson("/api/auth/signup");
  if (typeof body !== "object" || body === null || !("codeRequired" in body) || typeof body.codeRequired !== "boolean") {
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  return { codeRequired: body.codeRequired };
}

export function validateSignUp(input: SignUpInput): string | null {
  return firstOf(signUpProblems(input, input.password));
}

export async function signUp(input: SignUpInput): Promise<SignUpOutcome> {
  const response = await sendWithRefresh("/api/auth/signup", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 201) return { kind: "ok" };
  if (response.status === 409) return { kind: "taken" };
  if (response.status === 429) return { kind: "closed" };
  if (response.status === 400) {
    const problem: unknown = await response.json().catch(() => null);
    const detail =
      typeof problem === "object" && problem !== null && "detail" in problem && typeof problem.detail === "string"
        ? problem.detail
        : "";
    return { kind: "invalid", detail };
  }
  throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}

const isRequest = (value: unknown): value is AccessRequest =>
  typeof value === "object" &&
  value !== null &&
  "requestId" in value &&
  typeof value.requestId === "string" &&
  "loginId" in value &&
  typeof value.loginId === "string";

/** The requests waiting for the owner. Only the owner may ask; anyone else gets a 403. */
export async function listAccessRequests(): Promise<Loaded<AccessRequest & { id: string }>> {
  const { body } = await getJson("/api/access-requests");
  if (!Array.isArray(body) || !body.every(isRequest)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { items: body.map(item => ({ ...item, id: item.requestId })), incomplete: false };
}

async function decide(path: string, body: unknown): Promise<void> {
  const token = csrfToken();
  const response = await sendWithRefresh(path, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    body: JSON.stringify(body ?? {}),
  });
  if (response.status === 409) throw new ApiError("http", "이미 처리되었거나 신청 기간이 지났습니다.", 409);
  if (!response.ok) throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}

/** The owner names the centers. An empty list is refused by the server when the person asked for none. */
export const approveRequest = (requestId: string, role: GrantRole, centerCodes: string[]): Promise<void> =>
  decide(`/api/access-requests/${encodeURIComponent(requestId)}/approve`, { role, centerCodes });

export const rejectRequest = (requestId: string): Promise<void> =>
  decide(`/api/access-requests/${encodeURIComponent(requestId)}/reject`, undefined);

export type RequestOutcome = { kind: "ok" } | { kind: "waiting" } | { kind: "closed" } | { kind: "invalid"; detail: string };

/**
 * A signed-in account without access asks again. It needs the session and the CSRF token. It gives no access:
 * the owner decides. A 409 means a request already waits or the account already has access.
 */
export async function requestAccess(input: AccessProfileInput): Promise<RequestOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh("/api/access-requests", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    body: JSON.stringify(input),
  });
  if (response.status === 201) return { kind: "ok" };
  if (response.status === 409) return { kind: "waiting" };
  if (response.status === 429) return { kind: "closed" };
  if (response.status === 400) {
    const problem: unknown = await response.json().catch(() => null);
    const detail =
      typeof problem === "object" && problem !== null && "detail" in problem && typeof problem.detail === "string"
        ? problem.detail
        : "";
    return { kind: "invalid", detail };
  }
  throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}

const isMember = (value: unknown): value is Member =>
  typeof value === "object" &&
  value !== null &&
  "accountId" in value &&
  typeof value.accountId === "string" &&
  "loginId" in value &&
  typeof value.loginId === "string" &&
  "centerCodes" in value &&
  Array.isArray(value.centerCodes);

/** The accounts with an active membership. Only the owner may ask. */
export async function listMembers(): Promise<Loaded<MemberRow>> {
  const { body } = await getJson("/api/memberships");
  if (!Array.isArray(body) || !body.every(isMember)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { items: body.map(item => ({ ...item, id: item.accountId })), incomplete: false };
}

/** Ends an active membership. The data closes at the account's next request. */
/** Ties an active account to a driver of a center: its role becomes driver and it sees only its own route. */
export const linkDriver = (accountId: string, centerId: string, driverId: string): Promise<void> =>
  decide(`/api/memberships/${encodeURIComponent(accountId)}/driver`, { centerId, driverId });

export const revokeMember = (accountId: string): Promise<void> =>
  decide(`/api/memberships/${encodeURIComponent(accountId)}/revoke`, undefined);

