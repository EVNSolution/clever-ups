import assert from "node:assert/strict";
import { test } from "node:test";
import { orderOriginal, orderTitle } from "./order.ts";

test("orderTitle is the Korean address, else the sender's address, else the tracking number", () => {
  const base = { trackingNumber: "T1", address1: " 1 Road " };
  assert.equal(orderTitle({ ...base, addressKo: " 경기도 성남시 수정구 1 " }), "경기도 성남시 수정구 1");
  assert.equal(orderTitle({ ...base, addressKo: "  " }), "1 Road");
  assert.equal(orderTitle({ trackingNumber: "T1" }), "T1");
});

test("orderOriginal shows the sender's address only under a Korean one", () => {
  const base = { trackingNumber: "T", address1: "1 Road" };
  assert.equal(orderOriginal({ ...base, addressKo: "성남시 수정구 1길" }), "1 Road");
  assert.equal(orderOriginal(base), "");
  assert.equal(orderOriginal({ trackingNumber: "T", addressKo: "성남시 수정구 1길" }), "");
});
