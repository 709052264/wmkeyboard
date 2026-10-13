import { useVideoConfig } from "remotion";

// True when rendering the 9:16 composition; scenes reflow instead of forking.
export const useVertical = (): boolean => {
  const { width, height } = useVideoConfig();
  return height > width;
};
