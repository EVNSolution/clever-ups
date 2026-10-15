/** An item whose position may be missing. Missing means "location not confirmed", never a guessed marker. */
export interface MaybePositioned {
  lng?: number | null | undefined;
  lat?: number | null | undefined;
}

export function hasPosition<T extends MaybePositioned>(item: T): item is T & { lng: number; lat: number } {
  return Number.isFinite(item.lng) && Number.isFinite(item.lat);
}

/** A GeoJSON Polygon or MultiPolygon, as the API gives it. */
export interface PolygonShape {
  type: "Polygon" | "MultiPolygon";
  coordinates: unknown[];
}

/** Ray casting on one ring of [lng, lat] points. */
function inRing(lng: number, lat: number, ring: readonly (readonly number[])[]): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi = 0, yi = 0] = ring[i] ?? [];
    const [xj = 0, yj = 0] = ring[j] ?? [];
    if (yi > lat !== yj > lat && lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** True when the point is inside the shape: inside an outer ring and outside its holes. */
export function inShape(lng: number, lat: number, shape: PolygonShape): boolean {
  const polygons = (shape.type === "Polygon" ? [shape.coordinates] : shape.coordinates) as number[][][][];
  return polygons.some(([outer = [], ...holes]) => inRing(lng, lat, outer) && !holes.some(hole => inRing(lng, lat, hole)));
}
