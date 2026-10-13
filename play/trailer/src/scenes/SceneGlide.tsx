import React from "react";
import { PhoneFrame } from "../components/PhoneFrame";
import { CYAN, LIME, VIOLET } from "../theme";
import { SceneSplit } from "./SceneSplit";

export const SceneGlide: React.FC = () => {
  return (
    <SceneSplit
      headline="Typing that «learns,» locally"
      sub="Glide, autocorrect and next-word prediction — every bit of it on your device."
      chips={[
        { label: "Glide in any language", accent: VIOLET },
        { label: "On-device learning", accent: LIME },
        { label: "Hundreds of languages", accent: CYAN },
      ]}
    >
      <PhoneFrame clip="glide" still="kb-english-dark.png" width={400} delay={4} tilt={-2} />
    </SceneSplit>
  );
};
