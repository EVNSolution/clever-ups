import assert from "node:assert/strict";
import { test } from "node:test";
import { outerEdges } from "./outline.ts";

const square = (x: number, y: number) => ({
  type: "Polygon",
  coordinates: [[[x, y], [x + 1, y], [x + 1, y + 1], [x, y + 1], [x, y]]],
});

test("two squares side by side lose the edge they share", () => {
  const edges = outerEdges([square(0, 0), square(1, 0)]);
  assert.equal(edges.length, 6);
  assert.ok(!edges.some(([a, b]) => a?.[0] === 1 && b?.[0] === 1));
});

test("a single shape keeps every edge", () => {
  assert.equal(outerEdges([square(0, 0)]).length, 4);
});
