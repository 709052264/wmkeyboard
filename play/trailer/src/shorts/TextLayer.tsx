import React from "react";
import { AbsoluteFill, Easing, Sequence, interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { Headline } from "./Headline";
import { useLook } from "./looks";
import { SAFE, SAFE_W, beatFrames, beatStarts, hookFrames } from "./layout";
import type { ShortSpec } from "./types";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// Box for one headline in the look's head band (top on card/device/paper,
// low on the scrim for bleed). It keeps drifting in scale for as
// long as it is up, then flicks out upward in its last 5 frames.
export const TextBand: React.FC<{ children: React.ReactNode; top?: number; height?: number }> = ({
  children,
  top,
  height,
}) => {
  const frame = useCurrentFrame();
  const look = useLook();
  const { durationInFrames: d } = useVideoConfig();
  return (
    <AbsoluteFill
      style={{
        top: top ?? look.head.top,
        height: height ?? look.head.height,
        left: SAFE.left,
        width: SAFE_W,
        alignItems: "center",
        justifyContent: "center",
        scale: interpolate(frame, [0, d], [1, 1.05], clamp),
        opacity: interpolate(frame, [d - 5, d], [1, 0], clamp),
        translate: `0px ${interpolate(frame, [d - 5, d], [0, -50], { ...clamp, easing: Easing.in(Easing.cubic) })}px`,
      }}
    >
      {children}
    </AbsoluteFill>
  );
};

// Hook first, then each beat's caption, all in the head band. A beat's
// caption starts once the hook is down.
export const HookThenCaptions: React.FC<{
  spec: ShortSpec;
  render: (text: string, isHook: boolean) => React.ReactNode;
  captions?: boolean;
  vo?: boolean; // use the look's voHead band (clear of spoken words)
}> = ({ spec, render, captions = true, vo = false }) => {
  const look = useLook();
  const band = vo && look.voHead ? look.voHead : look.head;
  const hook = hookFrames(spec);
  const starts = beatStarts(spec.beats);
  return (
    <>
      <Sequence durationInFrames={hook} name="Hook">
        <TextBand top={band.top} height={band.height}>
          {render(spec.hook, true)}
        </TextBand>
      </Sequence>
      {captions
        ? spec.beats.map((b, i) => {
            const from = Math.max(starts[i], hook);
            const to = starts[i] + beatFrames(b);
            if (!b.caption || to - from < 12) {
              return null;
            }
            return (
              <Sequence key={i} from={from} durationInFrames={to - from} name={`Caption ${i + 1}`}>
                <TextBand top={band.top} height={band.height}>
                  {render(b.caption, false)}
                </TextBand>
              </Sequence>
            );
          })
        : null}
    </>
  );
};

export const Kinetic: React.FC<{ text: string; size: number }> = ({ text, size }) => (
  <div style={{ maxWidth: SAFE_W }}>
    <Headline text={text} size={size} />
  </div>
);
