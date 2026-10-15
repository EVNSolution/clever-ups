import { inShape, type PolygonShape } from "./geo.ts";

/** A zone as the lists show it. The ID is the zone name, unique in a center. A row with a parent is a 세부권역. */
export interface ZoneRow {
  id: string;
  zoneId: string;
  parentName: string | null;
  shape: PolygonShape | null;
  /** Orders of the day inside the zone; for a 권역, also those inside its 세부권역. */
  orders: number;
  driverId: string | null;
}

interface ZoneIn {
  id: string;
  name: string;
  parentId?: string | null | undefined;
  shape?: PolygonShape | null | undefined;
}

type Box = [number, number, number, number];

function boxOf(shape: PolygonShape): Box {
  const points = (shape.type === "Polygon" ? [shape.coordinates] : shape.coordinates).flat(2) as number[][];
  const xs = points.map(point => point[0] ?? 0);
  const ys = points.map(point => point[1] ?? 0);
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}

/**
 * The zones of a center with the day's numbers, and a finder for the zone of a point: a 세부권역 first, then its
 * 권역. The driver of a zone is the driver whose 담당 권역 it is (the main one first); a 세부권역 without one
 * takes its 권역's driver.
 */
export function buildZones(
  zones: readonly ZoneIn[],
  drivers: readonly { id: string; zones?: readonly string[] | undefined }[],
  points: readonly { lng: number; lat: number }[],
): { rows: ZoneRow[]; zoneOf: (lng: number, lat: number) => string | null } {
  const nameOf = new Map(zones.map(zone => [zone.id, zone.name] as const));
  const shaped = zones
    .filter((zone): zone is ZoneIn & { shape: PolygonShape } => zone.shape !== null && zone.shape !== undefined)
    // 세부권역 first: the deepest zone wins.
    .sort((a, b) => Number(Boolean(b.parentId)) - Number(Boolean(a.parentId)))
    .map(zone => ({ zone, box: boxOf(zone.shape) }));
  const zoneOf = (lng: number, lat: number): string | null => {
    const hit = shaped.find(
      ({ zone, box }) => lng >= box[0] && lng <= box[2] && lat >= box[1] && lat <= box[3] && inShape(lng, lat, zone.shape),
    );
    return hit?.zone.name ?? null;
  };
  const counts = new Map<string, number>();
  for (const point of points) {
    const name = zoneOf(point.lng, point.lat);
    if (name !== null) counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  const driverOf = (name: string): string | null =>
    (drivers.find(driver => driver.zones?.[0] === name) ?? drivers.find(driver => driver.zones?.includes(name)))?.id ?? null;
  const rows = zones.map(zone => {
    const parentName = zone.parentId ? (nameOf.get(zone.parentId) ?? null) : null;
    const children = zones.flatMap(child => (child.parentId === zone.id ? [child.name] : []));
    return {
      id: zone.name,
      zoneId: zone.id,
      parentName,
      shape: zone.shape ?? null,
      orders: [zone.name, ...children].reduce((sum, name) => sum + (counts.get(name) ?? 0), 0),
      driverId: driverOf(zone.name) ?? (parentName === null ? null : driverOf(parentName)),
    };
  });
  return { rows, zoneOf };
}
