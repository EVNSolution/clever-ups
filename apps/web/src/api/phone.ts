import { csrfToken } from "./auth.ts";
import { ApiError, getJson, sendWithRefresh } from "./http.ts";
import type { components } from "./schema";

export type PhoneStatus = components["schemas"]["PhoneStatus"];

export type SendOutcome =
  | { kind: "ok"; reference: string }
  | { kind: "denied"; reason: "daily" | "account" | "cooldown" | "other" }
  | { kind: "handled" }
  | { kind: "uncertain" }
  | { kind: "off" }
  | { kind: "invalid" };

const isStatus = (value: unknown): value is PhoneStatus =>
  typeof value === "object" &&
  value !== null &&
  "configured" in value &&
  typeof value.configured === "boolean" &&
  "verified" in value &&
  typeof value.verified === "boolean";

export async function getPhoneStatus(): Promise<PhoneStatus> {
  const { body } = await getJson("/api/phone");
  if (!isStatus(body)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return body;
}

async function post(path: string, body: unknown): Promise<Response> {
  const token = csrfToken();
  return sendWithRefresh(path, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    body: JSON.stringify(body),
  });
}

async function detailOf(response: Response): Promise<string> {
  const problem: unknown = await response.json().catch(() => null);
  return typeof problem === "object" && problem !== null && "detail" in problem && typeof problem.detail === "string"
    ? problem.detail
    : "";
}

/**
 * Asks the server to text a code to the number on the person's own request. This is the only call that can
 * send a message, and only the person's own click may make it. [requestKey] is one id per click.
 */
export async function sendPhoneCode(requestKey: string, challenge: string): Promise<SendOutcome> {
  return readSendOutcome(await post("/api/phone/send", { requestKey, challenge }));
}

async function readSendOutcome(response: Response): Promise<SendOutcome> {
  if (response.status === 200) {
    const body: unknown = await response.json().catch(() => null);
    if (typeof body === "object" && body !== null && "reference" in body && typeof body.reference === "string") {
      return { kind: "ok", reference: body.reference };
    }
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  if (response.status === 429) {
    const detail = await detailOf(response);
    if (detail.endsWith("DAILY_LIMIT")) return { kind: "denied", reason: "daily" };
    if (detail.endsWith("ACCOUNT_LIMIT")) return { kind: "denied", reason: "account" };
    if (detail.endsWith("COOLDOWN")) return { kind: "denied", reason: "cooldown" };
    return { kind: "denied", reason: "other" };
  }
  if (response.status === 409) return { kind: "handled" };
  if (response.status === 502) return { kind: "uncertain" };
  if (response.status === 503) return { kind: "off" };
  if (response.status === 400 || response.status === 404) return { kind: "invalid" };
  throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}

/** Hands the server the Firebase proof. True when the open request is now marked as verified. */
export async function verifyPhone(idToken: string): Promise<boolean> {
  const response = await post("/api/phone/verify", { idToken });
  if (response.status === 204) return true;
  if (response.status === 400 || response.status === 404) return false;
  throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}

export type PublicPhoneConfig = components["schemas"]["PublicPhoneConfig"];

/** What the sign-up form needs before anyone is signed in: whether to prove the number, and the web settings. */
export async function getPublicPhone(): Promise<PublicPhoneConfig> {
  const { body } = await getJson("/api/auth/phone");
  if (typeof body !== "object" || body === null || !("configured" in body) || typeof body.configured !== "boolean") {
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  return body as PublicPhoneConfig;
}

/** Texts a code to the number a person is about to give. Only the person's own click may call this. */
export async function sendSignUpCode(
  phone: string,
  requestKey: string,
  challenge: string,
  inviteCode: string,
): Promise<SendOutcome> {
  const response = await sendWithRefresh("/api/auth/phone/send", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ phone, requestKey, challenge, inviteCode }),
  });
  return readSendOutcome(response);
}

const DENIED = {
  daily: "오늘 보낼 수 있는 인증 문자가 모두 사용되었습니다. 내일 다시 시도하세요.",
  account: "이 계정이 받을 수 있는 인증 문자 횟수를 넘었습니다.",
  cooldown: "방금 보냈습니다. 1분 뒤에 다시 받을 수 있습니다.",
  other: "지금은 인증 문자를 보낼 수 없습니다. 가입 코드를 여러 번 잘못 입력했다면 잠시 뒤에 다시 시도하세요.",
} as const;

/** The sentence for a send that did not go through, or null when it did. */
export function sendProblem(outcome: SendOutcome): string | null {
  if (outcome.kind === "ok") return null;
  if (outcome.kind === "denied") return DENIED[outcome.reason];
  if (outcome.kind === "uncertain") return "문자가 갔는지 확인할 수 없습니다. 잠시 뒤에 다시 받으세요.";
  if (outcome.kind === "handled") return "같은 요청이 이미 처리되었습니다. 다시 눌러 주세요.";
  if (outcome.kind === "off") return "이 서버에서는 휴대폰 인증을 쓸 수 없습니다.";
  return "인증번호를 보내지 못했습니다. 가입 코드와 번호를 확인하세요.";
}
