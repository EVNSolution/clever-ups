import type { ComponentChildren, JSX } from "preact";
import { Icon, type IconName } from "./Icon";

export function Skeleton({ rows = 6 }: { rows?: number }): JSX.Element {
  return (
    <output class="skeleton">
      <span class="sr-only">불러오는 중</span>
      {Array.from({ length: rows }, (_, index) => (
        <div class="skeleton-row" key={index} aria-hidden="true">
          <div class="skeleton-block" style={{ width: 32, height: 32 }} />
          <div style={{ display: "grid", gap: 6 }}>
            <div class="skeleton-block" style={{ height: 12, width: `${60 + ((index * 13) % 30)}%` }} />
            <div class="skeleton-block" style={{ height: 10, width: `${40 + ((index * 17) % 30)}%` }} />
          </div>
        </div>
      ))}
    </output>
  );
}

export function EmptyState(props: {
  icon: IconName;
  title: string;
  hint?: string;
  children?: ComponentChildren;
}): JSX.Element {
  return (
    <div class="state">
      <Icon name={props.icon} size={28} />
      <p class="state-title">{props.title}</p>
      {props.hint !== undefined && <p class="state-hint">{props.hint}</p>}
      {props.children}
    </div>
  );
}
