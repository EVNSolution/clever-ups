import { refreshSession, rememberCsrf } from "./auth.ts";

export class ApiError extends Error {
  readonly kind: "network" | "http" | "shape" | "provider";
  readonly status: number | undefined;

  constructor(kind: "network" | "http" | "shape" | "provider", message: string, status?: number) {
    super(message);
    this.name = "ApiError";
    this.kind = kind;
    this.status = status;
  }
}

/** True for a 403: signed in, but the account may not see this. */
export const isForbidden = (error: unknown): boolean => error instanceof ApiError && error.status === 403;

/** Cause and next step for a failed request, in the words shown to the user. */
export function describeError(error: unknown): { cause: string; action: string } {
  if (error instanceof ApiError) {
    if (error.kind === "network") {
      return { cause: error.message, action: "네트워크와 API 서버가 실행 중인지 확인한 뒤 다시 시도하세요." };
    }
    if (error.kind === "provider") {
      return { cause: error.message, action: "잠시 뒤 다시 시도하세요. 계속되면 위 오류 코드를 개발자에게 알려 주세요." };
    }
    if (error.kind === "shape") {
      return { cause: error.message, action: "웹과 API 서버의 버전이 같은 계약인지 확인하세요." };
    }
    if (error.status === 401) {
      return { cause: "로그인이 필요합니다.", action: "로그인한 뒤 다시 시도하세요." };
    }
    if (error.status === 403) {
      return { cause: "이 계정은 이 정보를 볼 수 없습니다.", action: "개발 관리자에게 접근 권한을 문의하세요." };
    }
    if (error.status !== undefined && error.status >= 500) {
      return { cause: error.message, action: "잠시 후 다시 시도하세요. 계속되면 서버 담당자에게 알려 주세요." };
    }
    return { cause: error.message, action: "요청 권한과 주소를 확인하세요." };
  }
  return { cause: "알 수 없는 오류가 발생했습니다.", action: "다시 시도하세요." };
}

/** A loaded list. `incomplete` is true when the source may hold more than it handed over. */
export interface Loaded<T> {
  items: T[];
  incomplete: boolean;
}

/** Paths of the sign-in itself. A 401 there is an answer, not a reason to renew the session. */
const AUTH_PATH = "/api/auth/";

/**
 * Sends a request. When the access cookie has ended (401) it renews the session once, with the refresh cookie,
 * and sends the same request again, so the person does not see the renewal. A second 401 is returned as it is.
 */
export async function sendWithRefresh(path: string, init: RequestInit = {}): Promise<Response> {
  const options: RequestInit = { credentials: "same-origin", ...init };
  let response: Response;
  try {
    response = await fetch(path, options);
    if (response.status === 401 && !path.startsWith(AUTH_PATH) && (await refreshSession())) {
      response = await fetch(path, options);
    }
  } catch {
    throw new ApiError("network", "서버에 연결할 수 없습니다.");
  }
  rememberCsrf(response.headers);
  return response;
}

/**
 * GET a JSON resource. A status listed in `accept` is returned with a null body instead of throwing,
 * for answers such as 401 that the caller handles as a normal state.
 */
export async function getJson(
  path: string,
  accept: readonly number[] = [],
): Promise<{ status: number; body: unknown; headers: Headers }> {
  const response = await sendWithRefresh(path, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    if (accept.includes(response.status)) return { status: response.status, body: null, headers: response.headers };
    throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
  }
  try {
    return { status: response.status, body: await response.json(), headers: response.headers };
  } catch {
    throw new ApiError("shape", "응답을 읽을 수 없습니다.");
  }
}
