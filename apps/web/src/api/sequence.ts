import { csrfToken } from "./auth.ts";
import { ApiError, sendWithRefresh } from "./http.ts";
import type { components, paths } from "./schema";

type Post = paths["/api/stop-sequence"]["post"];
export type SequenceRequest = NonNullable<Post["requestBody"]>["content"]["application/json"];
export type Sequence = Post["responses"]["200"]["content"]["application/json"];
export type Point = components["schemas"]["Point"];

/** The route engine's answer, or `unavailable` when the server has no engine or it did not answer (503). */
export type SequenceOutcome = { kind: "ok"; sequence: Sequence } | { kind: "unavailable" };

/** Asks the server to put one driver's stops in visiting order. Nothing is saved. */
export async function sequenceStops(request: SequenceRequest): Promise<SequenceOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh("/api/stop-sequence", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    body: JSON.stringify(request),
  });
  if (response.status === 503) return { kind: "unavailable" };
  if (!response.ok) throw new ApiError("http", `서버가 오류를 반환했습니다 (HTTP ${response.status}).`, response.status);
  const body: unknown = await response.json().catch(() => null);
  if (typeof body !== "object" || body === null || !("stops" in body) || !Array.isArray(body.stops)) {
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  return { kind: "ok", sequence: body as Sequence };
}
