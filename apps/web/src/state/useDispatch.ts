import { useEffect, useState } from "preact/hooks";
import { getDispatch, type DispatchDetail } from "../api/dispatches";

export type DispatchState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "missing" }
  | { status: "ok"; run: DispatchDetail }
  | { status: "error"; error: unknown };

/** The saved dispatch with this ID, read again when the ID or [version] changes. */
export function useDispatch(id: string | null, version = 0): DispatchState {
  const [state, setState] = useState<DispatchState>({ status: "idle" });
  useEffect(() => {
    if (id === null) {
      setState({ status: "idle" });
      return;
    }
    let live = true;
    setState({ status: "loading" });
    getDispatch(id).then(
      run => {
        if (live) setState(run === null ? { status: "missing" } : { status: "ok", run });
      },
      (error: unknown) => {
        if (live) setState({ status: "error", error });
      },
    );
    return () => {
      live = false;
    };
  }, [id, version]);
  return state;
}
