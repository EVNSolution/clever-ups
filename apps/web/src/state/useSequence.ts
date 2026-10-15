import { useEffect, useRef, useState } from "preact/hooks";
import { sequenceStops, type Sequence, type SequenceRequest } from "../api/sequence";

export type SequenceState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ok"; sequence: Sequence }
  | { status: "unavailable" }
  | { status: "error"; error: unknown };

/**
 * The visiting order of one driver's stops, asked again only when the stops or the start time change. Answers
 * already seen are kept while the page lives, so going back to a driver shows at once.
 */
export function useSequence(request: SequenceRequest | null): SequenceState {
  const key = request === null ? null : JSON.stringify(request);
  const seen = useRef(new Map<string, Sequence>());
  const [state, setState] = useState<SequenceState>({ status: "idle" });
  useEffect(() => {
    if (key === null || request === null) {
      setState({ status: "idle" });
      return;
    }
    const known = seen.current.get(key);
    if (known !== undefined) {
      setState({ status: "ok", sequence: known });
      return;
    }
    let live = true;
    setState({ status: "loading" });
    sequenceStops(request).then(
      outcome => {
        if (!live) return;
        if (outcome.kind === "unavailable") {
          setState({ status: "unavailable" });
          return;
        }
        seen.current.set(key, outcome.sequence);
        setState({ status: "ok", sequence: outcome.sequence });
      },
      (error: unknown) => {
        if (live) setState({ status: "error", error });
      },
    );
    return () => {
      live = false;
    };
  }, [key]);
  return state;
}
