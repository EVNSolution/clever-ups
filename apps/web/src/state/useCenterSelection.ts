import { useCallback, useEffect, useMemo, useState } from "preact/hooks";
import type { Center } from "../api/session";
import { pickCenter, readCenterParam, withCenterParam } from "../center";
import type { Resource } from "./useResource";

export interface CenterSelection {
  /** The center being worked in, or null when the account has none or the list is not loaded. */
  selectedId: string | null;
  select: (id: string) => void;
  /** True once the center list answered, with or without an error. Lists can load from then on. */
  settled: boolean;
}

/**
 * The selected center lives in the URL (?center=), so a reload or a shared link keeps it.
 * Nothing is stored in the browser or on the server.
 */
export function useCenterSelection(centers: Resource<Center>): CenterSelection {
  const [requested, setRequested] = useState<string | null>(() => readCenterParam(location.href));
  const settled = centers.items !== null || (!centers.loading && centers.error !== null);

  const selectedId = useMemo(
    () => (centers.items === null ? null : pickCenter(centers.items, requested)),
    [centers.items, requested],
  );

  useEffect(() => {
    // Keep the URL in step once the list is known. A failed list leaves the URL as it is.
    if (centers.items === null) return;
    const next = withCenterParam(location.href, selectedId);
    if (next !== location.href) history.replaceState(null, "", next);
  }, [centers.items, selectedId]);

  const select = useCallback((id: string) => setRequested(id), []);
  return { selectedId, select, settled };
}
