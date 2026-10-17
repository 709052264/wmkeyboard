import React from "react";
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { MANROPE } from "../fonts";
import { useLook } from "./looks";

// Groups words into runs: plain words one by one, «marked» spans whole, so
// an accent phrase animates and wraps as one unit.
const runs = (text: string) => {
  const out: { text: string; accent: boolean }[] = [];
  let inAccent = false;
  for (const word of text.split(" ").filter(Boolean)) {
    const opens = word.includes("«");
    const accent = inAccent || opens;
    const clean = word.replace(/[«»]/g, "");
    const last = out[out.length - 1];
    if (accent && !opens && last?.accent) {
      last.text += ` ${clean}`;
    } else {
      out.push({ text: clean, accent });
    }
    if (opens) inAccent = true;
    if (word.includes("»")) inAccent = false;
  }
  return out;
};

// Kinetic headline in the current look: words spring up with a stagger,
// «marked» words take the accent (gradient on dark looks, a highlighter
// stroke on paper). Inline flow with text-wrap balance, so lines come out
// even and no word is left alone on the last line; an accent span never
// breaks.
export const Headline: React.FC<{ text: string; size: number; delay?: number; still?: boolean }> = ({
  text,
  size,
  delay = 0,
  still = false,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const look = useLook();
  const parts = runs(text);
  return (
    <div
      style={{
        fontFamily: MANROPE,
        fontWeight: 800,
        fontSize: size * look.headScale,
        lineHeight: 1.1,
        letterSpacing: "-0.02em",
        color: look.ink,
        textAlign: "center",
        textWrap: "balance",
      }}
    >
      {parts.map((part, i) => {
        const s = still ? 1 : spring({ frame: frame - delay - i * 3, fps, config: { damping: 200, stiffness: 140 } });
        const accentStyle: React.CSSProperties = !part.accent
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
          <React.Fragment key={i}>
            {i > 0 ? " " : null}
            <span
              style={{
                display: "inline-block",
                whiteSpace: "nowrap",
                opacity: s,
                translate: `0px ${interpolate(s, [0, 1], [40, 0])}px`,
                ...(look.frame === "bleed" ? { textShadow: "0 6px 24px rgba(0,0,0,0.65)" } : {}),
                ...accentStyle,
              }}
            >
              {part.text}
            </span>
          </React.Fragment>
        );
      })}
    </div>
  );
};

// Plain text with «marked» phrases kept on one line (marks dropped), for
// boxes that don't animate per word: meme box, reactions.
export const NoBreak: React.FC<{ text: string }> = ({ text }) => (
  <>
    {runs(text).map((part, i) => (
      <React.Fragment key={i}>
        {i > 0 ? " " : null}
        {part.accent ? <span style={{ whiteSpace: "nowrap" }}>{part.text}</span> : part.text}
      </React.Fragment>
    ))}
  </>
);

// Text treatment for spoken captions and reactions in the current look.
export const captionStyle = (look: ReturnType<typeof useLook>, size: number): React.CSSProperties => {
  const base: React.CSSProperties = {
    fontFamily: MANROPE,
    fontWeight: 800,
    fontSize: size,
    lineHeight: 1.12,
    textAlign: "center",
    letterSpacing: "-0.01em",
    textWrap: "balance",
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
