type Point = [number, number];

const key = (a: Point, b: Point): string => {
  const p = `${a[0].toFixed(7)},${a[1].toFixed(7)}`;
  const q = `${b[0].toFixed(7)},${b[1].toFixed(7)}`;
  return p < q ? `${p}|${q}` : `${q}|${p}`;
};

/**
 * The outer edge of several touching shapes, as separate segments: an edge two shapes share is inside the group
 * and left out. Shapes made from the same boundaries, or cut by the server against each other, share their edge
 * corners exactly, so matching rounded corners is enough.
 */
// ponytail: edges must match corner to corner; an edge split differently on two sides stays drawn. A real
// polygon union (server JTS) is the upgrade if that shows on the map.
export function outerEdges(shapes: readonly { type: string; coordinates: unknown[] }[]): Point[][] {
  const seen = new Map<string, { segment: Point[]; count: number }>();
  for (const shape of shapes) {
    const polygons = (shape.type === "Polygon" ? [shape.coordinates] : shape.coordinates) as number[][][][];
    for (const ring of polygons.flat()) {
      for (let i = 1; i < ring.length; i++) {
        const a: Point = [ring[i - 1]?.[0] ?? 0, ring[i - 1]?.[1] ?? 0];
        const b: Point = [ring[i]?.[0] ?? 0, ring[i]?.[1] ?? 0];
        const id = key(a, b);
        const found = seen.get(id);
        if (found === undefined) seen.set(id, { segment: [a, b], count: 1 });
        else found.count += 1;
      }
    }
  }
  return [...seen.values()].flatMap(edge => (edge.count === 1 ? [edge.segment] : []));
}
