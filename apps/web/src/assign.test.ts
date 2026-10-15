import assert from "node:assert/strict";
import { test } from "node:test";
import { NO_CHANGES, assignOrder, assignUnits, driverOf, fromRun, unitKeyOf, unitsOf } from "./assign.ts";
import type { ZoneRow } from "./zones.ts";

const row = (id: string, parentName: string | null, driverId: string | null): ZoneRow => ({
  id,
  zoneId: id,
  parentName,
  shape: null,
  orders: 0,
  driverId,
});
const rows = [row("분당", null, "7001"), row("정자1", "분당", "7002"), row("수정", null, null)];
const orders = [
  { id: "a", zoneId: "정자1", driverId: "7002" },
  { id: "b", zoneId: "분당", driverId: "7001" },
  { id: "c", zoneId: "수정", driverId: null },
];

test("units are 권역 at the zone level, and 권역 then 세부권역 at the sub level", () => {
  assert.deepEqual(unitsOf(rows, "zone").map(item => item.id), ["분당", "수정"]);
  assert.deepEqual(unitsOf(rows, "sub").map(item => item.id), ["분당", "수정", "정자1"]);
  assert.equal(unitKeyOf(orders[0]!, rows, "zone"), "분당");
  assert.equal(unitKeyOf(orders[0]!, rows, "sub"), "정자1");
});

test("an order's own change wins, then its 세부권역, then its 권역, then the default", () => {
  let changes = assignUnits(NO_CHANGES, ["분당"], "7009", orders, rows);
  assert.equal(driverOf(orders[0]!, rows, changes), "7009");
  changes = assignUnits(changes, ["정자1"], "7005", orders, rows);
  assert.equal(driverOf(orders[0]!, rows, changes), "7005");
  assert.equal(driverOf(orders[1]!, rows, changes), "7009");
  changes = assignOrder(changes, "a", null);
  assert.equal(driverOf(orders[0]!, rows, changes), null);
  assert.equal(driverOf(orders[2]!, rows, changes), null);
});

test("giving a whole 권역 drops earlier changes inside it", () => {
  let changes = assignUnits(NO_CHANGES, ["정자1"], "7005", orders, rows);
  changes = assignOrder(changes, "b", "7006");
  changes = assignUnits(changes, ["분당"], "7001", orders, rows);
  assert.deepEqual(changes, { units: { 분당: "7001" }, orders: {} });
});

test("a saved run becomes per-order changes, its loose orders without a driver", () => {
  const changes = fromRun({ routes: [{ driverId: "7001", stops: [{ orderId: "a" }] }], unassigned: ["c"] });
  assert.deepEqual(changes, { units: {}, orders: { a: "7001", c: null } });
});
