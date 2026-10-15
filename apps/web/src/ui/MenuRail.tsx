import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { Icon, type IconName } from "./Icon";

export type MenuItem<Id extends string> = { id: Id; label: string; icon: IconName };
export type MenuGroup<Id extends string> = { label: string; items: MenuItem<Id>[] };

const KEY = "ups.rail.open";

function readOpen(): boolean {
  try {
    return localStorage.getItem(KEY) !== "false";
  } catch {
    return true;
  }
}

/**
 * The far-left menu. Groups follow the order of the work. Folded, it shows the icons only, so the map keeps
 * its width; the choice is remembered in this browser.
 */
export function MenuRail<Id extends string>(props: {
  groups: MenuGroup<Id>[];
  active: Id;
  onChange: (id: Id) => void;
}): JSX.Element {
  const [open, setOpen] = useState(readOpen);
  const toggle = (): void => {
    setOpen(previous => {
      try {
        localStorage.setItem(KEY, String(!previous));
      } catch {
        // Only a convenience: without storage the rail starts open next time.
      }
      return !previous;
    });
  };
  return (
    <nav class="rail" aria-label="메뉴" data-open={open}>
      {props.groups.map(group => (
        <div class="rail-group" key={group.label}>
          <span class="rail-group-label">{group.label}</span>
          {group.items.map(item => (
            <button
              type="button"
              key={item.id}
              class="rail-item"
              aria-current={item.id === props.active ? "page" : undefined}
              title={open ? undefined : item.label}
              onClick={() => props.onChange(item.id)}
            >
              <Icon name={item.icon} />
              <span class="rail-item-label">{item.label}</span>
            </button>
          ))}
        </div>
      ))}
      <button
        type="button"
        class="rail-item rail-toggle"
        aria-expanded={open}
        aria-label={open ? "메뉴 접기" : "메뉴 펼치기"}
        title={open ? undefined : "메뉴 펼치기"}
        onClick={toggle}
      >
        <Icon name={open ? "chevron-left" : "chevron-right"} />
        <span class="rail-item-label">메뉴 접기</span>
      </button>
    </nav>
  );
}
