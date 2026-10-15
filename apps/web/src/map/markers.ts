import { MapPin, Truck } from "lucide-preact";
import { h, render } from "preact";

/**
 * Marker images for the map. An order is the lucide MapPin; a driver is the lucide Truck on a round badge. Both are
 * drawn into a canvas, so the map and the icons of the page share one library. Colors come from the CSS tokens.
 * Each kind has its own shape; color alone never carries the meaning.
 */
export const ICONS = ["order", "driver"] as const;
export type MarkerIcon = (typeof ICONS)[number];

/** Canvas pixels. The map draws these at pixel ratio 2, so the marker is SIZE / 2 CSS pixels wide. */
export const SIZE = 64;
export const PIXEL_RATIO = 2;

const TOKENS: Record<MarkerIcon, string> = { order: "--m-order", driver: "--m-driver" };

const token = (name: string): string => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

/** The icon as an element: rendered by the library, read back, and given to an image. */
function iconUrl(icon: MarkerIcon, color: string): string {
  const holder = document.createElement("div");
  const glyph =
    icon === "order"
      ? h(MapPin, { size: SIZE - 8, color: "#ffffff", fill: color, strokeWidth: 2 })
      : h(Truck, { size: SIZE / 2, color: "#ffffff", strokeWidth: 2.25 });
  render(glyph, holder);
  const element = holder.firstElementChild;
  if (element === null) throw new Error("The pin icon did not render");
  const text = new XMLSerializer().serializeToString(element);
  render(null, holder);
  return URL.createObjectURL(new Blob([text], { type: "image/svg+xml" }));
}

function load(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("The pin image did not load"));
    image.src = url;
  });
}

const images = new Map<MarkerIcon, ImageData>();

/** Draws every marker once. The map waits for this before it adds the images. */
export async function loadMarkerImages(): Promise<ReadonlyMap<MarkerIcon, ImageData>> {
  for (const icon of ICONS) {
    if (images.has(icon)) continue;
    const color = token(TOKENS[icon]);
    const url = iconUrl(icon, color);
    try {
      const image = await load(url);
      const canvas = document.createElement("canvas");
      canvas.width = SIZE;
      canvas.height = SIZE;
      const context = canvas.getContext("2d");
      if (context === null) throw new Error("Canvas 2D is not available");
      context.shadowColor = "rgba(20, 20, 20, 0.35)";
      context.shadowBlur = 4;
      context.shadowOffsetY = 1;
      if (icon === "order") {
        context.drawImage(image, 4, 0, SIZE - 8, SIZE - 8);
      } else {
        // A round badge with a white ring, the truck in its middle; it sits centered on the driver's place.
        context.beginPath();
        context.arc(SIZE / 2, SIZE / 2, SIZE / 2 - 6, 0, Math.PI * 2);
        context.fillStyle = color;
        context.fill();
        context.shadowColor = "transparent";
        context.lineWidth = 3;
        context.strokeStyle = "#ffffff";
        context.stroke();
        context.drawImage(image, SIZE / 4, SIZE / 4, SIZE / 2, SIZE / 2);
      }
      images.set(icon, context.getImageData(0, 0, SIZE, SIZE));
    } finally {
      URL.revokeObjectURL(url);
    }
  }
  return images;
}
