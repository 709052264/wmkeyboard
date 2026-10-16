import "./index.css";
import React from "react";
import { Composition, Folder } from "remotion";
import { Trailer, TRAILER_DURATION } from "./Trailer";
import { FPS, HEIGHT, V_HEIGHT, V_WIDTH, WIDTH } from "./theme";
import { SceneAddons } from "./scenes/SceneAddons";
import { SceneEmoji } from "./scenes/SceneEmoji";
import { SceneGlide } from "./scenes/SceneGlide";
import { SceneIntro } from "./scenes/SceneIntro";
import { SceneModes } from "./scenes/SceneModes";
import { SceneOutro } from "./scenes/SceneOutro";
import { SceneThemes } from "./scenes/SceneThemes";
import { SceneTools } from "./scenes/SceneTools";
import { ShortsFolder } from "./shorts";

const size = { fps: FPS, width: WIDTH, height: HEIGHT };

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition id="Trailer" component={Trailer} durationInFrames={TRAILER_DURATION} {...size} />
      <Composition
        id="TrailerVertical"
        component={Trailer}
        durationInFrames={TRAILER_DURATION}
        fps={FPS}
        width={V_WIDTH}
        height={V_HEIGHT}
      />
      <Folder name="Scenes">
        <Composition id="Intro" component={SceneIntro} durationInFrames={70} {...size} />
        <Composition id="Glide" component={SceneGlide} durationInFrames={150} {...size} />
        <Composition id="Themes" component={SceneThemes} durationInFrames={120} {...size} />
        <Composition id="Tools" component={SceneTools} durationInFrames={200} {...size} />
        <Composition id="Modes" component={SceneModes} durationInFrames={120} {...size} />
        <Composition id="Emoji" component={SceneEmoji} durationInFrames={140} {...size} />
        <Composition id="Addons" component={SceneAddons} durationInFrames={130} {...size} />
        <Composition id="Outro" component={SceneOutro} durationInFrames={120} {...size} />
      </Folder>
      <ShortsFolder />
    </>
  );
};
