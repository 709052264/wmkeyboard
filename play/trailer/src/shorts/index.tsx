import React from "react";
import { Composition, Folder } from "remotion";
import { FPS, V_HEIGHT, V_WIDTH } from "../theme";
import { HookShort } from "./HookShort";
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

// Short01..ShortNN, one per SHORTS entry. Render the stem master with
// --props='{"bed":false}', the safe-zone check with '{"safeZone":true}'.
export const ShortsFolder: React.FC = () => (
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
);
