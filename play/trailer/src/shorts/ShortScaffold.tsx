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
import { Sfx } from "../components/Sfx";
import { INTER } from "../fonts";
import { CYAN, INK, VIOLET, WHITE } from "../theme";
import { Headline } from "./Headline";
import { LookContext, PAPER, resolveLook, useLook } from "./looks";
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
> = ({ spec, bed, safeZone, look: lookProp, children, bedVolume = 0.6 }) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const accent = spec.accent ?? VIOLET;
  const accentB = spec.accentB ?? CYAN;
  const look = { ...resolveLook(lookProp ?? spec.look), accent, accentB };
  const starts = beatStarts(spec.beats);
  const body = beatsFrames(spec);
  const outroBed = Math.max(bedVolume, 0.6);

  return (
    <LookContext.Provider value={look}>
      <Canvas>
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
          {look.frame === "bleed" ? <Scrims /> : null}
          {children}
        </Sequence>

        <Sequence from={body} name="Outro">
          <Outro />
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
            backgroundColor: look.light ? "rgba(10,13,20,0.1)" : "rgba(255,255,255,0.1)",
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
      </Canvas>
    </LookContext.Provider>
  );
};

// Background per look: the Ink & Neon backdrop with an orbiting glow for the
// dark looks, cream paper with a dot grid for paper.
export const Canvas: React.FC<{ children?: React.ReactNode }> = ({ children }) => {
  const frame = useCurrentFrame();
  const look = useLook();
  if (look.light) {
    return (
      <AbsoluteFill style={{ backgroundColor: PAPER }}>
        <AbsoluteFill
          style={{
            backgroundImage: "radial-gradient(rgba(60,45,20,0.13) 2px, transparent 2.5px)",
            backgroundSize: "36px 36px",
            backgroundPosition: `${frame * 0.4}px ${frame * 0.25}px`,
          }}
        />
        <div
          style={{
            position: "absolute",
            width: 1000,
            height: 1000,
            left: SAFE_CX - 500 + Math.sin(frame / 45) * 140,
            top: 700 + Math.cos(frame / 55) * 160,
            borderRadius: "50%",
            background: `radial-gradient(circle, ${look.accent}40 0%, transparent 65%)`,
            filter: "blur(50px)",
          }}
        />
        {children}
      </AbsoluteFill>
    );
  }
  return (
    <Backdrop accentA={look.accent} accentB={look.accentB}>
      {/* slow orbiting glow behind the footage keeps the canvas alive */}
      <div
        style={{
          position: "absolute",
          width: 900,
          height: 900,
          left: SAFE_CX - 450 + Math.sin(frame / 40) * 120,
          top: 760 + Math.cos(frame / 52) * 140,
          borderRadius: "50%",
          background: `radial-gradient(circle, ${look.accent}55 0%, transparent 65%)`,
          filter: "blur(40px)",
        }}
      />
      {children}
    </Backdrop>
  );
};

// Bleed look: dark gradients top and bottom so type reads over footage.
const Scrims: React.FC = () => {
  const look = useLook();
  return (
    <>
      <AbsoluteFill
        style={{
          height: 640,
          background: "linear-gradient(180deg, rgba(5,7,12,0.85) 0%, rgba(5,7,12,0.55) 55%, transparent 100%)",
        }}
      />
      <AbsoluteFill
        style={{
          top: 760,
          background: `linear-gradient(180deg, transparent 0%, rgba(5,7,12,0.7) 35%, rgba(5,7,12,0.92) 100%)`,
        }}
      />
      <AbsoluteFill
        style={{
          top: look.head.top - 30,
          height: 6,
          left: SAFE_CX - 60,
          width: 120,
          borderRadius: 3,
          background: `linear-gradient(90deg, ${look.accent}, ${look.accentB})`,
        }}
      />
    </>
  );
};

const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const look = useLook();
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
      {look.frame === "bleed" ? (
        <AbsoluteFill style={{ left: -SAFE.left, width: 1080, top: -SAFE.top, height: 1920, backgroundColor: "rgba(5,7,12,0.6)" }} />
      ) : null}
      <Img
        src={staticFile("logo-mark.png")}
        style={{
          width: 170,
          height: 170,
          filter: `drop-shadow(0 0 44px ${look.accent}77)`,
          scale: interpolate(frame, [0, 10], [0.4, 1], {
            ...clamp,
            easing: Easing.out(Easing.back(2)),
          }),
          rotate: `${interpolate(frame, [0, 10], [-25, 0], clamp)}deg`,
        }}
      />
      <Headline text="WM «Keyboard»" size={104} delay={4} />
      <div
        style={{
          fontFamily: INTER,
          fontWeight: 500,
          fontSize: 44,
          color: look.soft,
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
          backgroundColor: look.light ? INK : "transparent",
          border: look.light ? "none" : "2px solid rgba(255,255,255,0.22)",
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
