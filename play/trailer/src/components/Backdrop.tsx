import React from "react";
import { AbsoluteFill, useVideoConfig } from "remotion";
import { INK } from "../theme";

const blob = (
  color: string,
  size: number,
  left: number,
  top: number,
  opacity: number,
): React.CSSProperties => ({
  position: "absolute",
  width: size,
  height: size,
  left,
  top,
  borderRadius: "50%",
  background: `radial-gradient(circle, ${color} 0%, transparent 70%)`,
  opacity,
  filter: "blur(60px)",
});

// Ink & Neon canvas: INK base, two soft accent blobs, vignette, faint grain.
export const Backdrop: React.FC<{
  accentA?: string;
  accentB?: string;
  children?: React.ReactNode;
}> = ({ accentA = "#7C5CFF", accentB = "#22D3EE", children }) => {
  const { width, height } = useVideoConfig();
  return (
    <AbsoluteFill style={{ backgroundColor: INK }}>
      <div style={blob(accentA, 1200, -300, -400, 0.22)} />
      <div style={blob(accentB, 1000, width - 620, height - 580, 0.16)} />
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(ellipse at center, transparent 55%, rgba(0,0,0,0.55) 100%)",
        }}
      />
      <svg width="0" height="0" style={{ position: "absolute" }}>
        <filter id="grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" />
          <feColorMatrix type="saturate" values="0" />
        </filter>
      </svg>
      <AbsoluteFill
        style={{ filter: "url(#grain)", opacity: 0.04, pointerEvents: "none" }}
      />
      {children}
    </AbsoluteFill>
  );
};
