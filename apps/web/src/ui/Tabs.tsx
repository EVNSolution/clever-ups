import type { JSX } from "preact";

export interface TabDef<T extends string> {
  id: T;
  label: string;
  count?: number | null | undefined;
}

/** Tablist with one tab stop. Arrow keys, Home and End move between tabs and activate them. */
export function Tabs<T extends string>(props: {
  label: string;
  idPrefix: string;
  tabs: readonly TabDef<T>[];
  active: T;
  onChange: (id: T) => void;
}): JSX.Element {
  const { tabs, active, idPrefix } = props;

  const onKeyDown = (event: KeyboardEvent): void => {
    const index = tabs.findIndex(tab => tab.id === active);
    const last = tabs.length - 1;
    const keys: Record<string, number> = {
      ArrowRight: index === last ? 0 : index + 1,
      ArrowLeft: index === 0 ? last : index - 1,
      Home: 0,
      End: last,
    };
    const next = keys[event.key];
    const target = next === undefined ? undefined : tabs[next];
    if (target === undefined) return;
    event.preventDefault();
    props.onChange(target.id);
    requestAnimationFrame(() => document.getElementById(`${idPrefix}-tab-${target.id}`)?.focus());
  };

  return (
    // Roving tabindex lives on the tabs; the tablist itself is not a tab stop.
    // oxlint-disable-next-line jsx-a11y/interactive-supports-focus
    <div class="seg" role="tablist" aria-label={props.label} onKeyDown={onKeyDown}>
      {tabs.map(tab => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          class="seg-tab"
          id={`${idPrefix}-tab-${tab.id}`}
          aria-selected={tab.id === active}
          aria-controls={`${idPrefix}-panel-${tab.id}`}
          tabIndex={tab.id === active ? 0 : -1}
          onClick={() => props.onChange(tab.id)}
        >
          {tab.label}
          {tab.count !== undefined && tab.count !== null && <span class="seg-count">{tab.count}</span>}
        </button>
      ))}
    </div>
  );
}
