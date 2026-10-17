import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  AbsoluteFill,
  Easing,
  Sequence,
  interpolate,
  staticFile,
  useCurrentFrame,
  useDelayRender,
  useVideoConfig,
} from "remotion";
import type { Caption, TikTokPage } from "@remotion/captions";
import { captionStyle } from "./Headline";
import { SAFE, SAFE_W } from "./layout";
import { useLook } from "./looks";

// Phrase pages: words split at punctuation and pauses into phrases; a
// phrase of up to KEEP_WORDS stays whole, a longer one breaks into even
// chunks of at most MAX_WORDS (6 -> 3+3, 7 -> 4+3). A lone trailing word
// joins the page before it unless a sentence ends there.
const MAX_WORDS = 4;
const KEEP_WORDS = 5;
const PAUSE_MS = 320;
const ENDS_PHRASE = /[.,!?;:…—–)"”]$/;
const ENDS_SENTENCE = /[.!?…]["”)]?$/;

export const phrasePages = (captions: Caption[]): TikTokPage[] => {
  const phrases: Caption[][] = [];
  let cur: Caption[] = [];
  captions.forEach((c, i) => {
    cur.push(c);
    const next = captions[i + 1];
    const pause = next ? next.startMs - c.endMs : Infinity;
    if (!next || ENDS_PHRASE.test(c.text.trim()) || pause > PAUSE_MS) {
      phrases.push(cur);
      cur = [];
    }
  });
  const groups = phrases.flatMap((ph) => {
    if (ph.length <= KEEP_WORDS) return [ph];
    const n = Math.ceil(ph.length / MAX_WORDS);
    const size = Math.ceil(ph.length / n);
    const out: Caption[][] = [];
    for (let k = 0; k < ph.length; k += size) out.push(ph.slice(k, k + size));
    return out;
  });
  const merged: Caption[][] = [];
  for (const g of groups) {
    const prev = merged[merged.length - 1];
    const prevEnd = prev?.[prev.length - 1]?.text.trim() ?? "";
    if (g.length === 1 && prev && prev.length < KEEP_WORDS && !ENDS_SENTENCE.test(prevEnd)) {
      prev.push(...g);
    } else {
      merged.push(g);
    }
  }
  return merged.map((g) => ({
    text: g.map((c) => c.text).join(""),
    startMs: g[0].startMs,
    durationMs: g[g.length - 1].endMs - g[0].startMs,
    tokens: g.map((c, k) => ({
      // each page starts flush: drop the first word's leading space
      text: k === 0 ? c.text.trimStart() : c.text.startsWith(" ") ? c.text : ` ${c.text}`,
      fromMs: c.startMs,
      toMs: c.endMs,
    })),
  }));
};

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

// Spoken captions from a Caption[] JSON (word timestamps). Pages of a few
// words sit in the lower band; each word pops in at its own timestamp and
// the one being spoken takes the accent colour.
export const WordCaptions: React.FC<{ src: string; accent: string }> = ({
  src,
  accent,
}) => {
  const [captions, setCaptions] = useState<Caption[] | null>(null);
  const { delayRender, continueRender, cancelRender } = useDelayRender();
  const [handle] = useState(() => delayRender(`captions ${src}`));
  const { fps } = useVideoConfig();

  const load = useCallback(async () => {
    try {
      const res = await fetch(staticFile(src));
      setCaptions((await res.json()) as Caption[]);
      continueRender(handle);
    } catch (e) {
      cancelRender(e);
    }
  }, [src, continueRender, cancelRender, handle]);

  useEffect(() => {
    load();
  }, [load]);

  const pages = useMemo(() => (captions ? phrasePages(captions) : []), [captions]);

  return (
    <>
      {pages.map((page, i) => {
        const next = pages[i + 1] ?? null;
        const start = Math.round((page.startMs / 1000) * fps);
        const end = Math.round(
          Math.min(
            next ? (next.startMs / 1000) * fps : Infinity,
            ((page.startMs + page.durationMs) / 1000) * fps + 8,
          ),
        );
        if (end - start <= 0) {
          return null;
        }
        return (
          <Sequence
            key={i}
            from={start}
            durationInFrames={end - start}
            name={`Words ${i + 1}`}
          >
            <CaptionPage page={page} accent={accent} />
          </Sequence>
        );
      })}
    </>
  );
};

const CaptionPage: React.FC<{ page: TikTokPage; accent: string }> = ({
  page,
  accent,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const look = useLook();
  const nowMs = page.startMs + (frame / fps) * 1000;
  const base = captionStyle(look, 84);
  return (
    <AbsoluteFill
      style={{
        top: look.low.top,
        height: look.low.height,
        left: SAFE.left,
        width: SAFE_W,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <div style={{ ...base, whiteSpace: "pre-wrap", maxWidth: SAFE_W }}>
        {page.tokens.map((t) => {
          const since = ((nowMs - t.fromMs) / 1000) * fps; // frames since the word began
          const active = t.fromMs <= nowMs && t.toMs > nowMs;
          // Leading space stays outside the scaled span, or the popped-up
          // active word swallows the gap before it.
          const lead = t.text.match(/^\s*/)?.[0] ?? "";
          return (
            <React.Fragment key={t.fromMs}>
              {lead}
              <span
                style={{
                  display: "inline-block",
                // grow rightward: the newest word has nothing after it yet, so the
                // pop overshoot never eats the space before it
                transformOrigin: "0% 60%",
                  color: active && !look.light ? accent : base.color,
                  ...(active && look.light
                    ? { background: `linear-gradient(180deg, transparent 55%, ${accent}AA 55%, ${accent}AA 95%, transparent 95%)` }
                    : {}),
                  opacity: interpolate(since, [0, 2], [0, 1], clamp),
                  scale: interpolate(since, [0, 5], [0.55, active ? 1.08 : 1], {
                    ...clamp,
                    easing: Easing.out(Easing.back(2.2)),
                  }),
                  translate: `0px ${interpolate(since, [0, 5], [24, 0], clamp)}px`,
                }}
              >
                {t.text.slice(lead.length)}
              </span>
            </React.Fragment>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};
