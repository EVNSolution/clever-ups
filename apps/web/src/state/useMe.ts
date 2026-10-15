import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { getMe, type Account } from "../api/session";

export type MeState =
  | { status: "loading" }
  | { status: "signed-in"; account: Account }
  | { status: "signed-out" }
  | { status: "error"; error: unknown };

/** The signed-in account. "signed-out" is a normal answer (HTTP 401), not an error. */
export function useMe(): MeState & { reload: () => void; refresh: () => void } {
  const [state, setState] = useState<MeState>({ status: "loading" });
  const latest = useRef(0);

  const reload = useCallback(() => {
    const id = ++latest.current;
    setState({ status: "loading" });
    getMe().then(
      account => {
        if (id === latest.current) setState(account === null ? { status: "signed-out" } : { status: "signed-in", account });
      },
      (error: unknown) => {
        if (id === latest.current) setState({ status: "error", error });
      },
    );
  }, []);

  /** Asks again without a loading state, so the page stays as it is until the answer differs. */
  const refresh = useCallback(() => {
    const id = ++latest.current;
    getMe().then(
      account => {
        if (id === latest.current) setState(account === null ? { status: "signed-out" } : { status: "signed-in", account });
      },
      () => undefined,
    );
  }, []);

  useEffect(() => {
    reload();
    return () => {
      latest.current++;
    };
  }, [reload]);

  return { ...state, reload, refresh };
}
