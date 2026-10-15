import assert from "node:assert/strict";
import { test } from "node:test";
import { buildZones } from "./zones.ts";

const square = (x: number, y: number, size: number) => ({
  type: "Polygon" as const,
  coordinates: [[[x, y], [x + size, y], [x + size, y + size], [x, y + size], [x, y]]],
});

const zones = [
  { id: "a", name: "분당", shape: square(0, 0, 4) },
  { id: "b", name: "정자1", parentId: "a", shape: square(0, 0, 1) },
  { id: "c", name: "수정", shape: null },
];

test("a point belongs to its 세부권역 first, then its 권역, and outside every shape to none", () => {
  const { zoneOf } = buildZones(zones, [], []);
  assert.equal(zoneOf(0.5, 0.5), "정자1");
  assert.equal(zoneOf(3, 3), "분당");
  assert.equal(zoneOf(9, 9), null);
});

test("a 권역 counts the orders of its 세부권역, and a 세부권역 without a driver takes its 권역's", () => {
  const drivers = [{ id: "7001", zones: ["분당"] }, { id: "7002", zones: ["수정", "분당"] }];
  const { rows } = buildZones(zones, drivers, [{ lng: 0.5, lat: 0.5 }, { lng: 3, lat: 3 }]);
  const row = (name: string) => rows.find(item => item.id === name);
  assert.equal(row("분당")?.orders, 2);
  assert.equal(row("정자1")?.orders, 1);
  assert.equal(row("분당")?.driverId, "7001");
  assert.equal(row("정자1")?.driverId, "7001");
  assert.equal(row("정자1")?.parentName, "분당");
  assert.equal(row("수정")?.driverId, "7002");
});
