import assert from "node:assert/strict";
import { test } from "node:test";
import { edgesOf, snap } from "./snap.ts";

// One degree is 100 pixels, y grows down as on a screen.
const project = (lng: number, lat: number) => ({ x: lng * 100, y: -lat * 100 });
const square = { type: "Polygon", coordinates: [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]] };
const edges = edgesOf([square]);
const everywhere = [-10, -10, 10, 10] as const;

test("a corner near the pointer wins, with its exact coordinates", () => {
  assert.deepEqual(snap({ x: 104, y: -97 }, edges, project, everywhere, 10), [1, 1]);
});

test("away from corners the pointer lands on the nearest edge", () => {
  assert.deepEqual(snap({ x: 50, y: 6 }, edges, project, everywhere, 10), [0.5, 0]);
});

test("nothing within the tolerance, or outside the box, gives null", () => {
  assert.equal(snap({ x: 50, y: -50 }, edges, project, everywhere, 10), null);
  assert.equal(snap({ x: 104, y: -97 }, edges, project, [5, 5, 6, 6], 10), null);
});
