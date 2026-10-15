import type { ComponentChildren, JSX } from "preact";
import { useRef, useState } from "preact/hooks";

/**
 * Single-select list with one tab stop (roving tabindex).
 * Arrow keys, Home and End move focus; Enter, Space or a click selects; Escape calls onEscape.
 */
export function ListBox<T>(props: {
  label: string;
  items: readonly T[];
  getId: (item: T) => string;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onEscape?: (() => void) | undefined;
  renderOption: (item: T, selected: boolean) => ComponentChildren;
  /** Class of each option. Defaults to the list row look. */
  optionClass?: string;
}): JSX.Element {
  const listRef = useRef<HTMLDivElement>(null);
  const [focusId, setFocusId] = useState<string | null>(null);

  const ids = props.items.map(props.getId);
  const preferred = [focusId, props.selectedId].find(id => id !== null && ids.includes(id));
  const tabbableId = preferred ?? ids[0] ?? null;

  const onKeyDown = (event: KeyboardEvent): void => {
    if (event.key === "Escape") {
      props.onEscape?.();
      return;
    }
    const options = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
    const index = options.findIndex(option => option === document.activeElement);
    if (index < 0) return;
    const moves: Record<string, number> = {
      ArrowDown: index + 1,
      ArrowUp: index - 1,
      Home: 0,
      End: options.length - 1,
    };
    const next = moves[event.key];
    if (next === undefined) return;
    event.preventDefault();
    options[Math.min(Math.max(next, 0), options.length - 1)]?.focus();
  };

  return (
    // A custom listbox: rows hold icons, badges and two text lines, which a native select cannot show.
    // The options carry the tab stop (roving tabindex), not the container.
    // oxlint-disable-next-line jsx-a11y/interactive-supports-focus, jsx-a11y/prefer-tag-over-role
    <div class="listbox" role="listbox" aria-label={props.label} ref={listRef} onKeyDown={onKeyDown}>
      {props.items.map(item => {
        const id = props.getId(item);
        return (
          <div
            key={id}
            // oxlint-disable-next-line jsx-a11y/prefer-tag-over-role
            role="option"
            class={props.optionClass ?? "option"}
            data-id={id}
            aria-selected={id === props.selectedId}
            tabIndex={id === tabbableId ? 0 : -1}
            onClick={() => props.onSelect(id)}
            onFocus={() => setFocusId(id)}
            onKeyDown={event => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                props.onSelect(id);
              }
            }}
          >
            {props.renderOption(item, id === props.selectedId)}
          </div>
        );
      })}
    </div>
  );
}
