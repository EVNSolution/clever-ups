export const CENTER_PARAM = "center";

/**
 * The center to show: the requested one if the account can use it, otherwise the first one.
 * null only when the account has no center.
 */
export function pickCenter(centers: readonly { id: string }[], requested: string | null): string | null {
  const match = requested === null ? undefined : centers.find(center => center.id === requested);
  return (match ?? centers[0])?.id ?? null;
}

/** The URL with the center set or removed. The hash and other parameters stay. */
export function withCenterParam(href: string, centerId: string | null): string {
  const url = new URL(href);
  if (centerId === null) url.searchParams.delete(CENTER_PARAM);
  else url.searchParams.set(CENTER_PARAM, centerId);
  return url.toString();
}

export const readCenterParam = (href: string): string | null => new URL(href).searchParams.get(CENTER_PARAM);
