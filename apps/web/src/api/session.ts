import { csrfToken, forgetCsrf } from "./auth.ts";
import { ApiError, getJson, sendWithRefresh, type Loaded } from "./http.ts";
import { hasId } from "./resources.ts";
import type { components } from "./schema";

export type Account = components["schemas"]["Account"];
export type Center = components["schemas"]["Center"];

function isAccount(value: unknown): value is Account {
  return (
    typeof value === "object" &&
    value !== null &&
    "id" in value &&
    typeof value.id === "string" &&
    "loginId" in value &&
    typeof value.loginId === "string" &&
    "role" in value &&
    (value.role === null || typeof value.role === "string") &&
    "membership" in value &&
    typeof value.membership === "string"
  );
}

function isCenter(value: unknown): value is Center {
  return hasId(value) && "name" in value && typeof value.name === "string";
}

/**
 * The signed-in account, or null when nobody is signed in. A 401 first tries to renew the session with the
 * refresh cookie, so a person who comes back within the refresh time is signed in again without a screen.
 * An account without an active membership is returned too: `membership` says why it sees no data.
 */
export async function getMe(): Promise<Account | null> {
  const { status, body } = await getJson("/api/me", [401]);
  if (status === 401) return null;
  if (!isAccount(body)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return body;
}

/** The centers the signed-in account can use. One account can use several, whatever its role. */
export async function listCenters(): Promise<Loaded<Center>> {
  const { body } = await getJson("/api/centers");
  if (!Array.isArray(body) || !body.every(isCenter)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { items: body as Center[], incomplete: false };
}

export type LoginOutcome =
  | { kind: "ok"; account: Account }
  | { kind: "invalid" }
  | { kind: "locked"; retryAfterSeconds: number };

/** Signs in. The server answers the same 401 for a wrong password and an unknown ID, and so does this. */
export async function login(loginId: string, password: string): Promise<LoginOutcome> {
  const response = await sendWithRefresh("/api/auth/login", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ loginId, password }),
  });
  if (response.status === 401) return { kind: "invalid" };
  if (response.status === 429) {
    const seconds = Number(response.headers.get("Retry-After"));
    return { kind: "locked", retryAfterSeconds: Number.isFinite(seconds) && seconds > 0 ? seconds : 900 };
  }
  if (!response.ok) throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
  const body: unknown = await response.json().catch(() => null);
  if (!isAccount(body)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { kind: "ok", account: body };
}

/** Ends the session on the server and forgets the token here. The page shows the sign-in screen next. */
export async function logout(): Promise<void> {
  try {
    const token = csrfToken();
    await sendWithRefresh("/api/auth/logout", { method: "POST", headers: token === null ? {} : { "X-CSRF-Token": token } });
  } finally {
    forgetCsrf();
  }
}
