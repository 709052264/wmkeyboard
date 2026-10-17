import React from "react";
import { Chip } from "../components/Chip";
import { VIOLET } from "../theme";
import { ShortScaffold } from "./ShortScaffold";
import { HookThenCaptions, Kinetic } from "./TextLayer";
import { WordCaptions } from "./WordCaptions";
import type { ShortProps } from "./types";

// Voiceover + word-timed captions. The hook headline holds the top band for
// the first beat; after that each beat names its feature as a chip while the
// spoken words run in the lower band. The bed ducks low under the voice.
export const VoShort: React.FC<ShortProps> = (props) => {
  const accent = props.spec.accent ?? VIOLET;
  return (
    <ShortScaffold {...props} bedVolume={0.22}>
      <HookThenCaptions
        spec={props.spec}
        vo
        render={(text, isHook) =>
          isHook ? (
            <Kinetic text={text} size={80} />
          ) : (
            <Chip label={text.replace(/[«»]/g, "")} accent={accent} size={46} />
          )
        }
      />
      {props.spec.vo ? <WordCaptions src={props.spec.vo.words} accent={accent} /> : null}
    </ShortScaffold>
  );
};
