import React from "react";
import { Series } from "remotion";
import { AVAILABLE_CLIPS } from "../clips";
import { PhoneFrame } from "../components/PhoneFrame";
import { Sfx } from "../components/Sfx";
import { AMBER, CYAN, LIME } from "../theme";
import { SceneSplit } from "./SceneSplit";

export const TOOLS_FRAMES = 200;

// Rapid cuts through toolbar panels; headline persists, phone + chip swap.
// Cuts whose clip is not yet captured are dropped (no static fallback).
const CUTS: { clip: string; label: string; accent: string }[] = [
  { clip: "toolbox", label: "75+ built-in tools", accent: AMBER },
  { clip: "dictionary", label: "Dictionary & Wikipedia", accent: CYAN },
  { clip: "calc", label: "Calculator in the strip", accent: AMBER },
  { clip: "voice", label: "On-device voice typing", accent: LIME },
];

export const SceneTools: React.FC = () => {
  const cuts = CUTS.filter((c) => AVAILABLE_CLIPS.includes(`${c.clip}.mp4`));
  const per = Math.floor(TOOLS_FRAMES / Math.max(cuts.length, 1));
  return (
    <SceneSplit
      headline="A «real» toolbox"
      sub="Calculator, translator, scanner, clipboard history, AI writing — one tap from the keyboard."
      chips={[
        { label: "QR + document scanner", accent: AMBER },
        { label: "Offline Whisper dictation", accent: LIME },
        { label: "Snippets with {date} {clip}", accent: null },
      ]}
      accentA={AMBER}
      accentB={CYAN}
    >
      <Series>
        {cuts.map((cut) => (
          <Series.Sequence key={cut.clip} durationInFrames={per} layout="none">
            <Sfx name="swap" volume={0.45} />
            <PhoneFrame clip={cut.clip} width={400} delay={0} label={cut.label} />
          </Series.Sequence>
        ))}
      </Series>
    </SceneSplit>
  );
};
