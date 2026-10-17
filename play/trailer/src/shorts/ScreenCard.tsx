import React from "react";
import {
  AbsoluteFill,
  Easing,
  Loop,
  OffthreadVideo,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { INTER } from "../fonts";
import { BODY, CLIP_H, CLIP_W, INK_HI } from "../theme";
import { cropTop } from "./crops";
import { SHORT_CLIPS } from "./clips";
import { SAFE_CX, resolveFocus, sec } from "./layout";
import { useLook } from "./looks";
import type { ClipBeat } from "./types";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// One beat's footage, framed by the current look: a rounded card (card,
// paper), the whole frame (bleed) or a tilted phone (device). Every look
// enters on the cut with its own move, then keeps pushing / panning through
// its crop window until the next cut, so no frame is a static hold. Short
// clips loop to fill the beat.
//
// Focus zoom is in card units: 2 fills the frame width in every look. Bleed
// never drops below width-fill; device always shows the whole screen.
export const ScreenCard: React.FC<{
  beat: ClipBeat;
  index: number;
  accent: string;
}> = ({ beat, index, accent }) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();
  const look = useLook();
  const C = look.card;
  const f = resolveFocus(beat.focus);
  const len = sec(beat.sec);
  const focusZoom = interpolate(frame, [0, len], [f.zoom, f.toZoom], {
    ...clamp,
    easing: Easing.inOut(Easing.quad),
  });
  const y = interpolate(frame, [0, len], [f.y, f.toY], {
    ...clamp,
    easing: Easing.inOut(Easing.cubic),
  });

  // Rows above `top` (status bar, Chrome's toolbar on chat clips) never show.
  const top = cropTop(beat);
  const fitW = (C.height * CLIP_W) / (CLIP_H - top); // whole visible screen at this width
  const zoomFill = C.maxWidth / fitW;
  const zoom =
    look.frame === "device"
      ? 1
      : look.frame === "bleed"
        ? Math.max(zoomFill, (focusZoom * zoomFill) / 2)
        : (focusZoom * zoomFill) / 2;
  const videoW = fitW * zoom;
  const videoH = C.height * zoom;
  const cardW = Math.min(C.maxWidth, videoW);
  const scale = videoW / CLIP_W;
  const offsetX = (cardW - videoW) / 2;
  const offsetY = -(videoH - C.height) * y;

  // Zoom punch-in: target rect from source pixels into card space, then a
  // scale about the card's origin that brings its centre to the look's zoom
  // anchor, horizontally centred.
  const z = beat.zoom;
  let zoomStyle: React.CSSProperties = {};
  let ring: React.ReactNode = null;
  if (z) {
    const at = sec(z.at);
    const cfg = { damping: 200 };
    const p =
      spring({ frame: frame - at, fps, config: cfg, durationInFrames: 10 }) -
      spring({
        frame: frame - at - 10 - sec(z.hold),
        fps,
        config: cfg,
        durationInFrames: 10,
      });
    const [zx, zy, zw, zh] = z.to;
    const tx = offsetX + zx * scale;
    const ty = offsetY + (zy - top) * scale;
    const tw = zw * scale;
    const th = zh * scale;
    const target = Math.min(
      Math.max(Math.min((cardW * 0.88) / tw, (C.height * look.zoom.maxH) / th), 1.3),
      5,
    );
    const s = 1 + (target - 1) * p;
    const cx = tx + tw / 2;
    const cy = ty + th / 2;
    const dx = cx + (cardW / 2 - cx) * p - s * cx;
    // The target lands in the upper part of the frame (look.zoom.at), so
    // captions in the look's low band never cover it.
    const dy = cy + (C.height * look.zoom.at - cy) * p - s * cy;
    zoomStyle = {
      transformOrigin: "0 0",
      transform: `translate(${dx}px, ${dy}px) scale(${s})`,
    };
    if (z.ring !== false) {
      const pad = 12 / s;
      ring = (
        <div
          style={{
            position: "absolute",
            left: tx - pad,
            top: ty - pad,
            width: tw + pad * 2,
            height: th + pad * 2,
            borderRadius: 20 / s,
            border: `${5 / s}px solid ${accent}`,
            boxShadow: `0 0 ${30 / s}px ${accent}`,
            opacity: p,
            scale: interpolate(p, [0, 1], [1.25, 1]),
          }}
        />
      );
    }
  }

  const total = SHORT_CLIPS[beat.clip];
  const from = beat.from ?? 0;
  const rate = beat.rate ?? 1;

  const video =
    total !== undefined ? (
      <OffthreadVideo
        src={staticFile(beat.clip)}
        startFrom={sec(from)}
        playbackRate={rate}
        muted
        style={{
          // Tailwind preflight caps video at max-width 100%, which would
          // letterbox any crop wider than the card
          maxWidth: "none",
          position: "absolute",
          left: offsetX,
          top: offsetY - top * scale,
          width: videoW,
          height: CLIP_H * scale,
        }}
      />
    ) : (
      <AbsoluteFill
        style={{
          background: `linear-gradient(160deg, ${INK_HI}, #1A2030)`,
          alignItems: "center",
          justifyContent: "center",
          fontFamily: INTER,
          fontSize: 34,
          color: BODY,
        }}
      >
        [{beat.clip}]
      </AbsoluteFill>
    );
  // Frames of footage left after `from`, at `rate`; loop when the beat is longer.
  const segment =
    total !== undefined ? Math.floor(sec(total - from) / rate) : len;
  const media =
    segment > 0 && segment < durationInFrames ? (
      <Loop durationInFrames={segment}>{video}</Loop>
    ) : (
      video
    );

  const screen = (
    <>
      <AbsoluteFill style={zoomStyle}>
        {media}
        {ring}
      </AbsoluteFill>
      {look.cut === "punch" ? (
        <AbsoluteFill
          style={{
            backgroundColor: "white",
            opacity: interpolate(frame, [0, 6], [index === 0 ? 0 : 0.35, 0], clamp),
          }}
        />
      ) : null}
    </>
  );
  const side = index % 2 === 0 ? -1 : 1;

  if (look.frame === "device") {
    // Phone with a bezel, tilted in 3D; each cut swings it in from the
    // opposite side.
    const bezel = 18;
    const swing = interpolate(frame, [0, 12], [side * -48, side * 13], {
      ...clamp,
      easing: Easing.out(Easing.back(1.3)),
    });
    return (
      <div
        style={{
          position: "absolute",
          top: C.top - bezel,
          left: SAFE_CX - cardW / 2 - bezel,
          padding: bezel,
          borderRadius: 74,
          backgroundColor: "#05070C",
          border: "3px solid rgba(255,255,255,0.18)",
          boxShadow: `${side * -40}px 60px 110px rgba(0,0,0,0.65), 0 0 90px ${accent}44`,
          transform: `perspective(2200px) rotateY(${swing + interpolate(frame, [12, len], [0, side * -4], clamp)}deg) rotateZ(${side * 2.5}deg) scale(${interpolate(frame, [0, len], [0.97, 1.03], clamp)})`,
        }}
      >
        <div style={{ position: "relative", width: cardW, height: C.height, borderRadius: 56, overflow: "hidden" }}>
          {screen}
        </div>
      </div>
    );
  }

  const enter: React.CSSProperties =
    look.cut === "slide"
      ? {
          // push in from the right, slight zoom settle
          translate: `${interpolate(frame, [0, 7], [index === 0 ? 0 : 260, 0], { ...clamp, easing: Easing.out(Easing.cubic) })}px 0px`,
          opacity: interpolate(frame, [0, 4], [index === 0 ? 1 : 0, 1], clamp),
          scale: interpolate(frame, [0, 7, len], [1.08, 1, 1.03], clamp),
        }
      : look.cut === "drop"
        ? {
            // dropped onto the paper: falls from above and settles
            translate: `0px ${interpolate(frame, [0, 10], [-140, 0], { ...clamp, easing: Easing.out(Easing.back(1.8)) })}px`,
            rotate: `${interpolate(frame, [0, 10, len], [side * 5, side * 1.2, side * 0.4], clamp)}deg`,
            opacity: interpolate(frame, [0, 3], [0, 1], clamp),
          }
        : {
            scale: interpolate(frame, [0, 9], [1.12, 1], {
              ...clamp,
              easing: Easing.out(Easing.back(1.6)),
            }),
            rotate: `${interpolate(frame, [0, 9, len], [side * 4, side * 1.6, -side * 0.64], clamp)}deg`,
            translate: `0px ${interpolate(frame, [0, 9], [60, 0], { ...clamp, easing: Easing.out(Easing.cubic) })}px`,
          };

  const chrome: React.CSSProperties =
    look.frame === "bleed"
      ? { backgroundColor: "#000" }
      : look.light
        ? {
            backgroundColor: "#FFFFFF",
            border: "10px solid #FFFFFF",
            boxShadow: "0 30px 70px rgba(60,45,20,0.28), 0 4px 12px rgba(60,45,20,0.12)",
          }
        : {
            backgroundColor: "#05070C",
            border: "3px solid rgba(255,255,255,0.14)",
            boxShadow: `0 40px 90px rgba(0,0,0,0.6), 0 0 80px ${accent}33`,
          };

  return (
    <div
      style={{
        position: "absolute",
        // paper's 10px white border sits outside the content box
        top: C.top - (look.light ? 10 : 0),
        left: SAFE_CX - cardW / 2 - (look.light ? 10 : 0),
        width: cardW,
        height: C.height,
        borderRadius: C.radius,
        overflow: "hidden",
        boxSizing: look.light ? "content-box" : undefined,
        ...chrome,
        ...enter,
      }}
    >
      {screen}
    </div>
  );
};
