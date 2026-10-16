import React from "react";
import { AbsoluteFill } from "remotion";
import { INTER } from "../fonts";
import { SAFE } from "./layout";

const band: React.CSSProperties = {
  position: "absolute",
  backgroundColor: "rgba(255,40,80,0.28)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  fontFamily: INTER,
  fontWeight: 600,
  fontSize: 30,
  color: "rgba(255,255,255,0.85)",
};

// Debug overlay: red bands are where the feed UI covers the video (the
// left band mirrors the right rail so layouts stay centred). Toggle
// with the `safeZone` prop; never on in a master.
export const SafeZone: React.FC = () => (
  <AbsoluteFill style={{ pointerEvents: "none" }}>
    <div style={{ ...band, top: 0, left: 0, right: 0, height: SAFE.top }}>
      top {SAFE.top}
    </div>
    <div style={{ ...band, bottom: 0, left: 0, right: 0, height: SAFE.bottom }}>
      bottom {SAFE.bottom}
    </div>
    <div
      style={{
        ...band,
        top: SAFE.top,
        bottom: SAFE.bottom,
        right: 0,
        width: SAFE.right,
        writingMode: "vertical-rl",
      }}
    >
      right {SAFE.right}
    </div>
    <div
      style={{
        ...band,
        top: SAFE.top,
        bottom: SAFE.bottom,
        left: 0,
        width: SAFE.left,
        writingMode: "vertical-rl",
      }}
    >
      left {SAFE.left}
    </div>
    <div
      style={{
        position: "absolute",
        top: SAFE.top,
        bottom: SAFE.bottom,
        left: SAFE.left,
        right: SAFE.right,
        border: "3px dashed rgba(120,255,160,0.8)",
      }}
    />
  </AbsoluteFill>
);
