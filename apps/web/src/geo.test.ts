import assert from "node:assert/strict";
import { test } from "node:test";
import { inShape, type PolygonShape } from "./geo.ts";

const square = (x: number, y: number, size: number): number[][] => [
  [x, y],
  [x + size, y],
  [x + size, y + size],
  [x, y + size],
  [x, y],
];

test("a point is in a polygon, but not in its hole", () => {
  const donut: PolygonShape = { type: "Polygon", coordinates: [square(0, 0, 4), square(1, 1, 2)] };
  assert.equal(inShape(0.5, 0.5, donut), true);
  assert.equal(inShape(2, 2, donut), false);
  assert.equal(inShape(5, 5, donut), false);
});

test("a point is in any part of a multipolygon", () => {
  const two: PolygonShape = { type: "MultiPolygon", coordinates: [[square(0, 0, 1)], [square(10, 10, 1)]] };
  assert.equal(inShape(10.5, 10.5, two), true);
  assert.equal(inShape(5, 5, two), false);
});
