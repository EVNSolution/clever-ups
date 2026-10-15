import { Fragment, type ComponentChildren, type JSX } from "preact";
import { useRef } from "preact/hooks";
import { Icon, type IconName } from "../ui/Icon";

export type GlyphTone = "order" | "driver" | "route" | "muted";

/** What the Detail area shows for the current selection. */
export interface DetailContent {
  title: string;
  icon: IconName;
  tone: GlyphTone;
  badges?: ComponentChildren;
  body: ComponentChildren;
}

export function Section(props: { title: string; children: ComponentChildren }): JSX.Element {
  return (
    <section>
      <h3 class="section-title">{props.title}</h3>
      {props.children}
    </section>
  );
}

export function DefinitionList(props: { entries: readonly (readonly [string, ComponentChildren])[] }): JSX.Element {
  return (
    <dl class="dl">
      {props.entries.map(([term, description]) => (
        <Fragment key={term}>
          <dt>{term}</dt>
          <dd>{description}</dd>
        </Fragment>
      ))}
    </dl>
  );
}

/** Area that opens beside the Plot. Escape or the close button closes it. */
export function DetailPanel(props: {
  content: DetailContent | null;
  onClose: () => void;
}): JSX.Element {
  // Keep the last content while the panel slides shut, so it does not flash empty.
  const last = useRef<DetailContent | null>(null);
  if (props.content !== null) last.current = props.content;
  const content = props.content ?? last.current;
  const open = props.content !== null;

  return (
    // Escape handler for keys that bubble up from the controls inside the panel.
    // oxlint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <aside
      class="detail"
      data-open={open}
      aria-label="상세 정보"
      inert={!open}
      onKeyDown={event => {
        if (event.key === "Escape") {
          event.stopPropagation();
          props.onClose();
        }
      }}
    >
      {content !== null && (
        <div class="detail-inner">
          <header class="detail-header">
            <span class="glyph" data-tone={content.tone} aria-hidden="true">
              <Icon name={content.icon} />
            </span>
            <div class="detail-heading">
              <h2 class="detail-title">{content.title}</h2>
              {content.badges !== undefined && <div class="detail-subtitle">{content.badges}</div>}
            </div>
            <button type="button" class="icon-btn" aria-label="상세 정보 닫기" title="닫기 (Esc)" onClick={props.onClose}>
              <Icon name="close" />
            </button>
          </header>
          <div class="detail-body">{content.body}</div>
        </div>
      )}
    </aside>
  );
}
