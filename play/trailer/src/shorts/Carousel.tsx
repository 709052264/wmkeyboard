import React from "react";
import { AbsoluteFill, Sequence, interpolate, useCurrentFrame } from "remotion";
import { Chip } from "../components/Chip";
import { Sfx } from "../components/Sfx";
import { ScreenCard } from "./ScreenCard";
import { CARD, SAFE_CX, itemBeat, sec } from "./layout";
import type { CarouselBeat } from "./types";

// Rapid cuts, one item every 1-1.5 s, each with its label chip riding on
// top of the card. A soft whoosh marks every cut inside the carousel.
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
      {starts.slice(1).map((s) => (
        <Sfx key={s} name="whoosh" at={s - 2} volume={0.18} />
      ))}
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
  return (
    <AbsoluteFill
      style={{
        top: CARD.top + 26,
        height: 120,
        left: SAFE_CX - 440,
        width: 880,
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
