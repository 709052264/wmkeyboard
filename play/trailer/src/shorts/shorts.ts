import { AMBER, CYAN, LIME, MAGENTA, VIOLET } from "../theme";
import type { ShortSpec } from "./types";

// One entry per short, registered in order as Short01, Short02, ...
// Clips are public/ paths; record.sh `shorts trim` drops new ones into
// public/shorts/clips and `shorts vo` writes a placeholder VO + word JSON.
export const SHORTS: ShortSpec[] = [
  {
    id: "glide-text",
    style: "text",
    hook: "Stop tapping. «Glide» instead.",
    accent: VIOLET,
    accentB: CYAN,
    beats: [
      { clip: "clips/glide.mp4", sec: 2.4, focus: "keyboard" },
      {
        clip: "clips/glide.mp4",
        sec: 3,
        caption: "One swipe, one «word»",
        rate: 1.1,
        focus: { zoom: 2, y: 1, toZoom: 2.4, toY: 0.95 },
      },
      {
        clip: "clips/glide.mp4",
        sec: 3,
        caption: "It «learns» how you write",
        from: 1.6,
        focus: "keyboard",
        // zoom in on the strip as "keyboard" lands
        zoom: { at: 0.9, to: [470, 1240, 360, 90], hold: 1.2 },
      },
      {
        clip: "clips/glide.mp4",
        sec: 3,
        caption: "Every word stays on «your phone»",
        from: 2.8,
        focus: "full",
      },
      {
        clip: "clips/glide.mp4",
        sec: 3,
        caption: "Glide in «any language»",
        rate: 1.3,
        focus: { zoom: 2.3, y: 0.92, toZoom: 2, toY: 1 },
      },
    ],
  },
  {
    id: "calc-vo",
    style: "vo",
    hook: "Your keyboard can do «math»",
    accent: AMBER,
    accentB: VIOLET,
    vo: { file: "shorts/vo/calc-vo.wav", words: "shorts/vo/calc-vo.json" },
    beats: [
      { clip: "clips/calc.mp4", sec: 2.2, focus: "keyboard" },
      {
        clip: "clips/calc.mp4",
        sec: 3.2,
        caption: "Answers in the «strip»",
        from: 1.0,
        rate: 0.25,
        focus: { zoom: 2, y: 0.55, toZoom: 2.2, toY: 0.6 },
        // zoom in on the calc chip: 47+15 → 62
        zoom: { at: 0.4, to: [250, 1236, 560, 88], hold: 1.6 },
      },
      {
        clip: "clips/calc.mp4",
        sec: 2.6,
        caption: "No calculator app",
        focus: "full",
      },
      {
        clip: "clips/calc.mp4",
        sec: 2.4,
        caption: "Currency and units",
        from: 0.6,
        focus: "keyboard",
      },
      {
        clip: "clips/glide.mp4",
        sec: 3.6,
        caption: "Glide typing",
        focus: "keyboard",
      },
      {
        clip: "clips/glide.mp4",
        sec: 2.4,
        caption: "On-device learning",
        from: 2.4,
        focus: "full",
      },
    ],
  },
  {
    id: "themes-hook",
    style: "hook",
    hook: "POV: you got bored of your keyboard at 1 am",
    accent: MAGENTA,
    accentB: LIME,
    beats: [
      {
        clip: "clips/themes.mp4",
        sec: 2.4,
        caption: "wait…",
        focus: { zoom: 1, y: 0.5, toZoom: 1.15 },
      },
      {
        // carousel: one theme card per cut, zoomed in on that card
        carousel: [
          {
            label: "Arcade Cabinet",
            clip: "clips/themes.mp4",
            from: 1,
            rate: 0.3,
            focus: { zoom: 2, y: 0.52, toZoom: 2.1 },
            zoom: { at: 0, to: [50, 1066, 476, 454], hold: 1 },
          },
          {
            label: "Blockland",
            clip: "clips/themes.mp4",
            from: 1,
            rate: 0.3,
            focus: { zoom: 2, y: 0.52, toZoom: 2.1 },
            zoom: { at: 0, to: [554, 1066, 476, 454], hold: 1 },
          },
          {
            label: "Deep Orbit",
            clip: "clips/themes.mp4",
            from: 1,
            rate: 0.3,
            focus: { zoom: 2, y: 0.95, toZoom: 2.1 },
            zoom: { at: 0, to: [50, 1552, 476, 454], hold: 1 },
          },
          {
            label: "Facet",
            clip: "clips/themes.mp4",
            from: 1,
            rate: 0.3,
            focus: { zoom: 2, y: 0.95, toZoom: 2.1 },
            zoom: { at: 0, to: [554, 1552, 476, 454], hold: 1 },
          },
          {
            label: "Sakura Breeze",
            clip: "clips/themes.mp4",
            from: 1,
            rate: 0.3,
            focus: { zoom: 2, y: 1, toZoom: 2.1 },
            zoom: { at: 0, to: [554, 2040, 476, 330], hold: 1 },
          },
        ],
      },
      {
        clip: "clips/themes.mp4",
        sec: 3,
        caption: "or build your own",
        from: 1,
        focus: { zoom: 2.4, y: 0.85, toZoom: 2, toY: 1 },
      },
      {
        clip: "clips/glide.mp4",
        sec: 3,
        caption: "and it still types like this",
        focus: "keyboard",
      },
    ],
  },
];
