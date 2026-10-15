import { csrfToken } from "./auth.ts";
import { sendWithRefresh } from "./http.ts";

/** What a write answered: done, or refused with the HTTP status (400 bad input, 403, 404, 409 taken). */
export type WriteOutcome = { kind: "ok" } | { kind: "refused"; status: number };

async function write(method: "POST" | "PUT", path: string, body: unknown): Promise<WriteOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh(path, {
    method,
    headers: { "Content-Type": "application/json", ...(token === null ? {} : { "X-CSRF-Token": token }) },
    body: JSON.stringify(body),
  });
  return response.ok ? { kind: "ok" } : { kind: "refused", status: response.status };
}

/** Makes a center (owner only). [id] is the LOCATION NAME code, such as KRSNA. */
export const createCenter = (id: string, name: string, depotAddress: string): Promise<WriteOutcome> =>
  write("POST", "/api/centers", { id, name, depotAddress });

/** Changes a center's name and depot address (owner only). */
export const updateCenter = (id: string, name: string, depotAddress: string): Promise<WriteOutcome> =>
  write("PUT", `/api/centers/${encodeURIComponent(id)}`, { name, depotAddress });

/** Registers a driver of a center by DRIVER ID code (administrator or owner). */
export const createDriver = (centerId: string, driverId: string): Promise<WriteOutcome> =>
  write("POST", "/api/drivers", { centerId, driverId });

/** Makes a driver the zone's 담당 기사, or none with null (administrator or owner). */
export const setZoneDriver = (zoneId: string, driverId: string | null): Promise<WriteOutcome> =>
  write("PUT", `/api/zones/${encodeURIComponent(zoneId)}/driver`, { driverId });

/** A refusal in words for the person. */
export function refusalText(status: number, taken: string): string {
  if (status === 409) return taken;
  if (status === 400) return "입력 형식을 확인하세요.";
  if (status === 403) return "권한이 없습니다.";
  if (status === 404) return "대상을 찾지 못했습니다. 새로고침하세요.";
  return `저장하지 못했습니다 (HTTP ${status}).`;
}
