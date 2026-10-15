import type { JSX } from "preact";
import { useCallback, useMemo, useState } from "preact/hooks";
import { describeError } from "../api/http";
import type { Center } from "../api/session";
import type { Resource } from "../state/useResource";
import { IconButton, SearchField } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import { ListBox } from "../ui/ListBox";
import { Popover } from "../ui/Popover";

/** Search appears when there are more centers than fit comfortably in the list. */
const SEARCH_FROM = 8;

function CenterOption(center: Center, selected: boolean): JSX.Element {
  return (
    <>
      <span class="option-title">
        {center.name}
        <span class="option-code">{center.id}</span>
      </span>
      {selected ? <Icon name="check" size={16} /> : <span />}
    </>
  );
}

/**
 * Picks the center to work in. One account can use several centers, so the list is the account's own.
 * One center shows as a plain field; two or more open a list.
 */
export function CenterSelect(props: {
  centers: Resource<Center>;
  selectedId: string | null;
  onSelect: (id: string) => void;
}): JSX.Element {
  const { centers, selectedId } = props;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [announcement, setAnnouncement] = useState("");

  const items = useMemo(() => centers.items ?? [], [centers.items]);
  const selected = items.find(center => center.id === selectedId);
  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return needle === "" ? items : items.filter(center => center.name.toLowerCase().includes(needle));
  }, [items, query]);

  const close = useCallback((restoreFocus: boolean) => {
    setOpen(false);
    setQuery("");
    if (restoreFocus) document.getElementById("center-trigger")?.focus();
  }, []);
  const dismiss = useCallback(() => close(false), [close]);

  if (centers.items === null && centers.loading) {
    return (
      <div class="center-field" aria-busy="true">
        <Icon name="building" size={16} />
        <span class="skeleton-block" style={{ width: "50%", height: 14 }} />
      </div>
    );
  }
  if (centers.items === null) {
    return (
      <div class="center-field" data-tone="danger">
        <Icon name="alert" size={16} />
        <span class="center-name">센터 목록을 불러오지 못했습니다</span>
        <IconButton icon="refresh" label={`센터 목록 다시 불러오기. ${describeError(centers.error).cause}`} onClick={centers.reload} />
      </div>
    );
  }
  if (items.length === 0) {
    return (
      <div class="center-field" data-tone="muted">
        <Icon name="building" size={16} />
        <span class="center-label">센터</span>
        <span class="center-name">선택할 수 있는 센터가 없습니다</span>
      </div>
    );
  }
  if (items.length === 1) {
    return (
      <div class="center-field">
        <Icon name="building" size={16} />
        <span class="center-label">센터</span>
        <span class="center-name">{items[0]?.name}</span>
      </div>
    );
  }

  const choose = (id: string): void => {
    props.onSelect(id);
    const name = items.find(center => center.id === id)?.name ?? "";
    setAnnouncement(`${name} 센터를 선택했습니다`);
    close(true);
  };

  return (
    <Popover
      open={open}
      onClose={dismiss}
      focusOnOpen={items.length > SEARCH_FROM ? ".popover-panel input" : '[role="option"][aria-selected="true"], [role="option"]'}
      trigger={
        <>
        <button
          id="center-trigger"
          type="button"
          class="center-btn"
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-label={`센터 선택. 현재 ${selected?.name ?? "선택 안 함"}`}
          onClick={() => (open ? close(false) : setOpen(true))}
          onKeyDown={event => {
            if (event.key === "ArrowDown" && !open) {
              event.preventDefault();
              setOpen(true);
            }
          }}
        >
          <Icon name="building" size={16} />
          <span class="center-label">센터</span>
          <span class="center-name">{selected?.name ?? "선택 안 함"}</span>
          <Icon name="chevron-down" size={16} />
        </button>
        <output class="sr-only">{announcement}</output>
        </>
      }
    >
      {items.length > SEARCH_FROM && (
        <div class="popover-search">
          <SearchField
            label="센터 검색"
            placeholder="센터 검색"
            value={query}
            onInput={setQuery}
            onKeyDown={event => {
              if (event.key === "Escape") close(true);
              if (event.key === "ArrowDown") {
                event.preventDefault();
                document.querySelector<HTMLElement>('.popover-panel [role="option"]')?.focus();
              }
            }}
          />
        </div>
      )}
      {shown.length === 0 ? (
        <p class="popover-empty">조건에 맞는 센터가 없습니다.</p>
      ) : (
        <ListBox
          label="센터"
          items={shown}
          getId={center => center.id}
          selectedId={selectedId}
          onSelect={choose}
          onEscape={() => close(true)}
          renderOption={CenterOption}
          optionClass="option option-compact"
        />
      )}
    </Popover>
  );
}
