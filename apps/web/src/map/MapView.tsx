import { LngLatBounds, Map as MapLibreMap, NavigationControl, Popup, setWorkerUrl, type ExpressionSpecification, type GeoJSONSource, type MapGeoJSONFeature } from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";
import "maplibre-gl/dist/maplibre-gl.css";
import type { JSX } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { loadMarkerImages, PIXEL_RATIO, type MarkerIcon } from "./markers";
import { edgesOf, snap } from "./snap";

const STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
const SOURCE = "points";
const LINE = "line";
const SHAPES = "shapes";
const DRAFT = "draft";
const OUTLINES = "outlines";
const SNAP_PIXELS = 12;

// Vite moves the library file, so the default worker path breaks. Point to the bundled worker.
setWorkerUrl(workerUrl);

export type PointKind = "order" | "driver";

export interface MapPoint {
  kind: PointKind;
  id: string;
  lng: number;
  lat: number;
  icon: MarkerIcon;
  label?: string | undefined;
  /** Drawn above the others. Used for the active tab's own kind. */
  priority?: boolean | undefined;
}

/**
 * A drawn polygon, such as an administrative boundary or a saved zone. `role` sets its look: a boundary to pick
 * (thin, dashed), a picked boundary, another zone, or the zone being edited (bold outline).
 */
export interface MapShape {
  id: string;
  role: "boundary" | "picked" | "zone" | "editing";
  /** A GeoJSON Polygon or MultiPolygon, as the API gives it. */
  geometry: { type: "Polygon" | "MultiPolygon"; coordinates: unknown[] };
  label?: string | undefined;
  /** Overrides the role's fill and edge colour, such as one colour per driver. */
  color?: string | undefined;
  /** Label and value lines shown beside the pointer while it is on the shape. */
  card?: readonly (readonly [string, string])[] | undefined;
}

/**
 * Lines over the shapes: a driver's outer edge (strong, in the driver's colour), or the 세부권역 edges inside a
 * 권역, drawn only from zoom 12 so the city view stays clean.
 */
export interface MapOutline {
  kind: "driver" | "detail";
  color?: string | undefined;
  segments: readonly (readonly [number, number])[][];
}

export type CameraCommand =
  | { nonce: number; kind: "fly"; lng: number; lat: number }
  | { nonce: number; kind: "fit"; coordinates: readonly (readonly [number, number])[] };

export interface MapViewProps {
  points: readonly MapPoint[];
  /** One road line under the markers, as [lng, lat] points, such as a driver's route. Empty for none. */
  line: readonly (readonly [number, number])[];
  selected: { kind: PointKind; id: string } | null;
  markerScale: number;
  autoCenter: boolean;
  command: CameraCommand | null;
  /** True while a page without the map is shown. The map stays alive so its camera is kept. */
  hidden: boolean;
  onSelect: (kind: PointKind, id: string) => void;
  shapes?: readonly MapShape[] | undefined;
  outlines?: readonly MapOutline[] | undefined;
  onSelectShape?: ((id: string) => void) | undefined;
  /**
   * Drawing a polygon: each click adds a corner. With `snap` a click near a corner or an edge of the shapes on
   * the map lands on it, so a drawn zone meets its neighbours without a gap.
   */
  draw?: { snap: boolean; points: readonly [number, number][]; onAdd: (point: [number, number]) => void } | undefined;
  /** Called with [west, south, east, north] when the map stops moving, and once when it is ready. */
  onView?: ((bbox: [number, number, number, number]) => void) | undefined;
  /** Label and value lines shown while the pointer is on a marker. Null or empty shows nothing. */
  describe?: ((kind: PointKind, id: string) => readonly (readonly [string, string])[] | null) | undefined;
}

/** A small card of label and value lines, built with text nodes only. */
function card(rows: readonly (readonly [string, string])[]): HTMLElement {
  const list = document.createElement("dl");
  for (const [label, value] of rows) {
    const term = document.createElement("dt");
    term.textContent = label;
    const detail = document.createElement("dd");
    detail.textContent = value;
    list.append(term, detail);
  }
  return list;
}

const keyOf = (kind: string, id: string): string => `${kind}:${id}`;
const motionDuration = (): number => (matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 600);

function toCollection(points: readonly MapPoint[]): GeoJSON.FeatureCollection<GeoJSON.Point> {
  return {
    type: "FeatureCollection",
    features: points.map((point, index) => ({
      type: "Feature",
      id: index,
      properties: { kind: point.kind, id: point.id, icon: point.icon, label: point.label ?? "", z: point.priority ? 2 : 1 },
      geometry: { type: "Point", coordinates: [point.lng, point.lat] },
    })),
  };
}

function toLine(line: readonly (readonly [number, number])[]): GeoJSON.FeatureCollection<GeoJSON.LineString> {
  return {
    type: "FeatureCollection",
    features: line.length < 2 ? [] : [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: line.map(([lng, lat]) => [lng, lat]) } }],
  };
}

/** The polygon being drawn: its area, its outline to the pointer, its corners and the snap mark. */
function toDraft(points: readonly [number, number][], pointer: [number, number] | null, snapped: boolean): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = points.map(point => ({
    type: "Feature",
    properties: { kind: "corner" },
    geometry: { type: "Point", coordinates: point },
  }));
  const path = pointer === null ? [...points] : [...points, pointer];
  if (points.length >= 3) {
    features.push({ type: "Feature", properties: { kind: "area" }, geometry: { type: "Polygon", coordinates: [[...points, points[0] ?? [0, 0]]] } });
  }
  if (path.length >= 2) features.push({ type: "Feature", properties: { kind: "path" }, geometry: { type: "LineString", coordinates: path } });
  if (pointer !== null && snapped) features.push({ type: "Feature", properties: { kind: "snap" }, geometry: { type: "Point", coordinates: pointer } });
  return { type: "FeatureCollection", features };
}

function toOutlines(outlines: readonly MapOutline[]): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: outlines.map(outline => ({
      type: "Feature",
      properties: { kind: outline.kind, color: outline.color ?? "#5f5f5f" },
      geometry: { type: "MultiLineString", coordinates: outline.segments.map(segment => segment.map(point => [...point])) },
    })),
  };
}

function toShapes(shapes: readonly MapShape[]): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: shapes.map(shape => ({
      type: "Feature",
      properties: { id: shape.id, role: shape.role, label: shape.label ?? "", ...(shape.color === undefined ? {} : { color: shape.color }) },
      geometry: shape.geometry as GeoJSON.Polygon | GeoJSON.MultiPolygon,
    })),
  };
}

export function MapView(props: MapViewProps): JSX.Element {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const indexRef = useRef(new Map<string, number>());
  const centeredOn = useRef<string | null>(null);
  const latest = useRef(props);
  latest.current = props;
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const element = container.current;
    if (element === null) return;
    const map = new MapLibreMap({ container: element, style: STYLE_URL, center: [127.0, 37.55], zoom: 10.5 });
    // Bottom right, so the zoom buttons sit just above the attribution.
    map.addControl(new NavigationControl({ showCompass: false }), "bottom-right");
    mapRef.current = map;

    map.on("load", async () => {
      const images = await loadMarkerImages();
      if (mapRef.current !== map) return;
      for (const [icon, image] of images) map.addImage(icon, image, { pixelRatio: PIXEL_RATIO });
      const zone = getComputedStyle(document.documentElement).getPropertyValue("--m-zone").trim();
      const selectedColor = getComputedStyle(document.documentElement).getPropertyValue("--m-selected").trim();
      // Boundaries and zones. Hovering a shape makes its edge strong, so the one a click picks is clear.
      const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
      map.addSource(SHAPES, { type: "geojson", data: toShapes([]), promoteId: "id" });
      const fillColor: ExpressionSpecification = [
        "case",
        ["has", "color"],
        ["to-color", ["get", "color"]],
        ["match", ["get", "role"], "picked", accent, "zone", zone, "#ffffff"],
      ];
      const isHovered: ExpressionSpecification = ["boolean", ["feature-state", "hover"], false];
      // The shape under the pointer rises: a soft shadow below it, and a copy drawn a little up and left.
      map.addLayer({
        id: "shape-shadow",
        type: "fill",
        source: SHAPES,
        paint: { "fill-color": "#000000", "fill-opacity": ["case", isHovered, 0.18, 0], "fill-translate": [4, 6] },
      });
      map.addLayer({
        id: "shape-fill",
        type: "fill",
        source: SHAPES,
        paint: {
          "fill-color": fillColor,
          "fill-opacity": ["match", ["get", "role"], "picked", 0.35, "zone", 0.18, "editing", 0, 0.05],
        },
      });
      map.addLayer({
        id: "shape-line",
        type: "line",
        source: SHAPES,
        paint: {
          "line-color": [
            "case",
            ["has", "color"],
            ["to-color", ["get", "color"]],
            ["match", ["get", "role"], "picked", accent, "editing", selectedColor, "zone", zone, "#3d3d3d"],
          ],
          "line-width": [
            "case",
            isHovered,
            3,
            ["match", ["get", "role"], "editing", 3, "picked", 2, 1],
          ],
          "line-opacity": ["case", ["boolean", ["feature-state", "hover"], false], 1, 0.7],
        },
      });
      map.addLayer({
        id: "shape-lift",
        type: "fill",
        source: SHAPES,
        // Light, so a large zone under the pointer does not cover the map; the raised edge carries the effect.
        paint: { "fill-color": fillColor, "fill-opacity": ["case", isHovered, 0.08, 0], "fill-translate": [-2, -3] },
      });
      map.addLayer({
        id: "shape-lift-line",
        type: "line",
        source: SHAPES,
        paint: {
          "line-color": fillColor,
          "line-width": 3,
          "line-opacity": ["case", isHovered, 1, 0],
          "line-translate": [-2, -3],
        },
      });
      map.addSource(OUTLINES, { type: "geojson", data: toOutlines([]) });
      map.addLayer({
        id: "outline-detail",
        type: "line",
        source: OUTLINES,
        minzoom: 12,
        filter: ["==", ["get", "kind"], "detail"],
        paint: { "line-color": "#3a3a3a", "line-width": 1.5, "line-opacity": 0.8, "line-dasharray": [3, 2] },
      });
      map.addLayer({
        id: "outline-driver",
        type: "line",
        source: OUTLINES,
        filter: ["==", ["get", "kind"], "driver"],
        layout: { "line-join": "round" },
        paint: { "line-color": ["to-color", ["get", "color"]], "line-width": 3.5 },
      });
      map.addLayer({
        id: "shape-label",
        type: "symbol",
        source: SHAPES,
        layout: { "text-field": ["get", "label"], "text-font": ["Noto Sans Regular"], "text-size": 11 },
        paint: { "text-color": "#1b1b1b", "text-halo-color": "#ffffff", "text-halo-width": 1.5 },
      });
      let hovered: string | number | undefined;
      const hover = (id: string | number | undefined): void => {
        if (hovered !== undefined) map.setFeatureState({ source: SHAPES, id: hovered }, { hover: false });
        hovered = id;
        if (id !== undefined) map.setFeatureState({ source: SHAPES, id }, { hover: true });
      };
      // A boundary wins over a zone outline drawn over it, so picking boundaries works inside a 권역.
      const pickable = (features: MapGeoJSONFeature[] | undefined): MapGeoJSONFeature | undefined =>
        features?.find(feature => feature.properties["role"] === "boundary" || feature.properties["role"] === "picked") ??
        features?.[0];
      // A shape with a card shows it beside the pointer, as an order pin does.
      const shapeCard = new Popup({ closeButton: false, closeOnClick: false, offset: 14, maxWidth: "280px", className: "pin-card" });
      let carded: unknown = null;
      map.on("mousemove", "shape-fill", event => {
        const feature = pickable(event.features);
        hover(feature?.id);
        const id: unknown = feature?.properties["id"];
        const rows = latest.current.shapes?.find(shape => shape.id === id)?.card;
        if (rows === undefined || rows.length === 0) {
          shapeCard.remove();
          carded = null;
          return;
        }
        if (carded !== id) shapeCard.setDOMContent(card(rows));
        carded = id;
        shapeCard.setLngLat(event.lngLat).addTo(map);
      });
      map.on("mouseleave", "shape-fill", () => {
        hover(undefined);
        shapeCard.remove();
        carded = null;
      });
      map.on("click", "shape-fill", event => {
        const id: unknown = pickable(event.features)?.properties["id"];
        if (typeof id === "string") latest.current.onSelectShape?.(id);
      });
      // The polygon being drawn, above the shapes.
      map.addSource(DRAFT, { type: "geojson", data: toDraft([], null, false) });
      map.addLayer({ id: "draft-fill", type: "fill", source: DRAFT, filter: ["==", ["get", "kind"], "area"], paint: { "fill-color": selectedColor, "fill-opacity": 0.2 } });
      map.addLayer({
        id: "draft-line",
        type: "line",
        source: DRAFT,
        filter: ["==", ["get", "kind"], "path"],
        paint: { "line-color": selectedColor, "line-width": 2, "line-dasharray": [2, 1] },
      });
      map.addLayer({
        id: "draft-points",
        type: "circle",
        source: DRAFT,
        filter: ["in", ["get", "kind"], ["literal", ["corner", "snap"]]],
        paint: {
          "circle-radius": ["match", ["get", "kind"], "snap", 7, 4],
          "circle-color": ["match", ["get", "kind"], "snap", "rgba(0,0,0,0)", "#ffffff"],
          "circle-stroke-color": selectedColor,
          "circle-stroke-width": 2,
        },
      });
      // Where a click would land now: snapped to a corner or an edge of the shapes in view, or the pointer itself.
      const landing = (point: { x: number; y: number }, lngLat: { lng: number; lat: number }): { at: [number, number]; snapped: boolean } => {
        const draw = latest.current.draw;
        if (draw?.snap) {
          const reach = map.unproject([point.x - SNAP_PIXELS, point.y + SNAP_PIXELS]);
          const far = map.unproject([point.x + SNAP_PIXELS, point.y - SNAP_PIXELS]);
          const geometries = (latest.current.shapes ?? []).map(shape => shape.geometry);
          const corners = draw.points.length > 0 ? [{ type: "Polygon", coordinates: [[...draw.points]] }] : [];
          const hit = snap(point, edgesOf([...geometries, ...corners]), (lng, lat) => map.project([lng, lat]), [reach.lng, reach.lat, far.lng, far.lat], SNAP_PIXELS);
          if (hit !== null) return { at: hit, snapped: true };
        }
        return { at: [lngLat.lng, lngLat.lat], snapped: false };
      };
      map.on("mousemove", event => {
        const draw = latest.current.draw;
        if (draw === undefined) return;
        const { at, snapped } = landing(event.point, event.lngLat);
        map.getSource<GeoJSONSource>(DRAFT)?.setData(toDraft(draw.points, at, snapped));
      });
      map.on("click", event => {
        const draw = latest.current.draw;
        if (draw === undefined) return;
        draw.onAdd(landing(event.point, event.lngLat).at);
      });
      const tellView = (): void => {
        const box = map.getBounds();
        latest.current.onView?.([box.getWest(), box.getSouth(), box.getEast(), box.getNorth()]);
      };
      map.on("moveend", tellView);
      tellView();
      const lineColor = getComputedStyle(document.documentElement).getPropertyValue("--m-order").trim();
      map.addSource(LINE, { type: "geojson", data: toLine([]) });
      map.addLayer({
        id: "line",
        type: "line",
        source: LINE,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: { "line-color": lineColor, "line-width": 4, "line-opacity": 0.75 },
      });
      map.addSource(SOURCE, { type: "geojson", data: toCollection([]) });
      map.addLayer({
        id: "halo",
        type: "circle",
        source: SOURCE,
        paint: {
          "circle-radius": ["case", ["boolean", ["feature-state", "selected"], false], 19, 0],
          // The pin's body is above the point it marks; the halo goes to the body, not to the tip.
          "circle-translate": [0, -14],
          "circle-color": "rgba(217, 72, 15, 0.18)",
          "circle-stroke-color": selectedColor,
          "circle-stroke-width": 3,
          "circle-stroke-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 1, 0],
        },
      });
      map.addLayer({
        id: "markers",
        type: "symbol",
        source: SOURCE,
        layout: {
          "icon-image": ["get", "icon"],
          // A pin stands on its place; a driver badge is centered on it.
          "icon-anchor": ["match", ["get", "icon"], "driver", "center", "bottom"],
          "icon-size": latest.current.markerScale,
          "icon-allow-overlap": true,
          "icon-ignore-placement": true,
          "symbol-sort-key": ["get", "z"],
          // A short label, such as the visiting number, sits at the pin's upper right. Never an address.
          "text-field": ["get", "label"],
          "text-font": ["Noto Sans Bold"],
          "text-size": 12,
          "text-anchor": "left",
          "text-offset": [0.7, -2.2],
          "text-allow-overlap": true,
          "text-ignore-placement": true,
        },
        paint: { "text-color": "#141414", "text-halo-color": "#ffffff", "text-halo-width": 2 },
      });
      map.on("click", "markers", event => {
        const properties = event.features?.[0]?.properties;
        if (properties === undefined) return;
        const kind: unknown = properties["kind"];
        const id: unknown = properties["id"];
        if ((kind === "order" || kind === "driver") && typeof id === "string") latest.current.onSelect(kind, id);
      });
      // A card beside the marker under the pointer. It follows the pointer's marker and goes when the pointer leaves.
      const popup = new Popup({ closeButton: false, closeOnClick: false, offset: 18, maxWidth: "320px", className: "pin-card" });
      let shown: string | null = null;
      map.on("mousemove", "markers", event => {
        const feature = event.features?.[0];
        const kind: unknown = feature?.properties["kind"];
        const id: unknown = feature?.properties["id"];
        if (feature === undefined || (kind !== "order" && kind !== "driver") || typeof id !== "string") return;
        if (shown === keyOf(kind, id)) return;
        const rows = latest.current.describe?.(kind, id);
        shown = keyOf(kind, id);
        if (rows === null || rows === undefined || rows.length === 0 || feature.geometry.type !== "Point") {
          popup.remove();
          return;
        }
        const [lng, lat] = feature.geometry.coordinates;
        if (lng === undefined || lat === undefined) return;
        popup.setLngLat([lng, lat]).setDOMContent(card(rows)).addTo(map);
      });
      map.on("mouseenter", "markers", () => {
        map.getCanvas().style.cursor = "pointer";
      });
      map.on("mouseleave", "markers", () => {
        map.getCanvas().style.cursor = "";
        shown = null;
        popup.remove();
      });
      setReady(true);
    });

    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (map === null || !ready) return;
    indexRef.current = new Map(props.points.map((point, index) => [keyOf(point.kind, point.id), index]));
    map.getSource<GeoJSONSource>(SOURCE)?.setData(toCollection(props.points));
  }, [ready, props.points]);

  useEffect(() => {
    if (!ready) return;
    mapRef.current?.getSource<GeoJSONSource>(LINE)?.setData(toLine(props.line));
  }, [ready, props.line]);

  useEffect(() => {
    if (!ready) return;
    mapRef.current?.getSource<GeoJSONSource>(SHAPES)?.setData(toShapes(props.shapes ?? []));
  }, [ready, props.shapes]);

  useEffect(() => {
    if (!ready) return;
    mapRef.current?.getSource<GeoJSONSource>(OUTLINES)?.setData(toOutlines(props.outlines ?? []));
  }, [ready, props.outlines]);

  // While drawing, a double click must not zoom and the pointer is a cross.
  const drawing = props.draw !== undefined;
  useEffect(() => {
    const map = mapRef.current;
    if (map === null || !ready) return;
    if (drawing) map.doubleClickZoom.disable();
    else map.doubleClickZoom.enable();
    map.getCanvas().style.cursor = drawing ? "crosshair" : "";
    map.getSource<GeoJSONSource>(DRAFT)?.setData(toDraft(props.draw?.points ?? [], null, false));
  }, [ready, drawing, props.draw?.points]);

  useEffect(() => {
    const map = mapRef.current;
    if (map === null || !ready) return;
    map.removeFeatureState({ source: SOURCE });
    const { selected } = props;
    if (selected === null) return;
    const index = indexRef.current.get(keyOf(selected.kind, selected.id));
    if (index !== undefined) map.setFeatureState({ source: SOURCE, id: index }, { selected: true });
  }, [ready, props.selected?.kind, props.selected?.id, props.points]);

  // Pan to a newly selected marker only when it is out of view. Zoom is kept, so the map keeps its place.
  useEffect(() => {
    const map = mapRef.current;
    if (map === null || !ready) return;
    const { selected, autoCenter, points } = props;
    const key = selected === null ? null : keyOf(selected.kind, selected.id);
    if (key === centeredOn.current) return;
    centeredOn.current = key;
    if (key === null || !autoCenter) return;
    const point = points.find(candidate => keyOf(candidate.kind, candidate.id) === key);
    if (point === undefined) return;
    if (map.getBounds().contains([point.lng, point.lat])) return;
    map.easeTo({ center: [point.lng, point.lat], zoom: Math.max(map.getZoom(), 10), duration: motionDuration() });
  }, [ready, props.selected?.kind, props.selected?.id, props.points]);

  useEffect(() => {
    const map = mapRef.current;
    if (map === null || !ready) return;
    map.setLayoutProperty("markers", "icon-size", props.markerScale);
  }, [ready, props.markerScale]);

  useEffect(() => {
    const map = mapRef.current;
    const { command } = props;
    if (map === null || !ready || command === null) return;
    if (command.kind === "fly") {
      map.easeTo({ center: [command.lng, command.lat], zoom: Math.max(map.getZoom(), 14), duration: motionDuration() });
      return;
    }
    if (command.coordinates.length === 0) return;
    const bounds = new LngLatBounds();
    for (const coordinate of command.coordinates) bounds.extend([coordinate[0], coordinate[1]]);
    map.fitBounds(bounds, { padding: 72, maxZoom: 15, duration: motionDuration() });
  }, [ready, props.command?.nonce]);

  useEffect(() => {
    if (props.hidden) return;
    // The container was display:none, so MapLibre needs to measure it again.
    const frame = requestAnimationFrame(() => mapRef.current?.resize());
    return () => cancelAnimationFrame(frame);
  }, [props.hidden]);

  return (
    <div class="map" ref={container}>
      <p class="sr-only">지도에 표시된 항목은 왼쪽 목록에서도 확인할 수 있습니다.</p>
    </div>
  );
}
