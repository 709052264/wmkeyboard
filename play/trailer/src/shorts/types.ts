// Data model for the vertical promo shorts. One ShortSpec per video; the
// template picked by `style` turns it into a 1080x1920 composition.

export type ShortStyle = "text" | "vo" | "hook";

// Crop window into the 1080x2260 screen recording (status bar already off).
// zoom 1 fits the whole screen to the card height; zoom 2 fills the card
// width, so only half the screen shows. y picks which part: 0 top, 1 bottom.
// toZoom / toY animate across the beat so the shot never holds still.
export type Focus = {
  zoom?: number;
  y?: number;
  toZoom?: number;
  toY?: number;
};

export type FocusPreset = "keyboard" | "full" | "top";

// Punch-in on part of the frame: springs in over ~10 frames at `at`
// (seconds into the beat), holds for `hold` seconds, springs back out.
// `to` is [x, y, w, h] in source-clip pixels (1080x2400, status bar
// included), so rects can be read straight off a frame grab.
export type Zoom = {
  at: number;
  to: [number, number, number, number];
  hold: number;
  ring?: boolean; // accent ring on the target, default true
};

export type ClipBeat = {
  // Path under public/, e.g. "clips/glide.mp4" or "shorts/clips/foo.mp4".
  clip: string;
  sec: number;
  caption?: string; // «word» gets the gradient accent
  from?: number; // seconds into the clip
  rate?: number; // playback rate
  focus?: Focus | FocusPreset;
  zoom?: Zoom;
};

// One cut of a carousel: a short clip beat with its own label chip on top
// of the card. sec defaults to 1.25 (keep it within 1-1.5).
export type CarouselItem = Omit<ClipBeat, "sec" | "caption"> & {
  label: string;
  sec?: number;
};

// Rapid cuts, one feature or language per item (scripts day 5 and day 11).
export type CarouselBeat = {
  carousel: CarouselItem[];
  caption?: string;
};

export type Beat = ClipBeat | CarouselBeat;

export type ShortSpec = {
  day?: number; // posting day; the composition becomes DayNN
  id: string; // slug, also the out/shorts/<id>-*.mp4 file stem
  style: ShortStyle;
  hook: string; // must read in the first 1.5 s
  beats: Beat[];
  hookSec?: number; // how long the hook stays up (text/vo); default 2, or all of beat 0 when it has no caption
  vo?: {
    file: string; // under public/, e.g. "shorts/vo/02.wav"
    words: string; // Caption[] JSON under public/
  };
  bed?: string; // music file under public/, default "music.mp3"
  accent?: string;
  accentB?: string;
  outroSec?: number; // default 2
};

// `bed: false` renders the stem master (VO + SFX only) from the same comp.
export type ShortProps = {
  spec: ShortSpec;
  bed: boolean;
  safeZone: boolean;
};
