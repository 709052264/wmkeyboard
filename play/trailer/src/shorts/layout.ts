import { FPS } from "../theme";
import type { Beat, CarouselItem, ClipBeat, Focus, ShortSpec } from "./types";

// Feed UI overlays on TikTok / Reels / Shorts: tabs on top, caption and
// music ticker at the bottom, like/share rail on the right. The side insets
// are kept symmetric (the rail's 140 on both sides) so every text block,
// card and chip centres on the frame at x 540.
export const SAFE = { top: 220, bottom: 420, right: 140, left: 140 } as const;
export const SAFE_W = 1080 - SAFE.left - SAFE.right; // 800
export const SAFE_CX = 540;
export const SAFE_BOTTOM_Y = 1920 - SAFE.bottom; // 1500

// Card-look geometry; the other looks set their own in looks.ts.
// Headline / hook band at the top of the safe box.
export const TEXT_ZONE = { top: SAFE.top + 10, height: 340 } as const;
// Screen card below it.
export const CARD = { top: 590, height: 890, maxWidth: 860 } as const;
// Spoken captions and meme reactions sit on the lower part of the card.
export const LOWER_BAND = { bottom: SAFE.bottom + 50, height: 260 } as const;

export const sec = (s: number) => Math.round(s * FPS);

export const CAROUSEL_ITEM_SEC = 1.25;

export const itemBeat = (item: CarouselItem): ClipBeat => ({
  ...item,
  sec: item.sec ?? CAROUSEL_ITEM_SEC,
});

export const beatFrames = (b: Beat): number =>
  "carousel" in b
    ? b.carousel.reduce((n, item) => n + sec(itemBeat(item).sec), 0)
    : sec(b.sec);

// With no caption on beat 0 the hook holds through it, so the top band is never empty.
export const hookFrames = (spec: ShortSpec) =>
  spec.hookSec !== undefined
    ? sec(spec.hookSec)
    : spec.beats[0]?.caption || !spec.beats[0]
      ? sec(2)
      : Math.max(sec(2), beatFrames(spec.beats[0]));
export const outroFrames = (spec: ShortSpec) => sec(spec.outroSec ?? 2);

export const beatStarts = (beats: Beat[]): number[] =>
  beats.reduce<number[]>(
    (acc, b, i) => [
      ...acc,
      i === 0 ? 0 : acc[i - 1] + beatFrames(beats[i - 1]),
    ],
    [],
  );

export const beatsFrames = (spec: ShortSpec) =>
  spec.beats.reduce((n, b) => n + beatFrames(b), 0);

export const shortDuration = (spec: ShortSpec) =>
  beatsFrames(spec) + outroFrames(spec);

const PRESETS: Record<string, Focus> = {
  keyboard: { zoom: 2, y: 1, toZoom: 2.15, toY: 1 },
  full: { zoom: 1, toZoom: 1.08, y: 0.5 },
  top: { zoom: 2, y: 0, toY: 0.25 },
};

export const resolveFocus = (f: ClipBeat["focus"]): Required<Focus> => {
  const base = typeof f === "string" ? PRESETS[f] : (f ?? PRESETS.keyboard);
  const zoom = base.zoom ?? 1;
  const y = base.y ?? 0.5;
  return { zoom, y, toZoom: base.toZoom ?? zoom * 1.06, toY: base.toY ?? y };
};
