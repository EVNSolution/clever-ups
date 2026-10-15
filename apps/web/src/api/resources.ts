import { today } from "./day.ts";
import { ApiError, getJson, type Loaded } from "./http.ts";
import type { components } from "./schema";

export type Driver = components["schemas"]["Driver"];
export type Order = components["schemas"]["Order"];

/** The one field every listed item must have, per the contract. */
export function hasId(value: unknown): value is { id: string } {
  return typeof value === "object" && value !== null && "id" in value && typeof value.id === "string";
}

async function list<T extends { id: string }>(path: string, centerId?: string): Promise<Loaded<T>> {
  const url = centerId === undefined ? path : `${path}?centerId=${encodeURIComponent(centerId)}`;
  const { body, headers } = await getJson(url);
  if (!Array.isArray(body) || !body.every(hasId)) throw new ApiError("shape", "응답 형식이 계약과 다릅니다.");
  return { items: body as T[], incomplete: headers.get("X-Data-Incomplete") === "true" };
}

/** Without a centerId the API returns every center the account can use. */
export const listDrivers = (centerId?: string): Promise<Loaded<Driver>> => list<Driver>("/api/drivers", centerId);

const PAST_DAY_MS = 10 * 60_000;
const TODAY_MS = 60_000;
const cache = new Map<string, { at: number; result: Promise<Loaded<Order>> }>();

/**
 * The orders of one operating day, of one center when [centerId] is given. A day read a moment ago is not
 * asked for again: going back to a day, or to a day that was read ahead, shows at once. Today is kept for a
 * minute only, since it still changes. The cache holds customer data, so it is emptied when the person signs out.
 */
export function listOrders(day: string, centerId?: string): Promise<Loaded<Order>> {
  const key = `${day}|${centerId ?? ""}`;
  const hit = cache.get(key);
  const keep = day < today() ? PAST_DAY_MS : TODAY_MS;
  if (hit !== undefined && Date.now() - hit.at < keep) return hit.result;
  const query = new URLSearchParams({ from: day, to: day });
  if (centerId !== undefined) query.set("centerId", centerId);
  const result = list<Order>(`/api/orders?${query.toString()}`);
  cache.set(key, { at: Date.now(), result });
  // A failed read must not be served again.
  result.catch(() => {
    if (cache.get(key)?.result === result) cache.delete(key);
  });
  return result;
}

/** Reads a day ahead of time, quietly. A failure is left for when the person opens that day. */
export function warmOrders(day: string, centerId?: string): void {
  listOrders(day, centerId).catch(() => undefined);
}

export function clearOrderCache(): void {
  cache.clear();
}
