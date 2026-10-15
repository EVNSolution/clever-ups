import { csrfToken } from "./auth.ts";
import { ApiError, getJson, sendWithRefresh } from "./http.ts";
import { hasId } from "./resources.ts";
import type { components } from "./schema";

export type Zone = components["schemas"]["Zone"];
export type Boundary = components["schemas"]["Boundary"];
export type BoundaryLevel = "SI" | "DONG";

/** The zones and 세부권역 of a center, with their shapes. */
export async function listZones(centerId: string): Promise<Zone[]> {
  const { body } = await getJson(`/api/zones?centerId=${encodeURIComponent(centerId)}`);
  if (!Array.isArray(body) || !body.every(hasId)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return body as Zone[];
}

/** Boundaries that touch the box: 시 for 권역, 행정동 for 세부권역. `incomplete` when the box held too many. */
export async function listBoundaries(
  level: BoundaryLevel,
  bbox: readonly [number, number, number, number],
): Promise<{ items: Boundary[]; incomplete: boolean }> {
  const box = bbox.map(value => value.toFixed(5)).join(",");
  const { body, headers } = await getJson(`/api/boundaries?level=${level}&bbox=${box}`);
  if (!Array.isArray(body)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { items: body as Boundary[], incomplete: headers.get("X-Data-Incomplete") === "true" };
}

export type ZoneOutcome = { kind: "ok"; zone: Zone } | { kind: "refused"; status: number };

async function send(method: "POST" | "PUT", path: string, body: unknown): Promise<ZoneOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh(path, {
    method,
    headers: { "Content-Type": "application/json", ...(token === null ? {} : { "X-CSRF-Token": token }) },
    body: JSON.stringify(body),
  });
  if (response.ok) return { kind: "ok", zone: (await response.json()) as Zone };
  return { kind: "refused", status: response.status };
}

/** Makes a zone, or a 세부권역 of [parentId]. 409 when the center has the name already. */
export const createZone = (centerId: string, name: string, parentId: string | null): Promise<ZoneOutcome> =>
  send("POST", "/api/zones", { centerId, name, parentId });

/** Sets the shape from boundaries. The server cuts it away from other zones and into its 권역. 409: nothing left. */
export const shapeZone = (zoneId: string, boundaryCodes: readonly string[]): Promise<ZoneOutcome> =>
  send("PUT", `/api/zones/${encodeURIComponent(zoneId)}/shape`, { boundaryCodes });

/** Sets the shape from a drawn ring of [lng, lat] corners. The server closes it and cuts it as for boundaries. */
export const drawZone = (zoneId: string, corners: readonly [number, number][]): Promise<ZoneOutcome> =>
  send("PUT", `/api/zones/${encodeURIComponent(zoneId)}/shape`, {
    polygon: { type: "Polygon", coordinates: [[...corners, corners[0]]] },
  });
