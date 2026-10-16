import React from "react";
import { ShortScaffold } from "./ShortScaffold";
import { HookThenCaptions, Kinetic } from "./TextLayer";
import type { ShortProps } from "./types";

// Music + kinetic text: the hook springs in word by word on frame 0, then
// every beat swaps in its own headline over the footage.
export const TextShort: React.FC<ShortProps> = (props) => (
  <ShortScaffold {...props} bedVolume={0.7}>
    <HookThenCaptions
      spec={props.spec}
      render={(text, isHook) => <Kinetic text={text} size={isHook ? 96 : 82} />}
    />
  </ShortScaffold>
);
