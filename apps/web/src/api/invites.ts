import { csrfToken } from "./auth.ts";
import { ApiError, sendWithRefresh } from "./http.ts";

export type InviteRole = "member" | "viewer";

export type CreateOutcome =
  | { kind: "ok"; code: string; centerCodes: string[]; role: string; expiresAt: string }
  | { kind: "invalid" };

export type RedeemOutcome =
  | { kind: "ok"; centerCodes: string[]; role: string | null }
  | { kind: "wrong" }
  | { kind: "locked"; retryAfterSeconds: number }
  | { kind: "owner" };

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

const isStrings = (value: unknown): value is string[] => Array.isArray(value) && value.every(v => typeof v === "string");

const isCreated = (value: unknown): value is { code: string; centerCodes: string[]; role: string; expiresAt: string } =>
  typeof value === "object" &&
  value !== null &&
  "code" in value &&
  typeof value.code === "string" &&
  "centerCodes" in value &&
  isStrings(value.centerCodes) &&
  "role" in value &&
  typeof value.role === "string" &&
  "expiresAt" in value &&
  typeof value.expiresAt === "string";

/** The owner makes a code for a center and a role. The code is in this answer only. */
export async function createInvite(centerCodes: string[], role: InviteRole): Promise<CreateOutcome> {
  const response = await post("/api/center-invites", { centerCodes, role });
  if (response.status === 201) {
    const body: unknown = await response.json().catch(() => null);
    if (isCreated(body)) return { kind: "ok", ...body };
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  if (response.status === 400) return { kind: "invalid" };
  throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}

/** A signed-in account types a code. Wrong, used and expired codes read the same. */
export async function redeemInvite(code: string): Promise<RedeemOutcome> {
  const response = await post("/api/center-invites/redeem", { code });
  if (response.status === 200) {
    const body: unknown = await response.json().catch(() => null);
    if (typeof body === "object" && body !== null && "centerCodes" in body && isStrings(body.centerCodes)) {
      const role = "role" in body && typeof body.role === "string" ? body.role : null;
      return { kind: "ok", centerCodes: body.centerCodes, role };
    }
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  if (response.status === 400) return { kind: "wrong" };
  if (response.status === 409) return { kind: "owner" };
  if (response.status === 429) {
    const seconds = Number(response.headers.get("Retry-After"));
    return { kind: "locked", retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : 900 };
  }
  throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
}
