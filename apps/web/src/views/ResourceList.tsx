import type { ComponentChildren, JSX } from "preact";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { describeError } from "../api/http";
import { formatTime } from "../format";
import { subject } from "../josa";
import type { Resource } from "../state/useResource";
import { IconButton, Notice, SearchField, ToggleChip } from "../ui/Controls";
import { EmptyState, Skeleton } from "../ui/States";
import { ListBox } from "../ui/ListBox";

const PAGE_SIZE = 100;

export interface ResourceFilter<T> {
  id: string;
  label: string;
  test: (item: T) => boolean;
}

/**
 * Search, filter chips, states and list for one resource.
 * `searchText`, `filters` and `renderOption` must be stable references (define them at module level).
 */
export function ResourceList<T extends { id: string }>(props: {
  noun: string;
  resource: Resource<T>;
  searchPlaceholder: string;
  searchText: (item: T) => string;
  filters?: readonly ResourceFilter<T>[];
  renderOption: (item: T) => ComponentChildren;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  emptyHint: string;
  /** Name of the center the list is scoped to, shown in the footer. */
  scope?: string | undefined;
  /** Extra buttons at the end of the toolbar. */
  tools?: ComponentChildren;
  /** Filter IDs on when the list mounts. */
  initialFilters?: readonly string[] | undefined;
}): JSX.Element {
  const { resource, noun } = props;
  const filters = props.filters ?? [];
  const [query, setQuery] = useState("");
  const [active, setActive] = useState<ReadonlySet<string>>(() => new Set(props.initialFilters));
  const [shown, setShown] = useState(PAGE_SIZE);
  const bodyRef = useRef<HTMLDivElement>(null);

  const items = useMemo(() => resource.items ?? [], [resource.items]);

  const matched = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const tests = filters.filter(filter => active.has(filter.id));
    return items.filter(
      item =>
        tests.every(filter => filter.test(item)) &&
        (needle === "" || props.searchText(item).toLowerCase().includes(needle)),
    );
  }, [items, query, active, filters, props.searchText]);

  const filterCounts = useMemo(
    () => filters.map(filter => items.reduce((count, item) => count + (filter.test(item) ? 1 : 0), 0)),
    [items, filters],
  );

  useEffect(() => setShown(PAGE_SIZE), [query, active]);

  // A selection made on the map may sit beyond the rows drawn so far: draw up to it, then scroll to it.
  useEffect(() => {
    if (props.selectedId === null) return;
    const index = matched.findIndex(item => item.id === props.selectedId);
    if (index >= shown) setShown(index + PAGE_SIZE);
  }, [props.selectedId, matched, shown]);

  useEffect(() => {
    bodyRef.current?.querySelector('[role="option"][aria-selected="true"]')?.scrollIntoView({ block: "nearest" });
  }, [props.selectedId, shown]);

  const filtering = query.trim() !== "" || active.size > 0;
  const clearFilters = (): void => {
    setQuery("");
    setActive(new Set());
  };
  const toggleFilter = (id: string): void =>
    setActive(previous => {
      const next = new Set(previous);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  const failure = resource.error === null ? null : describeError(resource.error);

  // "전체" would be wrong when the source handed over only part of the data.
  const countText = (): string => {
    const total = resource.incomplete ? `불러온 ${items.length}건(일부)` : `전체 ${items.length}건`;
    return filtering ? `${total} 중 ${matched.length}건` : total;
  };

  const body = (): JSX.Element => {
    if (resource.items === null && resource.loading) return <Skeleton />;
    if (resource.items === null) {
      return (
        <EmptyState icon="alert" title={`${noun} 목록을 불러오지 못했습니다`} hint={`${failure?.cause ?? ""} ${failure?.action ?? ""}`}>
          <button type="button" class="btn btn-outline" onClick={resource.reload}>
            다시 시도
          </button>
        </EmptyState>
      );
    }
    if (items.length === 0) {
      return <EmptyState icon="inbox" title={`표시할 ${subject(noun)} 없습니다`} hint={props.emptyHint} />;
    }
    if (matched.length === 0) {
      return (
        <EmptyState icon="search" title={`조건에 맞는 ${subject(noun)} 없습니다`} hint="검색어를 바꾸거나 필터를 해제해 보세요.">
          <button type="button" class="btn btn-outline" onClick={clearFilters}>
            검색·필터 지우기
          </button>
        </EmptyState>
      );
    }
    const visible = matched.slice(0, shown);
    return (
      <>
        <ListBox
          label={`${noun} 목록`}
          items={visible}
          getId={item => item.id}
          selectedId={props.selectedId}
          onSelect={props.onSelect}
          onEscape={() => props.onSelect(null)}
          renderOption={props.renderOption}
        />
        {matched.length > visible.length && (
          <button type="button" class="btn btn-soft more" onClick={() => setShown(shown + PAGE_SIZE)}>
            더 보기 ({matched.length - visible.length}건 남음)
          </button>
        )}
      </>
    );
  };

  return (
    <>
      <div class="toolbar">
        <SearchField
          label={`${noun} 검색`}
          placeholder={props.searchPlaceholder}
          value={query}
          onInput={setQuery}
        />
        <IconButton icon="refresh" label={`${noun} 목록 새로고침`} busy={resource.loading} onClick={resource.reload} />
        {props.tools}
      </div>
      {filters.length > 0 && resource.items !== null && filterCounts.some((count, index) => count > 0 || active.has(filters[index]?.id ?? "")) && (
        <div class="chips">
          {filters.map((filter, index) =>
            (filterCounts[index] ?? 0) > 0 || active.has(filter.id) ? (
            <ToggleChip
              key={filter.id}
              label={filter.label}
              count={filterCounts[index] ?? 0}
              pressed={active.has(filter.id)}
              onToggle={() => toggleFilter(filter.id)}
            />
            ) : null,
          )}
        </div>
      )}
      {failure !== null && resource.items !== null && (
        <div class="list-banner">
          <Notice tone="danger" icon="alert" role="alert" title={`${noun} 목록을 새로 불러오지 못했습니다`}>
            <p>
              {failure.cause} 마지막으로 불러온 {items.length}건을 표시합니다.
            </p>
            <p>{failure.action}</p>
            <div class="notice-actions">
              <button type="button" class="btn btn-outline" onClick={resource.reload}>
                다시 시도
              </button>
            </div>
          </Notice>
        </div>
      )}
      {resource.incomplete && resource.items !== null && (
        <div class="list-banner">
          <Notice tone="warn" icon="alert" title="일부만 불러왔을 수 있습니다">
            데이터 출처가 일부만 돌려줘서 이 목록이 전부가 아닐 수 있습니다.
          </Notice>
        </div>
      )}
      <div class="list-body" ref={bodyRef}>
        {body()}
      </div>
      <div class="list-footer" aria-live="polite">
        <span>
          {resource.items === null ? "" : `${props.scope === undefined ? "" : `${props.scope} · `}${countText()}`}
        </span>
        <span>
          {resource.loading
            ? "갱신 중…"
            : resource.updatedAt !== null
              ? `갱신 ${formatTime(resource.updatedAt)}`
              : ""}
        </span>
      </div>
    </>
  );
}
