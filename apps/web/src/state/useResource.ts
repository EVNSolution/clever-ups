import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import type { Loaded } from "../api/http";

export interface Resource<T> {
  /** null until the first successful load. Old items are kept while reloading or after a failed reload. */
  items: T[] | null;
  loading: boolean;
  error: unknown;
  updatedAt: Date | null;
  /** True when the source may hold more than it handed over. */
  incomplete: boolean;
  reload: () => void;
}

interface Snapshot<T> {
  items: T[] | null;
  loading: boolean;
  error: unknown;
  updatedAt: Date | null;
  incomplete: boolean;
}

const initial = <T>(): Snapshot<T> => ({ items: null, loading: true, error: null, updatedAt: null, incomplete: false });

/**
 * Loads a list, and again on `reload`. `load` must be a stable function.
 * A new `load` (for example another center) starts over: items of the old scope are dropped, not shown.
 * While `enabled` is false nothing is requested and the state stays "loading".
 */
export function useResource<T>(load: () => Promise<Loaded<T>>, enabled = true): Resource<T> {
  const [snapshot, setSnapshot] = useState<Snapshot<T>>(initial);
  const latest = useRef(0);

  const fetchNow = useCallback(() => {
    const id = ++latest.current;
    setSnapshot(previous => ({ ...previous, loading: true }));
    load().then(
      ({ items, incomplete }) => {
        if (id === latest.current) setSnapshot({ items, incomplete, loading: false, error: null, updatedAt: new Date() });
      },
      (error: unknown) => {
        if (id === latest.current) setSnapshot(previous => ({ ...previous, loading: false, error }));
      },
    );
  }, [load]);

  useEffect(() => {
    setSnapshot(initial);
    if (!enabled) return;
    fetchNow();
    return () => {
      latest.current++;
    };
  }, [fetchNow, enabled]);

  return { ...snapshot, reload: fetchNow };
}
