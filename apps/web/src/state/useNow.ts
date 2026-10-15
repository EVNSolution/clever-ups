import { useEffect, useState } from "preact/hooks";

/** The current time, refreshed every `intervalMs`, so ages such as "3분 전" keep moving. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}
