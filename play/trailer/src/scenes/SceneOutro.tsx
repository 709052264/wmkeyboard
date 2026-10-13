import React from "react";
import {
  AbsoluteFill,
  Img,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { Backdrop } from "../components/Backdrop";
import { Chip } from "../components/Chip";
import { GradientHeadline } from "../components/GradientHeadline";
import { Sfx } from "../components/Sfx";
import { INTER } from "../fonts";
import { AMBER, BODY, CYAN, LIME, MAGENTA, V_SAFE_BOTTOM, V_SAFE_TOP, VIOLET, WHITE } from "../theme";
import { useVertical } from "../useVertical";

const ROW_A = [
  ["Offline Whisper dictation", LIME],
  ["OTP codes from notifications", CYAN],
  ["Spacebar trackpad", null],
  ["Glide in any language", VIOLET],
  ["Latest emoji", MAGENTA],
  ["Calculator in the strip", AMBER],
] as const;
const ROW_B = [
  ["Encrypted backups", CYAN],
  ["Theme editor", VIOLET],
  ["Sticker editor", MAGENTA],
  ["Handwriting input", AMBER],
  ["Lua plugins", LIME],
  ["Typing statistics", null],
] as const;

export const SceneOutro: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();
  const vertical = useVertical();
  const rowTop = vertical ? V_SAFE_TOP + 60 : 120;
  const rowBottom = vertical ? V_SAFE_BOTTOM + 60 : 120;
  const logo = spring({
    frame: frame - 46,
    fps,
    config: { damping: 200, stiffness: 110 },
  });
  const cta = spring({
    frame: frame - 78,
    fps,
    config: { damping: 200, stiffness: 140 },
  });
  const fadeOut = interpolate(
    frame,
    [durationInFrames - 24, durationInFrames - 2],
    [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" },
  );

  return (
    <Backdrop>
      <Sfx name="riser" at={8} volume={0.45} />
      <Sfx name="impact" at={48} volume={0.55} />
      <Sfx name="pop" at={80} volume={0.4} />
      <AbsoluteFill style={{ opacity: fadeOut }}>
        {/* chip rows drift outward as the logo takes over */}
        <div
          style={{
            position: "absolute",
            top: rowTop,
            left: 0,
            right: 0,
            display: "flex",
            justifyContent: "center",
            gap: 22,
            opacity: interpolate(frame, [40, 80], [1, 0.14], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
            }),
            transform: `translateX(${-frame * 0.6}px)`,
          }}
        >
          {ROW_A.map(([label, accent], i) => (
            <Chip key={label} label={label} accent={accent} delay={i * 4} size={28} />
          ))}
        </div>
        <div
          style={{
            position: "absolute",
            bottom: rowBottom,
            left: 0,
            right: 0,
            display: "flex",
            justifyContent: "center",
            gap: 22,
            opacity: interpolate(frame, [40, 80], [1, 0.14], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
            }),
            transform: `translateX(${frame * 0.6}px)`,
          }}
        >
          {ROW_B.map(([label, accent], i) => (
            <Chip key={label} label={label} accent={accent} delay={8 + i * 4} size={28} />
          ))}
        </div>

        <AbsoluteFill
          style={{
            alignItems: "center",
            justifyContent: "center",
            flexDirection: "column",
            gap: 34,
          }}
        >
          <Img
            src={staticFile("logo-mark.png")}
            style={{
              width: 150,
              height: 150,
              opacity: logo,
              transform: `scale(${interpolate(logo, [0, 1], [0.6, 1])})`,
              filter: "drop-shadow(0 0 44px rgba(124,92,255,0.45))",
            }}
          />
          <GradientHeadline text="WM «Keyboard»" size={104} delay={50} align="center" />
          <div
            style={{
              fontFamily: INTER,
              fontWeight: 500,
              fontSize: 42,
              color: BODY,
              opacity: logo,
              letterSpacing: "0.06em",
            }}
          >
            Private. Offline. Yours.
          </div>
          <div
            style={{
              marginTop: 18,
              fontFamily: INTER,
              fontWeight: 600,
              fontSize: 36,
              color: WHITE,
              border: "2px solid rgba(255,255,255,0.22)",
              borderRadius: 999,
              padding: "20px 44px",
              opacity: cta,
              transform: `translateY(${interpolate(cta, [0, 1], [24, 0])}px)`,
            }}
          >
            Get it on Google Play
          </div>
        </AbsoluteFill>
      </AbsoluteFill>
    </Backdrop>
  );
};
