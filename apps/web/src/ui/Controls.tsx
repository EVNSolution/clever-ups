import type { ComponentChildren, JSX } from "preact";
import { Icon, type IconName } from "./Icon";

export function IconButton(props: {
  icon: IconName;
  label: string;
  onClick: () => void;
  busy?: boolean;
  pressed?: boolean;
  expanded?: boolean;
  controls?: string;
}): JSX.Element {
  return (
    <button
      type="button"
      class="icon-btn"
      aria-label={props.label}
      title={props.label}
      data-busy={props.busy ? "true" : undefined}
      aria-pressed={props.pressed}
      aria-expanded={props.expanded}
      aria-controls={props.controls}
      onClick={props.onClick}
    >
      <Icon name={props.icon} />
    </button>
  );
}

export function SearchField(props: {
  label: string;
  placeholder: string;
  value: string;
  onInput: (value: string) => void;
  onKeyDown?: (event: KeyboardEvent) => void;
}): JSX.Element {
  return (
    <div class="search">
      <Icon name="search" size={16} />
      <input
        type="search"
        aria-label={props.label}
        placeholder={props.placeholder}
        value={props.value}
        autocomplete="off"
        spellcheck={false}
        onInput={event => props.onInput(event.currentTarget.value)}
        onKeyDown={props.onKeyDown}
      />
      {props.value !== "" && (
        <button type="button" class="clear" aria-label="검색어 지우기" onClick={() => props.onInput("")}>
          <Icon name="close" size={14} />
        </button>
      )}
    </div>
  );
}

export function ToggleChip(props: {
  label: string;
  count: number;
  pressed: boolean;
  onToggle: () => void;
}): JSX.Element {
  return (
    <button type="button" class="chip" aria-pressed={props.pressed} onClick={props.onToggle}>
      {props.label}
      <span class="chip-count">{props.count}</span>
    </button>
  );
}

export type Tone = "neutral" | "ok" | "warn" | "danger";

export function Badge(props: { tone?: Tone; icon?: IconName; children: ComponentChildren }): JSX.Element {
  return (
    <span class="badge" data-tone={props.tone ?? "neutral"}>
      {props.icon !== undefined && <Icon name={props.icon} size={12} />}
      {props.children}
    </span>
  );
}

export function Notice(props: {
  tone?: Tone;
  icon: IconName;
  title?: string;
  role?: "alert" | "status";
  children?: ComponentChildren;
}): JSX.Element {
  return (
    <div class="notice" data-tone={props.tone ?? "neutral"} role={props.role}>
      <Icon name={props.icon} size={16} />
      <div>
        {props.title !== undefined && <p class="notice-title">{props.title}</p>}
        {props.children}
      </div>
    </div>
  );
}
