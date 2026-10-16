import React from "react";
import {
  AbsoluteFill,
  Audio,
  Easing,
  Img,
  Sequence,
  interpolate,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { Backdrop } from "../components/Backdrop";
import { GradientHeadline } from "../components/GradientHeadline";
import { Sfx } from "../components/Sfx";
import { INTER } from "../fonts";
import { BODY, CYAN, VIOLET, WHITE } from "../theme";
import { SafeZone } from "./SafeZone";
import { ScreenCard } from "./ScreenCard";
import { Carousel } from "./Carousel";
import { SAFE, SAFE_CX, SAFE_W, beatFrames, beatStarts, beatsFrames } from "./layout";
import type { ShortProps } from "./types";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// Shared body of every short: drifting backdrop, one ScreenCard per beat,
// whoosh on each cut, outro card, the music bed (only when `bed`), the VO,
// and the safe-zone overlay. Templates pass their text layers as children;
// those play over the beats, not the outro.
export const ShortScaffold: React.FC<
  ShortProps & { children?: React.ReactNode; bedVolume?: number }
> = ({ spec, bed, safeZone, children, bedVolume = 0.6 }) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const accent = spec.accent ?? VIOLET;
  const accentB = spec.accentB ?? CYAN;
  const starts = beatStarts(spec.beats);
  const body = beatsFrames(spec);
  const outroBed = Math.max(bedVolume, 0.6);

  return (
    <Backdrop accentA={accent} accentB={accentB}>
      {bed ? (
        <Audio
          src={staticFile(spec.bed ?? "music.mp3")}
          loop
          volume={(f) =>
            // ducked bed (VO) comes back up for the outro, after the voice is done
            interpolate(
              f,
              [0, 8, body - 6, body + 4, durationInFrames - 20, durationInFrames - 2],
              [0, bedVolume, bedVolume, outroBed, outroBed, 0],
              clamp,
            )
          }
        />
      ) : null}
      {spec.vo ? <Audio src={staticFile(spec.vo.file)} /> : null}
      {starts.slice(1).map((s) => (
        <Sfx key={s} name="whoosh" at={s - 3} volume={0.35} />
      ))}
      <Sfx name="whoosh" at={body - 3} volume={0.4} />

      {/* slow orbiting glow behind the card keeps the canvas alive */}
      <div
        style={{
          position: "absolute",
          width: 900,
          height: 900,
          left: SAFE_CX - 450 + Math.sin(frame / 40) * 120,
          top: 760 + Math.cos(frame / 52) * 140,
          borderRadius: "50%",
          background: `radial-gradient(circle, ${accent}55 0%, transparent 65%)`,
          filter: "blur(40px)",
        }}
      />

      <Sequence durationInFrames={body} name="Beats">
        {spec.beats.map((b, i) => (
          <Sequence key={i} from={starts[i]} durationInFrames={beatFrames(b)} name={`Beat ${i + 1}`}>
            {"carousel" in b ? (
              <Carousel beat={b} index={i} accent={accent} />
            ) : (
              <ScreenCard beat={b} index={i} accent={accent} />
            )}
          </Sequence>
        ))}
        {children}
      </Sequence>

      <Sequence from={body} name="Outro">
        <Outro accent={accent} />
      </Sequence>

      {/* progress bar, just under the top safe line */}
      <div
        style={{
          position: "absolute",
          top: SAFE.top - 2,
          left: SAFE.left,
          width: SAFE_W,
          height: 6,
          borderRadius: 3,
          backgroundColor: "rgba(255,255,255,0.08)",
        }}
      >
        <div
          style={{
            width: `${(frame / durationInFrames) * 100}%`,
            height: "100%",
            borderRadius: 3,
            background: `linear-gradient(90deg, ${accent}, ${accentB})`,
          }}
        />
      </div>
      {safeZone ? <SafeZone /> : null}
    </Backdrop>
  );
};

const Outro: React.FC<{ accent: string }> = ({ accent }) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill
      style={{
        left: SAFE.left,
        width: SAFE_W,
        top: SAFE.top,
        bottom: SAFE.bottom,
        alignItems: "center",
        justifyContent: "center",
        flexDirection: "column",
        gap: 30,
        scale: interpolate(frame, [0, 60], [0.94, 1.04], clamp),
      }}
    >
      <Img
        src={staticFile("logo-mark.png")}
        style={{
          width: 170,
          height: 170,
          filter: `drop-shadow(0 0 44px ${accent}77)`,
          scale: interpolate(frame, [0, 10], [0.4, 1], {
            ...clamp,
            easing: Easing.out(Easing.back(2)),
          }),
          rotate: `${interpolate(frame, [0, 10], [-25, 0], clamp)}deg`,
        }}
      />
      <GradientHeadline text="WM «Keyboard»" size={110} delay={4} align="center" />
      <div
        style={{
          fontFamily: INTER,
          fontWeight: 500,
          fontSize: 44,
          color: BODY,
          letterSpacing: "0.05em",
          opacity: interpolate(frame, [10, 18], [0, 1], clamp),
        }}
      >
        Private. Offline. Yours.
      </div>
      <div
        style={{
          marginTop: 14,
          fontFamily: INTER,
          fontWeight: 600,
          fontSize: 40,
          color: WHITE,
          border: "2px solid rgba(255,255,255,0.22)",
          borderRadius: 999,
          padding: "22px 48px",
          opacity: interpolate(frame, [16, 24], [0, 1], clamp),
          translate: `0px ${interpolate(frame, [16, 26], [30, 0], { ...clamp, easing: Easing.out(Easing.cubic) })}px`,
        }}
      >
        Free on Google Play
      </div>
    </AbsoluteFill>
  );
};
