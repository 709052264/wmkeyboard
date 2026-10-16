import React from "react";
import { Composition, Folder, Still } from "remotion";
import { FPS, V_HEIGHT, V_WIDTH } from "../theme";
import { HookShort } from "./HookShort";
import { Thumb, type ThumbProps } from "./Thumb";
import { TextShort } from "./TextShort";
import { VoShort } from "./VoShort";
import { shortDuration } from "./layout";
import { SHORTS } from "./shorts";
import type { ShortProps, ShortStyle } from "./types";

const TEMPLATES: Record<ShortStyle, React.FC<ShortProps>> = {
  text: TextShort,
  vo: VoShort,
  hook: HookShort,
};

export const shortCompositionId = (i: number) => {
  const day = SHORTS[i].day;
  return day !== undefined
    ? `Day${String(day).padStart(2, "0")}`
    : `Short${String(i + 1).padStart(2, "0")}`;
};

// One composition per SHORTS entry (DayNN, or ShortNN without a day).
// Render the stem master with --props='{"bed":false}', the safe-zone check
// with '{"safeZone":true}', another look with '{"look":"paper"}'. Each one
// also gets two cover stills: DayNN-thumb (1080x1920) and DayNN-thumb-yt
// (1280x720).
export const ShortsFolder: React.FC = () => (
  <>
    <Folder name="Shorts">
      {SHORTS.map((spec, i) => (
        <Composition
          key={spec.id}
          id={shortCompositionId(i)}
          component={TEMPLATES[spec.style]}
          durationInFrames={shortDuration(spec)}
          fps={FPS}
          width={V_WIDTH}
          height={V_HEIGHT}
          defaultProps={{ spec, bed: true, safeZone: false } satisfies ShortProps}
        />
      ))}
    </Folder>
    <Folder name="Thumbs">
      {SHORTS.map((spec, i) => (
        <React.Fragment key={spec.id}>
          <Still
            id={`${shortCompositionId(i)}-thumb`}
            component={Thumb}
            width={V_WIDTH}
            height={V_HEIGHT}
            defaultProps={{ spec } satisfies ThumbProps}
          />
          <Still
            id={`${shortCompositionId(i)}-thumb-yt`}
            component={Thumb}
            width={1280}
            height={720}
            defaultProps={{ spec } satisfies ThumbProps}
          />
        </React.Fragment>
      ))}
    </Folder>
  </>
);
