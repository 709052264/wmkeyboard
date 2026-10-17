import React from "react";
import { AbsoluteFill, Sequence, interpolate, useCurrentFrame } from "remotion";
import { Chip } from "../components/Chip";
import { ScreenCard } from "./ScreenCard";
import { SAFE, SAFE_CX, SAFE_W, itemBeat, sec } from "./layout";
import { useLook } from "./looks";
import type { CarouselBeat } from "./types";

// Rapid cuts, one item every 1-1.5 s, each with its label chip riding on
// top of the card. Cuts inside a carousel are silent: at this pace whooshes
// would stack into a wash.
export const Carousel: React.FC<{
  beat: CarouselBeat;
  index: number;
  accent: string;
}> = ({ beat, index, accent }) => {
  const items = beat.carousel.map(itemBeat);
  const starts = items.reduce<number[]>(
    (acc, b, i) => [...acc, i === 0 ? 0 : acc[i - 1] + sec(items[i - 1].sec)],
    [],
  );
  return (
    <>
      {items.map((item, i) => (
        <Sequence
          key={i}
          from={starts[i]}
          durationInFrames={sec(item.sec)}
          name={beat.carousel[i].label}
        >
          <ScreenCard beat={item} index={index + i} accent={accent} />
          <Label text={beat.carousel[i].label} accent={accent} />
        </Sequence>
      ))}
    </>
  );
};

const Label: React.FC<{ text: string; accent: string }> = ({
  text,
  accent,
}) => {
  const frame = useCurrentFrame();
  const look = useLook();
  return (
    <AbsoluteFill
      style={{
        // on the card's top edge, never above the safe line (bleed starts at 0)
        top: Math.max(look.card.top + 26, SAFE.top + 40),
        height: 120,
        left: SAFE_CX - SAFE_W / 2,
        width: SAFE_W,
        alignItems: "center",
        justifyContent: "flex-start",
        scale: interpolate(frame, [0, 30], [1, 1.06], {
          extrapolateLeft: "clamp",
          extrapolateRight: "clamp",
        }),
      }}
    >
      <Chip label={text} accent={accent} delay={1} size={42} />
    </AbsoluteFill>
  );
};
