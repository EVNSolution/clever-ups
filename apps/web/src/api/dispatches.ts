import { csrfToken } from "./auth.ts";
import { ApiError, getJson, sendWithRefresh, type Loaded } from "./http.ts";
import { hasId } from "./resources.ts";
import type { components, paths } from "./schema";

export type DispatchSummary = components["schemas"]["DispatchSummary"];
export type DispatchInput = NonNullable<paths["/api/dispatches"]["post"]["requestBody"]>["content"]["application/json"];

/** What came of a save. `refused` carries the status: 400 bad input, 403 not allowed, 503 no engine or database. */
export type SaveOutcome = { kind: "saved"; summary: DispatchSummary } | { kind: "refused"; status: number };

/** Saves a dispatch. The server orders each route with the route engine and keeps the result as computed. */
export async function saveDispatch(input: DispatchInput): Promise<SaveOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh("/api/dispatches", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    body: JSON.stringify(input),
  });
  if (!response.ok) return { kind: "refused", status: response.status };
  const body: unknown = await response.json().catch(() => null);
  if (!hasId(body)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { kind: "saved", summary: body as DispatchSummary };
}

/** The saved dispatches of one center and day, newest first. */
export async function listDispatches(centerId: string, day: string): Promise<Loaded<DispatchSummary>> {
  const { body } = await getJson(`/api/dispatches?centerId=${encodeURIComponent(centerId)}&day=${day}`);
  if (!Array.isArray(body) || !body.every(hasId)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { items: body as DispatchSummary[], incomplete: false };
}

export type DispatchDetail = components["schemas"]["DispatchDetail"];
export type SavedRoute = DispatchDetail["routes"][number];
export type DeliveryResult = components["schemas"]["DeliveryResult"];

/** One saved dispatch with its routes as they were computed, or null when there is none for this person. */
export async function getDispatch(id: string): Promise<DispatchDetail | null> {
  const { status, body } = await getJson(`/api/dispatches/${encodeURIComponent(id)}`, [404]);
  if (status === 404) return null;
  if (typeof body !== "object" || body === null || !("routes" in body) || !Array.isArray(body.routes)) {
    throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  }
  return body as DispatchDetail;
}

/** What came of sending or cancelling: the run after it, or the refusing status (403, 404, 409, 503). */
export type ChangeOutcome = { kind: "changed"; run: DispatchDetail } | { kind: "refused"; status: number };

/** Sends a draft (its routes become the drivers'), or cancels a draft or a sent run. */
export const changeDispatch = (id: string, action: "send" | "cancel"): Promise<ChangeOutcome> =>
  postChange(`/api/dispatches/${encodeURIComponent(id)}/${action}`, undefined);

/** Gives a draft's route to another driver of the center. */
export const changeRouteDriver = (id: string, from: string, to: string): Promise<ChangeOutcome> =>
  postChange(`${routePath(id, from)}/driver`, { driverId: to });

/** Adds unassigned orders of a draft to a route; the server orders the whole route again. */
export const addRouteOrders = (id: string, driverId: string, orderIds: string[]): Promise<ChangeOutcome> =>
  postChange(`${routePath(id, driverId)}/orders`, { orderIds });

const routePath = (id: string, driverId: string): string =>
  `/api/dispatches/${encodeURIComponent(id)}/routes/${encodeURIComponent(driverId)}`;

async function postChange(path: string, body: object | undefined): Promise<ChangeOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh(path, {
    method: "POST",
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) return { kind: "refused", status: response.status };
  const answer: unknown = await response.json().catch(() => null);
  if (typeof answer !== "object" || answer === null || !("routes" in answer)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { kind: "changed", run: answer as DispatchDetail };
}
