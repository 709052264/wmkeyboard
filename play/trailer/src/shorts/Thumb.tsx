import React from "react";
import { AbsoluteFill, Img, OffthreadVideo, staticFile, useVideoConfig } from "remotion";
import { INTER } from "../fonts";
import { CLIP_H, CLIP_W, CYAN, VIOLET } from "../theme";
import { cropTop } from "./crops";
import { Canvas } from "./ShortScaffold";
import { Headline } from "./Headline";
import { itemBeat } from "./layout";
import { LookContext, resolveLook, useLook } from "./looks";
import type { Look, ShortSpec, Thumb as ThumbSpec } from "./types";

export type ThumbProps = {
  spec: ShortSpec;
  look?: Look; // overrides spec.look
};

// Lower half of the screen: suggestion strip and keyboard.
const DEFAULT_CROP: [number, number, number, number] = [0, 1240, 1080, 1060];

// spec.thumb, else the first beat with a zoom (the moment the script calls
// out), framed around its target, else the first clip beat at from + 1 s.
export const resolveThumb = (spec: ShortSpec): Required<ThumbSpec> => {
  const t = resolveThumbFrame(spec);
  // Rows above this (status bar, Chrome's toolbar) never reach a cover.
  return { ...t, cropTop: cropTop({ clip: t.clip, cropTop: spec.thumb?.cropTop }) };
};

const resolveThumbFrame = (spec: ShortSpec): Omit<Required<ThumbSpec>, "cropTop"> => {
  const title = spec.thumb?.title ?? spec.hook;
  if (spec.thumb) {
    return { clip: spec.thumb.clip, from: spec.thumb.from, title, zoom: spec.thumb.zoom ?? DEFAULT_CROP };
  }
  const beats = spec.beats.flatMap((b) => ("carousel" in b ? b.carousel.map(itemBeat) : [b]));
  const zoomed = beats.find((b) => b.zoom);
  if (zoomed?.zoom) {
    const [, zy, , zh] = zoomed.zoom.to;
    const cy = zy + zh / 2;
    const top = Math.min(Math.max(cy - 420, 0), 2400 - 860);
    return {
      clip: zoomed.clip,
      // a beat into the hold, in clip time
      from: (zoomed.from ?? 0) + (zoomed.zoom.at + 0.6) * (zoomed.rate ?? 1),
      title,
      zoom: [0, top, 1080, 860],
    };
  }
  const first = beats[0];
  return { clip: first?.clip ?? "", from: (first?.from ?? 0) + 1, title, zoom: DEFAULT_CROP };
};

// Title size from its length, so long hooks still fit in a few lines.
const titleSize = (text: string, wide: boolean) => {
  const n = text.replace(/[«»]/g, "").length;
  return wide ? Math.max(58, Math.min(104, 124 - n * 1.25)) : Math.max(80, Math.min(150, 166 - n * 1.6));
};

// One crisp frame of the clip, cropped to `rect` (source pixels) and scaled
// to cover a w x h box. Rows above `minY` never show: when the box needs
// more height than the rect, the window slides down instead of up.
const Frame: React.FC<{
  clip: string;
  from: number;
  rect: [number, number, number, number];
  w: number;
  h: number;
  minY: number;
}> = ({ clip, from, rect, w, h, minY }) => {
  const { fps } = useVideoConfig();
  const [x, ry, rw, rh0] = rect;
  const y = Math.max(ry, minY);
  const rh = Math.max(1, rh0 - (y - ry));
  const scale = Math.max(w / rw, h / rh);
  // source rows shown: centred on the rect, then kept inside [minY, CLIP_H]
  const shown = h / scale;
  const srcTop = Math.min(Math.max(y + rh / 2 - shown / 2, minY), CLIP_H - shown);
  return (
    <div style={{ position: "relative", width: w, height: h, overflow: "hidden", backgroundColor: "#05070C" }}>
      <OffthreadVideo
        src={staticFile(clip)}
        startFrom={Math.round(from * fps)}
        muted
        style={{
          maxWidth: "none", // beat Tailwind preflight's max-width 100% on video
          position: "absolute",
          width: CLIP_W * scale,
          height: CLIP_H * scale,
          left: -x * scale + (w - rw * scale) / 2,
          top: -srcTop * scale,
        }}
      />
    </div>
  );
};

const Badge: React.FC<{ size: number }> = ({ size }) => {
  const look = useLook();
  return (
    <div style={{ display: "flex", alignItems: "center", gap: size * 0.4 }}>
      <Img src={staticFile("logo-app.png")} style={{ width: size * 1.7, height: size * 1.7 }} />
      <span style={{ fontFamily: INTER, fontWeight: 700, fontSize: size, color: look.ink, letterSpacing: "0.02em" }}>
        WM Keyboard
      </span>
    </div>
  );
};

// Cover image for a short, styled per look. 1080x1920 for the feeds (the
// title stays inside the centre 3:4 the profile grid shows), 1280x720 for
// YouTube with the title left and the feature right.
export const Thumb: React.FC<ThumbProps> = ({ spec, look: lookProp }) => {
  const { width, height } = useVideoConfig();
  const look = {
    ...resolveLook(lookProp ?? spec.look),
    accent: spec.accent ?? VIOLET,
    accentB: spec.accentB ?? CYAN,
  };
  const t = resolveThumb(spec);
  const wide = width > height;
  const size = titleSize(t.title, wide) / look.headScale;
  const ring = look.light
    ? { border: "12px solid #FFFFFF", boxShadow: "0 30px 70px rgba(60,45,20,0.3)" }
    : { border: `6px solid ${look.accent}`, boxShadow: `0 30px 80px rgba(0,0,0,0.6), 0 0 70px ${look.accent}66` };

  const title = (
    <div style={{ filter: look.light ? undefined : "drop-shadow(0 8px 24px rgba(0,0,0,0.7))" }}>
      <Headline text={t.title} size={size} still />
    </div>
  );

  const content = (() => {
    if (look.frame === "bleed") {
      return (
        <AbsoluteFill>
          {/* The crop fills the frame (wide) or its top 1080 px (vertical), so
              the title's scrim never sits on the thing the cover is about. */}
          <Frame clip={t.clip} from={t.from} rect={t.zoom} w={width} h={wide ? height : 1080} minY={t.cropTop} />
          <AbsoluteFill
            style={{
              background: wide
                ? "linear-gradient(90deg, rgba(5,7,12,0.95) 0%, rgba(5,7,12,0.8) 45%, transparent 75%)"
                : "linear-gradient(180deg, transparent 42%, rgba(5,7,12,0.9) 56%, rgba(5,7,12,1) 100%)",
            }}
          />
          <AbsoluteFill
            style={
              wide
                ? { left: 60, width: 640, justifyContent: "center", gap: 30 }
                : { top: 1000, height: 560, left: 140, width: 800, justifyContent: "center", alignItems: "center", gap: 36 }
            }
          >
            {title}
            <Badge size={wide ? 28 : 36} />
          </AbsoluteFill>
        </AbsoluteFill>
      );
    }
    const device = look.frame === "device";
    // device: the whole screen in a tilted phone; card/paper: the crop
    const box = wide ? { w: device ? 300 : 500, h: device ? 628 : 600 } : { w: device ? 520 : 820, h: device ? 1088 : 760 };
    const media = device ? (
      <div
        style={{
          padding: 14,
          borderRadius: 60,
          backgroundColor: "#05070C",
          border: "3px solid rgba(255,255,255,0.2)",
          boxShadow: `40px 50px 100px rgba(0,0,0,0.65), 0 0 90px ${look.accent}55`,
          transform: "perspective(2000px) rotateY(-14deg) rotateZ(3deg)",
        }}
      >
        <div style={{ borderRadius: 46, overflow: "hidden" }}>
          <Frame clip={t.clip} from={t.from} rect={[0, t.cropTop, 1080, CLIP_H - t.cropTop]} w={box.w} h={box.h} minY={t.cropTop} />
        </div>
      </div>
    ) : (
      <div style={{ borderRadius: wide ? 36 : 48, overflow: "hidden", rotate: look.light ? "-1.5deg" : "0deg", ...ring }}>
        <Frame clip={t.clip} from={t.from} rect={t.zoom} w={box.w} h={box.h} minY={t.cropTop} />
      </div>
    );
    if (wide) {
      return (
        <AbsoluteFill style={{ flexDirection: "row", alignItems: "center", padding: "0 60px", gap: 50 }}>
          <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 30, alignItems: "center" }}>
            {title}
            <Badge size={28} />
          </div>
          {media}
        </AbsoluteFill>
      );
    }
    return (
      <AbsoluteFill style={{ alignItems: "center" }}>
        <AbsoluteFill style={{ top: 250, height: device ? 560 : 640, left: 140, width: 800, alignItems: "center", justifyContent: "center", gap: 28 }}>
          <Badge size={34} />
          {title}
        </AbsoluteFill>
        <div style={{ position: "absolute", top: device ? 800 : 920 }}>{media}</div>
      </AbsoluteFill>
    );
  })();

  return (
    <LookContext.Provider value={look}>
      <Canvas>{content}</Canvas>
    </LookContext.Provider>
  );
};
