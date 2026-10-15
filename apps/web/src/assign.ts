import type { ZoneRow } from "./zones.ts";

/** What one click assigns: a whole 권역, or a 세부권역. */
export type UnitLevel = "zone" | "sub";

/**
 * Changes a person made to the day's dispatch, over the default (the zone's driver). `units` is keyed by zone
 * name, `orders` by order ID; null means "no driver" on purpose. An order's own entry wins, then its 세부권역,
 * then its 권역. The drivers' 담당 권역 never change from here.
 */
export interface Assignment {
  units: Readonly<Record<string, string | null>>;
  orders: Readonly<Record<string, string | null>>;
}

export const NO_CHANGES: Assignment = { units: {}, orders: {} };

interface OrderIn {
  id: string;
  zoneId: string | null;
  driverId: string | null;
}

const has = (record: Readonly<Record<string, unknown>>, key: string | null): key is string =>
  key !== null && Object.hasOwn(record, key);

/** The 권역 of a zone: itself, or the 권역 above a 세부권역. */
export function topOf(zoneName: string | null, rows: readonly ZoneRow[]): string | null {
  if (zoneName === null) return null;
  return rows.find(row => row.id === zoneName)?.parentName ?? zoneName;
}

/** The unit an order is assigned with at this level. */
export const unitKeyOf = (order: OrderIn, rows: readonly ZoneRow[], level: UnitLevel): string | null =>
  level === "sub" ? order.zoneId : topOf(order.zoneId, rows);

/**
 * The units shown at a level: every 권역; or every 권역 (for its orders outside any 세부권역) and then every
 * 세부권역, which the map draws on top so a click inside one picks it.
 */
export function unitsOf(rows: readonly ZoneRow[], level: UnitLevel): ZoneRow[] {
  const tops = rows.filter(row => row.parentName === null);
  return level === "zone" ? tops : [...tops, ...rows.filter(row => row.parentName !== null)];
}

/** The driver an order goes to after the changes. */
export function driverOf(order: OrderIn, rows: readonly ZoneRow[], changes: Assignment): string | null {
  if (has(changes.orders, order.id)) return changes.orders[order.id] ?? null;
  if (has(changes.units, order.zoneId)) return changes.units[order.zoneId] ?? null;
  const top = topOf(order.zoneId, rows);
  if (has(changes.units, top)) return changes.units[top] ?? null;
  return order.driverId;
}

/**
 * Gives whole units to a driver. Everything inside them follows: changes made to their 세부권역 and to their
 * single orders before are dropped.
 */
export function assignUnits(
  changes: Assignment,
  keys: readonly string[],
  driverId: string | null,
  orders: readonly OrderIn[],
  rows: readonly ZoneRow[],
): Assignment {
  const chosen = new Set(keys);
  const inside = (zoneName: string | null): boolean => zoneName !== null && (chosen.has(zoneName) || chosen.has(topOf(zoneName, rows) ?? ""));
  const units = Object.fromEntries(Object.entries(changes.units).filter(([name]) => !inside(name) || chosen.has(name)));
  for (const key of keys) units[key] = driverId;
  const ordersLeft = Object.fromEntries(
    Object.entries(changes.orders).filter(([id]) => !inside(orders.find(order => order.id === id)?.zoneId ?? null)),
  );
  return { units, orders: ordersLeft };
}

/** Moves one order to another driver. */
export const assignOrder = (changes: Assignment, orderId: string, driverId: string | null): Assignment => ({
  units: changes.units,
  orders: { ...changes.orders, [orderId]: driverId },
});

/** A saved run of the day as changes: every order it holds keeps the driver it was saved with. */
export function fromRun(run: { routes: readonly { driverId: string; stops: readonly { orderId: string }[] }[]; unassigned: readonly string[] }): Assignment {
  const orders: Record<string, string | null> = {};
  for (const route of run.routes) for (const stop of route.stops) orders[stop.orderId] = route.driverId;
  for (const id of run.unassigned) orders[id] = null;
  return { units: {}, orders };
}
