/** One driver's share of the day: the zones given to the driver and the orders in them. */
export interface Route<O> {
  /** The driver ID. */
  id: string;
  zoneIds: readonly string[];
  orders: O[];
}

export interface Plan<O> {
  routes: Route<O>[];
  /** Orders no driver gets: no position yet, a zone without a driver, or a driver no longer listed. */
  unassigned: O[];
  /** Drivers with no order this day. */
  idleDriverIds: string[];
}

/**
 * Zone dispatch: an order goes to the driver of its zone. Nothing is ordered within a route yet; the sequence
 * and the times come with the route engine. Routes with the most orders come first.
 */
export function planByZone<O extends { driverId: string | null }>(
  orders: readonly O[],
  drivers: readonly { id: string; zoneIds: readonly string[] }[],
): Plan<O> {
  const known = new Set(drivers.map(driver => driver.id));
  const byDriver = new Map<string, O[]>();
  const unassigned: O[] = [];
  for (const order of orders) {
    if (order.driverId === null || !known.has(order.driverId)) {
      unassigned.push(order);
      continue;
    }
    const list = byDriver.get(order.driverId);
    if (list === undefined) byDriver.set(order.driverId, [order]);
    else list.push(order);
  }
  const routes: Route<O>[] = [];
  const idleDriverIds: string[] = [];
  for (const driver of drivers) {
    const mine = byDriver.get(driver.id);
    if (mine === undefined) idleDriverIds.push(driver.id);
    else routes.push({ id: driver.id, zoneIds: driver.zoneIds, orders: mine });
  }
  routes.sort((a, b) => b.orders.length - a.orders.length);
  return { routes, unassigned, idleDriverIds };
}
