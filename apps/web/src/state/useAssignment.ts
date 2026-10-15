import { useCallback, useEffect, useState } from "preact/hooks";
import { NO_CHANGES, fromRun, type Assignment } from "../assign";
import { getDispatch, listDispatches, type DispatchSummary } from "../api/dispatches";

const UNDO_DEPTH = 50;

export interface AssignmentState {
  changes: Assignment;
  /** The saved run of the day the changes started from, or null when the day starts from the zones' drivers. */
  base: DispatchSummary | null;
  apply: (change: (previous: Assignment) => Assignment) => void;
  undo: () => void;
  canUndo: boolean;
  /** Back to the zones' drivers, as if nothing were saved. Can be undone. */
  reset: () => void;
}

/**
 * The day's dispatch as a person changes it. A day starts from its latest kept run (a draft or the sent one), so
 * a saved dispatch opens as it was saved; without one it starts from the zones' drivers. Saving makes a new
 * draft of the day (the 배차 저장 button), so the assignment is kept per day, not on the drivers.
 */
export function useAssignment(centerId: string | undefined, day: string, enabled: boolean): AssignmentState {
  const [state, setState] = useState<{ changes: Assignment; past: Assignment[] }>({ changes: NO_CHANGES, past: [] });
  const [base, setBase] = useState<DispatchSummary | null>(null);

  useEffect(() => {
    setState({ changes: NO_CHANGES, past: [] });
    setBase(null);
    if (!enabled || centerId === undefined) return;
    let live = true;
    void (async () => {
      const runs = await listDispatches(centerId, day);
      const kept = runs.items.find(run => run.status === "DRAFT" || run.status === "SENT");
      const run = kept === undefined ? null : await getDispatch(kept.id);
      if (!live || run === null || kept === undefined) return;
      setState({ changes: fromRun(run), past: [] });
      setBase(kept);
    })().catch(() => undefined);
    return () => {
      live = false;
    };
  }, [centerId, day, enabled]);

  const apply = useCallback(
    (change: (previous: Assignment) => Assignment): void =>
      setState(previous => ({ changes: change(previous.changes), past: [...previous.past.slice(-UNDO_DEPTH), previous.changes] })),
    [],
  );
  const undo = useCallback(
    (): void =>
      setState(previous =>
        previous.past.length === 0 ? previous : { changes: previous.past.at(-1) ?? NO_CHANGES, past: previous.past.slice(0, -1) },
      ),
    [],
  );
  const reset = useCallback((): void => apply(() => NO_CHANGES), [apply]);
  return { changes: state.changes, base, apply, undo, canUndo: state.past.length > 0, reset };
}
