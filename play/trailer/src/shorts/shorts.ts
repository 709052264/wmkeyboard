import { AMBER, CYAN, LIME, MAGENTA, VIOLET } from "../theme";
import type { Focus, ShortSpec } from "./types";

// One entry per short; `day` names the composition DayNN (posting day in
// play/shorts/tracker.md). Clips are public/ paths; record.sh `shorts trim`
// drops new ones into public/shorts/clips and `shorts vo` writes a
// placeholder VO + word JSON. The template demos live in git history
// (e1daab152).
//
// Footage notes: the chat clips are Chrome over play/shorts/canvas/chat.html,
// so their top ~430 px are Chrome's bar and the chat header. CHAT keeps the
// bubbles, the field and the keyboard and drops that band. Zoom rects are in
// source pixels (1080x2400): the suggestion strip sits at y 1355-1445.

const CHAT: Focus = { zoom: 1.25, y: 1, toZoom: 1.32, toY: 1 };
const PAGE: Focus = { zoom: 1.2, y: 0.4, toZoom: 1.26, toY: 0.5 };
// Theme editor: the live preview pinned at the top of the screen.
const PREVIEW: Focus = { zoom: 1.45, y: 0.12, toZoom: 1.55, toY: 0.12 };
const STRIP: [number, number, number, number] = [0, 1355, 1080, 90];
// Emoji and clipboard panels open above the keys: keep the field and the whole
// panel in frame (bottom two thirds of the screen).
const PANEL: Focus = { zoom: 1.5, y: 1, toZoom: 1.56, toY: 1 };
// Theme editor scrolled to the sliders: preview at the top, sliders below it.
const SLIDERS: Focus = { zoom: 1.1, y: 0.35, toZoom: 1.16, toY: 0.4 };

export const SHORTS: ShortSpec[] = [
  {
    day: 1,
    id: "d01-offline",
    style: "vo",
    hook: "Your keyboard reads «everything» you type",
    accent: CYAN,
    accentB: VIOLET,
    vo: { file: "shorts/vo/d01.wav", words: "shorts/vo/d01.json" },
    beats: [
      { clip: "shorts/clips/s01-typing.mp4", sec: 3, from: 1, rate: 1.6, focus: CHAT },
      {
        clip: "shorts/clips/s01-typing.mp4",
        sec: 3,
        from: 3,
        rate: 1.4,
        focus: "keyboard",
        zoom: { at: 0.4, to: STRIP, hold: 1.2 },
      },
      { clip: "shorts/clips/s01-privacy.mp4", sec: 3, focus: "full" },
      {
        clip: "shorts/clips/s01-privacy.mp4",
        sec: 4,
        from: 3,
        rate: 1.5,
        focus: { zoom: 1.3, y: 0.4, toY: 0.7 },
      },
      {
        clip: "shorts/clips/s01-privacy.mp4",
        sec: 3,
        from: 9,
        rate: 1.2,
        focus: { zoom: 1.3, y: 0.6, toY: 0.9 },
      },
      { clip: "shorts/clips/s01-typing.mp4", sec: 3, from: 9, rate: 1.5, focus: CHAT },
    ],
  },
  {
    day: 3,
    id: "d03-avro",
    style: "hook",
    hook: "POV: you text in Banglish but your friends want real «বাংলা»",
    accent: MAGENTA,
    accentB: AMBER,
    beats: [
      {
        clip: "shorts/clips/s03-avro.mp4",
        sec: 3,
        from: 0.8,
        rate: 2,
        caption: "type «ami valo achi»",
        focus: CHAT,
      },
      {
        clip: "shorts/clips/s03-avro.mp4",
        sec: 3,
        from: 8.5,
        rate: 2.5,
        caption: "get «বাংলা»",
        focus: "keyboard",
        zoom: { at: 0.6, to: STRIP, hold: 1 },
      },
      {
        clip: "shorts/clips/s03-avro.mp4",
        sec: 2.5,
        from: 18.5,
        rate: 2,
        caption: "type how you «talk»",
        focus: CHAT,
      },
      {
        clip: "shorts/clips/s03-avro.mp4",
        sec: 1.5,
        from: 22.5,
        caption: "ধন্যবাদ «keyboard» 🙏",
        focus: CHAT,
      },
    ],
  },
  {
    day: 4,
    id: "d04-calc",
    style: "hook",
    hook: "When the group chat asks you to «split the bill»",
    accent: AMBER,
    accentB: LIME,
    beats: [
      { clip: "shorts/clips/s04-calc.mp4", sec: 2.5, focus: CHAT },
      {
        clip: "shorts/clips/s04-calc.mp4",
        sec: 3,
        from: 2.5,
        rate: 0.8,
        caption: "the «answer» is already there",
        focus: "keyboard",
        zoom: { at: 0.3, to: [330, 1355, 330, 90], hold: 1.4 },
      },
      {
        clip: "shorts/clips/s04-calc.mp4",
        sec: 3,
        from: 5,
        rate: 2.2,
        caption: "tap, «send»",
        focus: CHAT,
      },
      {
        clip: "shorts/clips/s04-calc.mp4",
        sec: 2,
        from: 12,
        caption: "no calculator app. no leaving the «chat»",
        focus: CHAT,
      },
    ],
  },
  {
    day: 5,
    id: "d05-photos",
    style: "hook",
    hook: "POV: your keyboard, but it's your «cat»",
    accent: LIME,
    accentB: CYAN,
    beats: [
      { clip: "shorts/clips/s05-photos.mp4", sec: 2, from: 33, focus: { zoom: 1.2, y: 0.25 } },
      {
        caption: "all of it in «one editor»",
        carousel: [
          { label: "Search millions", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 16.5, rate: 2.5, focus: PAGE },
          { label: "Use it", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 54.5, rate: 1.5, focus: PAGE },
          { label: "Match the colors", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 69.5, rate: 1, focus: PAGE },
          { label: "Blur it", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 118, rate: 1.8, focus: SLIDERS },
          { label: "Fade it", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 121.75, rate: 2, focus: SLIDERS },
          { label: "Tint it", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 165.5, rate: 4.5, focus: PREVIEW },
          { label: "Light or dark", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 173.5, rate: 2.5, focus: PREVIEW },
          { label: "A new one every day", clip: "shorts/clips/s05-photos.mp4", sec: 1.5, from: 227.5, rate: 1.5, focus: PAGE },
        ],
      },
      {
        clip: "shorts/clips/s05-photos.mp4",
        sec: 2.5,
        from: 140,
        caption: "your keyboard, «your photo»",
        focus: PREVIEW,
      },
    ],
  },
  {
    day: 6,
    id: "d06-emoji",
    style: "hook",
    hook: "Searching emoji in your own language? «Finally.»",
    accent: AMBER,
    accentB: MAGENTA,
    beats: [
      { clip: "shorts/clips/s06-emoji.mp4", sec: 2.5, rate: 2.2, focus: CHAT },
      {
        clip: "shorts/clips/s06-emoji.mp4",
        sec: 2.5,
        from: 8,
        rate: 1.6,
        caption: "«party»",
        focus: PANEL,
        zoom: { at: 0.5, to: [0, 1150, 1080, 280], hold: 1 },
      },
      { clip: "shorts/clips/s06-emoji.mp4", sec: 2, from: 13.6, rate: 1.4, caption: "typo? «birthdy» still works", focus: PANEL },
      { clip: "shorts/clips/s06-emoji.mp4", sec: 2, from: 17.2, rate: 1.2, caption: "Banglish «jonmodin»", focus: PANEL },
      { clip: "shorts/clips/s06-emoji.mp4", sec: 2, from: 21.6, rate: 1.3, caption: "Spanish «fiesta»", focus: PANEL },
      { clip: "shorts/clips/s06-emoji.mp4", sec: 2, from: 24.5, caption: "125 languages. typos «forgiven»", focus: CHAT },
    ],
  },
  {
    day: 7,
    id: "d07-clipboard",
    style: "vo",
    hook: "Copied it an hour ago. «Need it again.»",
    accent: VIOLET,
    accentB: CYAN,
    vo: { file: "shorts/vo/d07.wav", words: "shorts/vo/d07.json" },
    beats: [
      { clip: "shorts/clips/s07-clipboard.mp4", sec: 2, focus: CHAT },
      { clip: "shorts/clips/s07-clipboard.mp4", sec: 3, from: 1, rate: 2, focus: PANEL },
      { clip: "shorts/clips/s07-clipboard.mp4", sec: 3, from: 15, rate: 1.2, focus: PANEL },
      {
        clip: "shorts/clips/s07-clipboard.mp4",
        sec: 3,
        from: 18,
        rate: 1.2,
        focus: PANEL,
        zoom: { at: 0.3, to: [20, 1500, 520, 260], hold: 1.2 },
      },
      { clip: "shorts/clips/s07-clipboard.mp4", sec: 3, from: 20.5, focus: CHAT },
      { clip: "shorts/clips/s07-clipboard.mp4", sec: 2.5, from: 35.5, focus: CHAT },
    ],
  },
  {
    day: 9,
    id: "d09-themes",
    style: "text",
    hook: "Pick a «vibe».",
    accent: MAGENTA,
    accentB: VIOLET,
    beats: [
      { clip: "shorts/clips/s09-themes.mp4", sec: 3, rate: 3, focus: "keyboard" },
      { clip: "shorts/clips/s09-themes.mp4", sec: 3, from: 9, rate: 3, caption: "«28» looks built in", focus: "keyboard" },
      { clip: "shorts/clips/s09-themes.mp4", sec: 3, from: 18, rate: 3.5, caption: "light, dark, «animated»", focus: "keyboard" },
      { clip: "shorts/clips/s09-addons.mp4", sec: 2, rate: 1.5, caption: "more from «addon repos»", focus: PAGE },
      {
        clip: "shorts/clips/s09-addons.mp4",
        sec: 2,
        from: 13,
        caption: "one tap to «install»",
        focus: "full",
        zoom: { at: 0.6, to: [40, 1850, 320, 110], hold: 0.8 },
      },
      { clip: "shorts/clips/s09-arcade.mp4", sec: 2.5, from: 1, rate: 1.4, caption: "«ready player one»", focus: "keyboard" },
      { clip: "shorts/clips/s09-arcade.mp4", sec: 2.5, from: 4.5, caption: "or build your «own»", focus: "keyboard" },
    ],
  },
  {
    day: 10,
    id: "d10-netlog",
    style: "vo",
    hook: "This keyboard shows its «receipts»",
    accent: LIME,
    accentB: CYAN,
    vo: { file: "shorts/vo/d10.wav", words: "shorts/vo/d10.json" },
    beats: [
      { clip: "shorts/clips/s10-netlog.mp4", sec: 3.5, focus: "full" },
      { clip: "shorts/clips/s10-netlog.mp4", sec: 4, from: 2, rate: 1.2, focus: { zoom: 1.3, y: 0.2, toY: 0.4 } },
      { clip: "shorts/clips/s10-netlog.mp4", sec: 4, from: 6, rate: 1.2, focus: { zoom: 1.3, y: 0.3, toY: 0.6 } },
      { clip: "shorts/clips/s10-netlog.mp4", sec: 3.5, from: 10, focus: { zoom: 1.3, y: 0.5, toY: 0.8 } },
      {
        clip: "shorts/clips/s10-netlog.mp4",
        sec: 5,
        from: 13.5,
        rate: 0.9,
        focus: { zoom: 1.3, y: 1 },
        zoom: { at: 1, to: [40, 1870, 1000, 190], hold: 2 },
      },
      { clip: "shorts/clips/s10-netlog.mp4", sec: 2.5, from: 16, rate: 0.8, focus: { zoom: 1.3, y: 1, toZoom: 1.4 } },
    ],
  },
  {
    day: 11,
    id: "d11-languages",
    style: "text",
    hook: "One keyboard. «Hundreds» of languages.",
    accent: CYAN,
    accentB: MAGENTA,
    beats: [
      {
        caption: "switch with one «swipe»",
        carousel: [
          { label: "English", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 0, rate: 2, focus: CHAT },
          { label: "বাংলা", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 8, rate: 2.6, focus: CHAT },
          { label: "हिन्दी", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 15, rate: 2.6, focus: CHAT },
          { label: "日本語", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 22, rate: 2.2, focus: CHAT },
          { label: "العربية", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 27.5, rate: 2.6, focus: CHAT },
          { label: "Русский", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 32, rate: 3, focus: CHAT },
          { label: "Español", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 39.5, rate: 2.6, focus: CHAT },
          { label: "한국어", clip: "shorts/clips/s11-langs.mp4", sec: 1.5, from: 45.5, rate: 3.5, focus: CHAT },
        ],
      },
      {
        clip: "shorts/clips/s11-langs.mp4",
        sec: 2.5,
        from: 52,
        caption: "every message, its own «language»",
        focus: CHAT,
      },
    ],
  },
  {
    day: 15,
    id: "d15-undo",
    style: "text",
    hook: "Autocorrect changed your «word»?",
    accent: VIOLET,
    accentB: AMBER,
    beats: [
      {
        clip: "shorts/clips/s15-undo.mp4",
        sec: 2.5,
        from: 1.5,
        focus: "keyboard",
        zoom: { at: 0.6, to: [250, 1355, 300, 90], hold: 1.2 },
      },
      { clip: "shorts/clips/s15-undo.mp4", sec: 2.5, from: 4.5, rate: 1.2, caption: "tap the chip, it's «back»", focus: CHAT },
      { clip: "shorts/clips/s15-undo.mp4", sec: 3, from: 9, rate: 1.5, caption: "fix it once, it «remembers»", focus: CHAT },
    ],
  },
];
