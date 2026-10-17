import React from "react";
import { AbsoluteFill, Easing, Sequence, interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { INTER } from "../fonts";
import { INK, VIOLET, WHITE } from "../theme";
import { ShortScaffold } from "./ShortScaffold";
import { NoBreak, captionStyle } from "./Headline";
import { SAFE, SAFE_W, TEXT_ZONE, beatFrames, beatStarts } from "./layout";
import { useLook } from "./looks";
import type { ShortProps } from "./types";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// Meme-style hook ("POV: ..."): the white caption box is up and readable by
// frame 4 and stays for the whole body, like the native meme format. Beat
// captions land as outlined reaction text on the footage.
export const HookShort: React.FC<ShortProps> = (props) => {
  const { spec } = props;
  const starts = beatStarts(spec.beats);
  const accent = spec.accent ?? VIOLET;
  return (
    <ShortScaffold {...props} bedVolume={0.7}>
      <MemeBox text={spec.hook} accent={accent} cuts={starts} />
      {spec.beats.map((b, i) =>
        b.caption ? (
          <Sequence key={i} from={starts[i] + 3} durationInFrames={beatFrames(b) - 3} name={`React ${i + 1}`}>
            <Reaction text={b.caption} index={i} />
          </Sequence>
        ) : null,
      )}
    </ShortScaffold>
  );
};

const MemeBox: React.FC<{ text: string; accent: string; cuts: number[] }> = ({ text, accent, cuts }) => {
  const frame = useCurrentFrame();
  // Small bump on every cut after the first so the box breathes with the edit.
  const lastCut = [...cuts].reverse().find((c) => c <= frame && c > 0);
  const bump = lastCut === undefined ? 0 : interpolate(frame - lastCut, [0, 3, 8], [0, 0.035, 0], clamp);
  // The meme box is plain black-on-white by design, so «accent» marks only
  // keep their phrase on one line.
  const colon = text.indexOf(":");
  const lead = colon >= 0 ? text.slice(0, colon + 1) : "";
  const rest = colon >= 0 ? text.slice(colon + 1) : text;
  return (
    <AbsoluteFill
      style={{
        top: TEXT_ZONE.top,
        height: TEXT_ZONE.height,
        left: SAFE.left,
        width: SAFE_W,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          backgroundColor: WHITE,
          color: INK,
          borderRadius: 28,
          padding: "30px 40px",
          maxWidth: SAFE_W,
          boxShadow: `0 18px 50px rgba(0,0,0,0.5), 0 0 0 6px ${accent}`,
          fontFamily: INTER,
          fontWeight: 600,
          fontSize: 58,
          lineHeight: 1.18,
          textAlign: "left",
          textWrap: "balance",
          scale: interpolate(frame, [0, 4], [1.25, 1], { ...clamp, easing: Easing.out(Easing.back(2)) }) + bump,
          rotate: `${interpolate(frame, [0, 4, 7, 10], [-4, 1.5, -0.8, 0], clamp)}deg`,
        }}
      >
        {lead ? <span style={{ fontWeight: 800 }}>{lead.replace(/[«»]/g, "")}</span> : null}
        {lead ? " " : null}
        <NoBreak text={rest} />
      </div>
    </AbsoluteFill>
  );
};

const Reaction: React.FC<{ text: string; index: number }> = ({ text, index }) => {
  const frame = useCurrentFrame();
  const { durationInFrames: d } = useVideoConfig();
  const look = useLook();
  const tilt = index % 2 === 0 ? -3 : 3;
  return (
    <AbsoluteFill
      style={{
        top: look.low.top,
        height: look.low.height,
        left: SAFE.left,
        width: SAFE_W,
        alignItems: "center",
        justifyContent: "center",
        opacity: interpolate(frame, [d - 4, d], [1, 0], clamp),
      }}
    >
      <div
        style={{
          ...captionStyle(look, 72),
          maxWidth: SAFE_W,
          scale: interpolate(frame, [0, 5, d], [0.3, 1.04, 1.08], {
            ...clamp,
            easing: Easing.out(Easing.back(2.4)),
          }),
          rotate: `${interpolate(frame, [0, 5, d], [tilt * 3, tilt, tilt * 0.5], clamp)}deg`,
        }}
      >
        <NoBreak text={text} />
      </div>
    </AbsoluteFill>
  );
};
