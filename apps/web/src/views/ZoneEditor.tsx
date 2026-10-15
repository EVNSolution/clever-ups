import type { JSX } from "preact";
import { useCallback, useEffect, useMemo, useState } from "preact/hooks";
import { describeError } from "../api/http";
import { createZone, drawZone, listBoundaries, listZones, shapeZone, type Boundary, type Zone } from "../api/zones";
import { Section, type DetailContent } from "../detail/DetailPanel";
import type { MapShape } from "../map/MapView";
import { Badge, Notice } from "../ui/Controls";

type Box = [number, number, number, number];

/** Every [lng, lat] of a shape, to fit the camera to it. */
export function cornersOf(shape: Zone["shape"]): [number, number][] {
  if (shape === null || shape === undefined) return [];
  const rings = shape.type === "Polygon" ? (shape.coordinates as number[][][]) : (shape.coordinates as number[][][][]).flat();
  return rings.flat().map(point => [point[0] ?? 0, point[1] ?? 0]);
}

export interface ZoneEditor {
  open: boolean;
  start: () => void;
  close: () => void;
  detail: DetailContent | null;
  shapes: MapShape[];
  onSelectShape: (id: string) => void;
  onView: (bbox: Box) => void;
  /** Set while a zone is drawn by hand, for the map's draw mode. */
  draw: { snap: boolean; points: readonly [number, number][]; onAdd: (point: [number, number]) => void } | undefined;
  /** Shown on the map while boundaries are drawn: the license asks for it. */
  attribution: string | null;
}

/**
 * The 권역 editor in the Detail panel. A 권역 is made of 시 boundaries, a 세부권역 of 행정동 boundaries inside its
 * 권역: click boundaries on the map to pick them, then save. The server cuts what other zones of the same level
 * hold, so zones never overlap. Drawing by hand comes next.
 */
export function useZoneEditor(
  centerId: string | undefined,
  onFit: (corners: [number, number][]) => void,
  /** Called after a zone is made or its shape saved, so the lists read the zones again. */
  onChanged: () => void,
): ZoneEditor {
  const [open, setOpen] = useState(false);
  const [zones, setZones] = useState<Zone[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const [boundaries, setBoundaries] = useState<{ items: Boundary[]; incomplete: boolean } | null>(null);
  const [view, setView] = useState<Box | null>(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  // Drawing by hand instead of picking boundaries. Snap is on unless turned off.
  const [drawing, setDrawing] = useState(false);
  const [corners, setCorners] = useState<[number, number][]>([]);
  const [snapOn, setSnapOn] = useState(true);

  const reload = useCallback((): void => {
    if (centerId === undefined) return;
    listZones(centerId).then(setZones, (error: unknown) => setProblem(describeError(error).cause));
  }, [centerId]);
  useEffect(() => {
    if (open) reload();
  }, [open, reload]);
  useEffect(() => setOpen(false), [centerId]);

  const target = zones?.find(zone => zone.id === editing) ?? null;
  const level = target?.parentId ? "DONG" : "SI";
  useEffect(() => {
    if (target === null || view === null) {
      setBoundaries(null);
      return;
    }
    let live = true;
    listBoundaries(level, view).then(
      found => live && setBoundaries(found),
      (error: unknown) => live && setProblem(describeError(error).cause),
    );
    return () => {
      live = false;
    };
  }, [target?.id, level, view]);

  const edit = (zone: Zone): void => {
    setEditing(zone.id);
    setPicked(new Set());
    setDrawing(false);
    setCorners([]);
    setProblem(null);
    const parent = zones?.find(item => item.id === zone.parentId);
    const corners = cornersOf(zone.shape).length > 0 ? cornersOf(zone.shape) : cornersOf(parent?.shape);
    if (corners.length > 0) onFit(corners);
  };
  const stopEditing = (): void => {
    setEditing(null);
    setPicked(new Set());
    setDrawing(false);
    setCorners([]);
  };

  const save = async (): Promise<void> => {
    if (target === null) return;
    setBusy(true);
    setProblem(null);
    try {
      const outcome = drawing ? await drawZone(target.id, corners) : await shapeZone(target.id, [...picked]);
      if (outcome.kind === "refused") {
        setProblem(
          outcome.status === 409
            ? "저장하지 못했습니다. 다른 권역이 고른 경계를 모두 덮었거나, 상위 권역의 경계가 아직 없습니다."
            : `저장하지 못했습니다 (HTTP ${outcome.status}).`,
        );
        return;
      }
      stopEditing();
      reload();
      onChanged();
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };

  const make = async (): Promise<void> => {
    if (centerId === undefined) return;
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await createZone(centerId, name.trim(), parentId === "" ? null : parentId);
      if (outcome.kind === "refused") {
        setProblem(outcome.status === 409 ? "같은 이름의 권역이 이미 있습니다." : `만들지 못했습니다 (HTTP ${outcome.status}).`);
        return;
      }
      setName("");
      reload();
      onChanged();
      edit(outcome.zone);
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };

  const onSelectShape = useCallback(
    (id: string): void => {
      if (editing === null || drawing || !boundaries?.items.some(item => item.code === id)) return;
      setPicked(previous => {
        const next = new Set(previous);
        if (!next.delete(id)) next.add(id);
        return next;
      });
    },
    [editing, drawing, boundaries],
  );

  const shapes = useMemo<MapShape[]>(() => {
    if (!open || zones === null) return [];
    const drawn = zones.flatMap(zone => {
      if (!zone.shape || zone.id === editing) return [];
      // While editing, only the zones of the same level stand in the way.
      if (target !== null && (zone.parentId ?? null) !== (target.parentId ?? null)) return [];
      return [{ id: `zone:${zone.id}`, role: "zone" as const, geometry: zone.shape, label: zone.name }];
    });
    // The zone's saved shape, and for a 세부권역 the 권역 it must stay inside, as bold outlines.
    const parent = zones.find(zone => zone.id === target?.parentId);
    const own = [target, parent].flatMap(zone =>
      zone?.shape ? [{ id: `zone:${zone.id}`, role: "editing" as const, geometry: zone.shape }] : [],
    );
    const lines = (boundaries?.items ?? []).map(item => ({
      id: item.code,
      role: picked.has(item.code) ? ("picked" as const) : ("boundary" as const),
      geometry: item.shape,
      label: item.name.split(" ").at(-1),
    }));
    return [...lines, ...drawn, ...own];
  }, [open, zones, editing, target, boundaries, picked]);

  const tops = (zones ?? []).filter(zone => !zone.parentId);
  const row = (zone: Zone): JSX.Element => (
    <li key={zone.id} class="zone-edit-row">
      <span>{zone.name}</span>
      {!zone.shape && <Badge tone="warn">경계 없음</Badge>}
      <button type="button" class="btn btn-soft" disabled={busy} onClick={() => edit(zone)}>
        경계 고르기
      </button>
    </li>
  );

  const body = (
    <>
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert" title={problem}>
          <p>내용은 그대로 남아 있습니다.</p>
        </Notice>
      )}
      {target !== null ? (
        <Section title={`${target.name} 경계 정하기`}>
          <div class="approval-confirm">
            <fieldset class="seg zone-mode" aria-label="정하는 방법">
              {([false, true] as const).map(mode => (
                <button type="button" key={String(mode)} class="seg-tab" aria-pressed={drawing === mode} onClick={() => setDrawing(mode)}>
                  {mode ? "그리기" : "경계 고르기"}
                </button>
              ))}
            </fieldset>
            {drawing ? (
              <>
                <p>지도를 눌러 꼭짓점을 찍습니다. 꼭짓점 {corners.length}개.</p>
                <label class="zone-snap">
                  <input type="checkbox" checked={snapOn} onChange={event => setSnapOn(event.currentTarget.checked)} />
                  Snap: 보이는 경계와 권역의 모서리·선분에 붙입니다.
                </label>
                <div class="approval-actions">
                  <button type="button" class="btn btn-soft" disabled={corners.length === 0} onClick={() => setCorners(previous => previous.slice(0, -1))}>
                    마지막 점 지우기
                  </button>
                  <button type="button" class="btn btn-soft" disabled={corners.length === 0} onClick={() => setCorners([])}>
                    처음부터
                  </button>
                </div>
              </>
            ) : (
              <p>
                지도에서 {level === "SI" ? "시" : "행정동"} 경계를 눌러 더하거나 뺍니다. 고른 {picked.size}개.
              </p>
            )}
            <p>저장하면 이 모양으로 바뀝니다. 다른 권역과 겹치는 부분은 잘려 나가고, 세부권역은 상위 권역 안으로 잘립니다.</p>
            {boundaries?.incomplete && <p>경계가 너무 많습니다. 지도를 확대하세요.</p>}
            <div class="approval-actions">
              <button type="button" class="btn btn-soft" disabled={busy} onClick={stopEditing}>
                취소
              </button>
              <button
                type="button"
                class="btn btn-primary"
                disabled={busy || (drawing ? corners.length < 3 : picked.size === 0)}
                onClick={() => void save()}
              >
                {busy ? "저장 중" : "저장"}
              </button>
            </div>
          </div>
        </Section>
      ) : (
        <>
          <Section title="권역">
            {zones === null ? (
              <p>불러오는 중입니다.</p>
            ) : (
              <ul class="zone-edit-list">
                {tops.flatMap(zone => {
                  const children = zones.filter(child => child.parentId === zone.id);
                  return [
                    row(zone),
                    ...(children.length === 0
                      ? []
                      : [
                          <li key={`${zone.id}-children`}>
                            <ul class="zone-edit-list zone-edit-children">{children.map(row)}</ul>
                          </li>,
                        ]),
                  ];
                })}
              </ul>
            )}
          </Section>
          <Section title="새 권역">
            <div class="route-edit">
              <label class="route-edit-row">
                <span>이름</span>
                <input class="text-input" value={name} maxLength={40} onInput={event => setName(event.currentTarget.value)} />
              </label>
              <label class="route-edit-row">
                <span>상위</span>
                <select class="text-input" value={parentId} onChange={event => setParentId(event.currentTarget.value)}>
                  <option value="">없음(권역)</option>
                  {tops.map(zone => (
                    <option key={zone.id} value={zone.id}>
                      {zone.name}의 세부권역
                    </option>
                  ))}
                </select>
              </label>
              <button type="button" class="btn btn-primary" disabled={busy || name.trim() === ""} onClick={() => void make()}>
                만들기
              </button>
            </div>
          </Section>
        </>
      )}
    </>
  );

  return {
    open,
    start: () => setOpen(true),
    close: () => {
      setOpen(false);
      stopEditing();
    },
    detail: open ? { title: "권역 편집", icon: "edit", tone: "route", body } : null,
    shapes,
    onSelectShape,
    onView: setView,
    draw: target !== null && drawing ? { snap: snapOn, points: corners, onAdd: point => setCorners(previous => [...previous, point]) } : undefined,
    attribution: target === null ? null : "행정동 경계: 통계청 SGIS, 가공 vuski/admdongkor, CC BY 4.0",
  };
}
