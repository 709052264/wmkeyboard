import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { MANROPE } from "../fonts";
import { useLook } from "./looks";

// Kinetic headline in the current look: words spring up with a stagger,
// «marked» words take the accent (gradient on dark looks, a highlighter
// stroke on paper). Words sit in a centred flex row with a gap, so the
// block centres exactly instead of carrying a trailing word margin.
export const Headline: React.FC<{ text: string; size: number; delay?: number; still?: boolean }> = ({
  text,
  size,
  delay = 0,
  still = false,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const look = useLook();
  const words = text.split(" ").filter(Boolean);
  // «two words» spans: track open/close across words
  let inAccent = false;
  return (
    <div
      style={{
        display: "flex",
        flexWrap: "wrap",
        justifyContent: "center",
        columnGap: "0.26em",
        rowGap: "0.02em",
        fontFamily: MANROPE,
        fontWeight: 800,
        fontSize: size * look.headScale,
        lineHeight: 1.08,
        letterSpacing: "-0.02em",
        color: look.ink,
        textAlign: "center",
      }}
    >
      {words.map((word, i) => {
        const opens = word.includes("«");
        const accent = inAccent || opens;
        if (opens) inAccent = true;
        if (word.includes("»")) inAccent = false;
        const clean = word.replace(/[«»]/g, "");
        const s = still ? 1 : spring({ frame: frame - delay - i * 3, fps, config: { damping: 200, stiffness: 140 } });
        const accentStyle: React.CSSProperties = !accent
          ? {}
          : look.light
            ? {
                background: `linear-gradient(180deg, transparent 58%, ${look.accent}AA 58%, ${look.accent}AA 92%, transparent 92%)`,
                padding: "0 0.08em",
              }
            : {
                background: `linear-gradient(90deg, ${look.accent}, ${look.accentB})`,
                WebkitBackgroundClip: "text",
                backgroundClip: "text",
                color: "transparent",
                filter: `drop-shadow(0 0 ${size / 6}px ${look.accent}66)`,
              };
        return (
          <span
            key={i}
            style={{
              display: "inline-block",
              opacity: s,
              translate: `0px ${interpolate(s, [0, 1], [40, 0])}px`,
              ...(look.frame === "bleed" ? { textShadow: "0 6px 24px rgba(0,0,0,0.65)" } : {}),
              ...accentStyle,
            }}
          >
            {clean}
          </span>
        );
      })}
    </div>
  );
};

// Text treatment for spoken captions and reactions in the current look.
export const captionStyle = (look: ReturnType<typeof useLook>, size: number): React.CSSProperties => {
  const base: React.CSSProperties = {
    fontFamily: MANROPE,
    fontWeight: 800,
    fontSize: size,
    lineHeight: 1.12,
    textAlign: "center",
    letterSpacing: "-0.01em",
  };
  if (look.caption === "pill") {
    return {
      ...base,
      color: "#FFFFFF",
      backgroundColor: "rgba(10,13,20,0.9)",
      border: `3px solid ${look.accent}`,
      borderRadius: 34,
      padding: "16px 34px",
      boxShadow: "0 18px 40px rgba(0,0,0,0.5)",
      boxDecorationBreak: "clone",
      WebkitBoxDecorationBreak: "clone",
    };
  }
  if (look.caption === "highlight") {
    return {
      ...base,
      color: look.ink,
      backgroundColor: "#FFFFFF",
      borderRadius: 26,
      padding: "14px 30px",
      boxShadow: "0 14px 34px rgba(30,25,15,0.18)",
    };
  }
  return {
    ...base,
    color: "#FFFFFF",
    paintOrder: "stroke fill",
    WebkitTextStroke: `${Math.round(size * 0.15)}px rgba(5,7,12,0.92)`,
    textShadow: "0 10px 30px rgba(0,0,0,0.6)",
  };
};
