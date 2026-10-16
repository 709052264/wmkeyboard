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
import { BODY, CLIP_H, CLIP_W, INK_HI, STATUS_BAR } from "../theme";
import { SHORT_CLIPS } from "./clips";
import { CARD, SAFE_CX, resolveFocus, sec } from "./layout";
import type { ClipBeat } from "./types";

const SCREEN_RATIO = (CLIP_H - STATUS_BAR) / CLIP_W; // 2260 / 1080
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// One beat's footage: a rounded screen card that punches in on the cut, then
// keeps pushing / panning through its crop window until the next cut, so no
// frame is a static hold. Short clips loop to fill the beat.
export const ScreenCard: React.FC<{
  beat: ClipBeat;
  index: number;
  accent: string;
}> = ({ beat, index, accent }) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();
  const f = resolveFocus(beat.focus);
  const len = sec(beat.sec);
  const zoom = interpolate(frame, [0, len], [f.zoom, f.toZoom], {
    ...clamp,
    easing: Easing.inOut(Easing.quad),
  });
  const y = interpolate(frame, [0, len], [f.y, f.toY], {
    ...clamp,
    easing: Easing.inOut(Easing.cubic),
  });

  const fitW = CARD.height / SCREEN_RATIO; // ~425: whole screen visible
  const videoW = fitW * zoom;
  const videoH = CARD.height * zoom;
  const cardW = Math.min(CARD.maxWidth, videoW);
  const scale = videoW / CLIP_W;
  const offsetX = (cardW - videoW) / 2;
  const offsetY = -(videoH - CARD.height) * y;

  // Zoom punch-in: target rect from source pixels into card space, then a
  // scale about the card's origin that brings its centre to the card centre.
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
    const ty = offsetY + (zy - STATUS_BAR) * scale;
    const tw = zw * scale;
    const th = zh * scale;
    const target = Math.min(
      Math.max(Math.min((cardW * 0.88) / tw, (CARD.height * 0.45) / th), 1.3),
      5,
    );
    const s = 1 + (target - 1) * p;
    const cx = tx + tw / 2;
    const cy = ty + th / 2;
    const dx = cx + (cardW / 2 - cx) * p - s * cx;
    const dy = cy + (CARD.height / 2 - cy) * p - s * cy;
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
  const tilt = index % 2 === 0 ? -1.6 : 1.6;

  const video =
    total !== undefined ? (
      <OffthreadVideo
        src={staticFile(beat.clip)}
        startFrom={sec(from)}
        playbackRate={rate}
        muted
        style={{
          position: "absolute",
          left: offsetX,
          top: offsetY - STATUS_BAR * scale,
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

  return (
    <div
      style={{
        position: "absolute",
        top: CARD.top,
        left: SAFE_CX - cardW / 2,
        width: cardW,
        height: CARD.height,
        borderRadius: 54,
        overflow: "hidden",
        backgroundColor: "#05070C",
        border: "3px solid rgba(255,255,255,0.14)",
        boxShadow: `0 40px 90px rgba(0,0,0,0.6), 0 0 80px ${accent}33`,
        scale: interpolate(frame, [0, 9], [1.12, 1], {
          ...clamp,
          easing: Easing.out(Easing.back(1.6)),
        }),
        rotate: `${interpolate(frame, [0, 9, len], [tilt * 2.5, tilt, -tilt * 0.4], clamp)}deg`,
        translate: `0px ${interpolate(frame, [0, 9], [60, 0], { ...clamp, easing: Easing.out(Easing.cubic) })}px`,
      }}
    >
      <AbsoluteFill style={zoomStyle}>
        {media}
        {ring}
      </AbsoluteFill>
      {/* cut flash */}
      <AbsoluteFill
        style={{
          backgroundColor: "white",
          opacity: interpolate(
            frame,
            [0, 6],
            [index === 0 ? 0 : 0.35, 0],
            clamp,
          ),
        }}
      />
    </div>
  );
};
