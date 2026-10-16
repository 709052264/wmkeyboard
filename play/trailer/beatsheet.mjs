// Beat check sheet: for each beat of a short, the source frames across the
// window it plays (from .. from + sec * rate, looping like ScreenCard does),
// with the card's visible crop drawn in cyan and the zoom target in red, and
// the beat's caption beside them. Catches captions that describe a different
// moment than the footage, and crops or zooms that cut off the subject,
// without rendering anything.
//
//   node beatsheet.mjs [DayNN ...]   ->  out/shorts/<id>-beats.png
import { buildSync } from "esbuild";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const SRC_W = 1080;
const SRC_H = 2400;
const STATUS_BAR = 140;
const SCREEN_H = SRC_H - STATUS_BAR; // 2260
const CARD = { height: 890, maxWidth: 860 };
const FRAMES = 5;
const TILE_W = 216;
const FONT = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf";
const SCALE = TILE_W / SRC_W;

const tmp = mkdtempSync(join(tmpdir(), "beatsheet-"));
const bundle = join(tmp, "shorts.mjs");
buildSync({
  entryPoints: ["src/shorts/shorts.ts"],
  bundle: true,
  format: "esm",
  platform: "node",
  outfile: bundle,
  logLevel: "error",
});
const { SHORTS } = await import(pathToFileURL(bundle).href);
const { SHORT_CLIPS } = await import(
  pathToFileURL(
    (buildSync({ entryPoints: ["src/shorts/clips.ts"], bundle: true, format: "esm", platform: "node", outfile: join(tmp, "clips.mjs"), logLevel: "error" }),
    join(tmp, "clips.mjs")),
  ).href
);

const PRESETS = {
  keyboard: { zoom: 2, y: 1, toZoom: 2.15, toY: 1 },
  full: { zoom: 1, toZoom: 1.08, y: 0.5 },
  top: { zoom: 2, y: 0, toY: 0.25 },
};
const focusOf = (f) => {
  const b = typeof f === "string" ? PRESETS[f] : (f ?? PRESETS.keyboard);
  const zoom = b.zoom ?? 1;
  const y = b.y ?? 0.5;
  return { zoom, y, toZoom: b.toZoom ?? zoom * 1.06, toY: b.toY ?? y };
};
// Source-pixel rect the card shows for a given zoom / y (mirrors ScreenCard).
const cropRect = (zoom, y) => {
  const fitW = CARD.height / (SCREEN_H / SRC_W);
  const videoW = fitW * zoom;
  const cardW = Math.min(CARD.maxWidth, videoW);
  const w = (cardW / videoW) * SRC_W;
  const h = SCREEN_H / zoom;
  const top = STATUS_BAR + (SCREEN_H - h) * y;
  return [(SRC_W - w) / 2, top, w, h];
};

const ids = process.argv.slice(2);
const pad = (n) => String(n).padStart(2, "0");
for (const spec of SHORTS) {
  const comp = spec.day !== undefined ? `Day${pad(spec.day)}` : spec.id;
  if (ids.length && !ids.includes(comp)) continue;
  const beats = spec.beats.flatMap((b, i) =>
    "carousel" in b
      ? b.carousel.map((it, k) => ({ ...it, sec: it.sec ?? 1.25, caption: `[${it.label}]${k === 0 && b.caption ? " " + b.caption : ""}`, n: `${i + 1}.${k + 1}` }))
      : [{ ...b, n: String(i + 1) }],
  );
  const rows = [];
  beats.forEach((b, r) => {
    const len = SHORT_CLIPS[b.clip] ?? 1e9;
    const rate = b.rate ?? 1;
    const from = b.from ?? 0;
    const f = focusOf(b.focus);
    const tiles = [];
    for (let k = 0; k < FRAMES; k++) {
      const local = (b.sec * k) / (FRAMES - 1);
      const t = (from + local * rate) % len;
      const zoom = f.zoom + (f.toZoom - f.zoom) * (k / (FRAMES - 1));
      const y = f.y + (f.toY - f.y) * (k / (FRAMES - 1));
      const [cx, cy, cw, ch] = cropRect(zoom, y).map((v) => v * SCALE);
      const draws = [
        "-fill", "none", "-stroke", "#22d3ee", "-strokewidth", "2",
        "-draw", `rectangle ${cx},${cy} ${cx + cw},${cy + ch}`,
      ];
      if (b.zoom && local >= b.zoom.at && local <= b.zoom.at + b.zoom.hold + 0.4) {
        const [zx, zy, zw, zh] = b.zoom.to.map((v) => v * SCALE);
        draws.push("-stroke", "#ff3355", "-strokewidth", "3", "-draw", `rectangle ${zx},${zy} ${zx + zw},${zy + zh}`);
      }
      const out = join(tmp, `r${r}-${k}.png`);
      execFileSync("ffmpeg", ["-v", "error", "-y", "-ss", t.toFixed(3), "-i", join("public", b.clip), "-frames:v", "1", "-vf", `scale=${TILE_W}:-1`, out]);
      execFileSync("magick", [out, ...draws,
        "-stroke", "none", "-font", FONT, "-fill", "#ffffff", "-undercolor", "#000000cc", "-pointsize", "15", "-gravity", "south",
        "-annotate", "+0+4", `${t.toFixed(2)}s`, out]);
      tiles.push(out);
    }
    const row = join(tmp, `row${r}.png`);
    const label = `${b.n}  ${b.sec}s  x${rate}  from ${from}\n${b.clip.split("/").pop()}\n\n${(b.caption ?? "(no caption)").replace(/[«»]/g, "")}`;
    execFileSync("magick", [
      "(", "-size", "300x480", "-background", "#111", "-fill", "#eee", "-font", FONT, "-pointsize", "17",
      `caption:${label}`, ")", ...tiles, "+append", row,
    ]);
    rows.push(row);
  });
  const sheet = join("out", "shorts", `${comp}-beats.png`);
  execFileSync("magick", [...rows, "-background", "#333", "-splice", "0x6", "-append", sheet]);
  console.log(`wrote ${sheet} (${beats.length} beats)`);
}
rmSync(tmp, { recursive: true, force: true });
