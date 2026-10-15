import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

/**
 * Icons come from the icon library (src/ui/Icon.tsx). Vector shapes written by hand are not allowed:
 * a change that adds one fails here, so the rule does not depend on someone remembering it.
 */
const FORBIDDEN = [/<svg[\s>]/, /<path[\s>]/, /<circle[\s>]/, /<polyline[\s>]/, /<polygon[\s>]/, /<line[\s>]/, /data:image\/svg/];
const SKIP = new Set(["schema.d.ts", "no-handmade-svg.test.ts"]);

function files(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

test("no source file draws an svg by hand", () => {
  const offenders = files("src")
    .filter(path => /\.(tsx?|css)$/.test(path) && !SKIP.has(path.split("/").pop() ?? ""))
    .filter(path => FORBIDDEN.some(pattern => pattern.test(readFileSync(path, "utf8"))));
  assert.deepEqual(offenders, []);
});
