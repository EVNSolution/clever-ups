import type { ComponentChildren, JSX } from "preact";
import { useEffect, useRef } from "preact/hooks";

/**
 * A panel under its trigger. It closes on an outside click and when focus leaves it.
 * The owner closes it on Escape and puts focus back on the trigger. `focusOnOpen` is a selector for the first focus.
 */
export function Popover(props: {
  open: boolean;
  onClose: () => void;
  trigger: ComponentChildren;
  focusOnOpen: string;
  children: ComponentChildren;
}): JSX.Element {
  const root = useRef<HTMLDivElement>(null);
  const { open, onClose, focusOnOpen } = props;

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent): void => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) onClose();
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open, onClose]);

  useEffect(() => {
    if (open) root.current?.querySelector<HTMLElement>(focusOnOpen)?.focus();
  }, [open, focusOnOpen]);

  return (
    <div
      class="popover"
      ref={root}
      onFocusOut={event => {
        // Only a move to another element counts. A click on blank panel space has no related target.
        const next = event.relatedTarget;
        if (open && next instanceof Node && !root.current?.contains(next)) onClose();
      }}
    >
      {props.trigger}
      {open && <div class="popover-panel">{props.children}</div>}
    </div>
  );
}
