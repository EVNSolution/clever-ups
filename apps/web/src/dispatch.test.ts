import assert from "node:assert/strict";
import { test } from "node:test";
import { planByZone } from "./dispatch.ts";

test("each order goes to the driver of its zone; the rest stay unassigned or idle", () => {
  const orders = [
    { id: "o1", driverId: "d1" },
    { id: "o2", driverId: "d2" },
    { id: "o3", driverId: "d2" },
    { id: "o4", driverId: null },
    { id: "o5", driverId: "gone" },
  ];
  const drivers = [
    { id: "d1", zoneIds: ["z1"] },
    { id: "d2", zoneIds: ["z2", "z3"] },
    { id: "d3", zoneIds: [] },
  ];
  const plan = planByZone(orders, drivers);
  assert.deepEqual(
    plan.routes.map(route => [route.id, route.orders.map(order => order.id)]),
    [
      ["d2", ["o2", "o3"]],
      ["d1", ["o1"]],
    ],
  );
  assert.deepEqual(plan.routes[0]?.zoneIds, ["z2", "z3"]);
  assert.deepEqual(plan.unassigned.map(order => order.id), ["o4", "o5"]);
  assert.deepEqual(plan.idleDriverIds, ["d3"]);
});
