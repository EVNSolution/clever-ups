import { useCallback, useEffect, useState } from "preact/hooks";
import { getHealth } from "../api/health";

export interface HealthState {
  status: "checking" | "up" | "down";
  checkedAt: Date | null;
  check: () => void;
}

/**
 * Checks the API now and every `intervalMs`, and again when the browser goes back online.
 * While the API is down it checks more often, so the status does not stay red after it recovers.
 */
export function useHealth(intervalMs = 30_000, downIntervalMs = 5_000): HealthState {
  const [state, setState] = useState<Pick<HealthState, "status" | "checkedAt">>({ status: "checking", checkedAt: null });

  const check = useCallback(() => {
    getHealth().then(
      () => setState({ status: "up", checkedAt: new Date() }),
      () => setState({ status: "down", checkedAt: new Date() }),
    );
  }, []);

  const delay = state.status === "down" ? downIntervalMs : intervalMs;

  useEffect(() => {
    check();
    const timer = setInterval(check, delay);
    addEventListener("online", check);
    return () => {
      clearInterval(timer);
      removeEventListener("online", check);
    };
  }, [check, delay]);

  return { ...state, check };
}
