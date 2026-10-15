/** Edges of shapes as a flat list: x1, y1, x2, y2 in longitude and latitude, four numbers per edge. */
export function edgesOf(geometries: readonly { type: string; coordinates: unknown[] }[]): Float64Array {
  const out: number[] = [];
  for (const geometry of geometries) {
    const polygons = (geometry.type === "Polygon" ? [geometry.coordinates] : geometry.coordinates) as number[][][][];
    for (const ring of polygons.flat()) {
      for (let i = 1; i < ring.length; i++) {
        const a = ring[i - 1] ?? [];
        const b = ring[i] ?? [];
        out.push(a[0] ?? 0, a[1] ?? 0, b[0] ?? 0, b[1] ?? 0);
      }
    }
  }
  return Float64Array.from(out);
}

export interface ScreenPoint {
  x: number;
  y: number;
}

/**
 * The point to use for a pointer at [cursor]: the nearest corner within [tolerance] pixels, else the nearest
 * point on an edge, else null. A corner is returned with its exact coordinates, so a drawn zone shares the
 * corner with the boundary or zone it touches. [box] ([west, south, east, north]) skips far edges cheaply.
 */
export function snap(
  cursor: ScreenPoint,
  edges: Float64Array,
  project: (lng: number, lat: number) => ScreenPoint,
  box: readonly [number, number, number, number],
  tolerance: number,
): [number, number] | null {
  let corner: [number, number] | null = null;
  let cornerDistance = tolerance;
  let onEdge: [number, number] | null = null;
  let edgeDistance = tolerance;
  const [west, south, east, north] = box;
  for (let i = 0; i < edges.length; i += 4) {
    const x1 = edges[i] ?? 0;
    const y1 = edges[i + 1] ?? 0;
    const x2 = edges[i + 2] ?? 0;
    const y2 = edges[i + 3] ?? 0;
    if (Math.max(x1, x2) < west || Math.min(x1, x2) > east || Math.max(y1, y2) < south || Math.min(y1, y2) > north) continue;
    const a = project(x1, y1);
    const b = project(x2, y2);
    for (const [point, lng, lat] of [[a, x1, y1], [b, x2, y2]] as const) {
      const distance = Math.hypot(point.x - cursor.x, point.y - cursor.y);
      if (distance <= cornerDistance) {
        cornerDistance = distance;
        corner = [lng, lat];
      }
    }
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const length = dx * dx + dy * dy;
    const t = length === 0 ? 0 : Math.max(0, Math.min(1, ((cursor.x - a.x) * dx + (cursor.y - a.y) * dy) / length));
    const distance = Math.hypot(a.x + t * dx - cursor.x, a.y + t * dy - cursor.y);
    if (distance <= edgeDistance) {
      edgeDistance = distance;
      onEdge = [x1 + t * (x2 - x1), y1 + t * (y2 - y1)];
    }
  }
  return corner ?? onEdge;
}
