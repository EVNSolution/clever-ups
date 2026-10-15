import assert from "node:assert/strict";
import { test } from "node:test";
import { decodePolyline } from "./polyline.ts";

test("the reference line of the format decodes to its three points, as [lng, lat]", () => {
  assert.deepEqual(decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@"), [
    [-120.2, 38.5],
    [-120.95, 40.7],
    [-126.453, 43.252],
  ]);
  assert.deepEqual(decodePolyline(""), []);
});
