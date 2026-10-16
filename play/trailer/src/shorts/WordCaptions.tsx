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
import { createTikTokStyleCaptions } from "@remotion/captions";
import type { Caption, TikTokPage } from "@remotion/captions";
import { MANROPE } from "../fonts";
import { WHITE } from "../theme";
import { LOWER_BAND, SAFE, SAFE_W } from "./layout";

// How long a page of words stays up before the next one replaces it.
const SWITCH_EVERY_MS = 900;
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

  const pages = useMemo(
    () =>
      captions
        ? createTikTokStyleCaptions({
            captions,
            combineTokensWithinMilliseconds: SWITCH_EVERY_MS,
          }).pages
        : [],
    [captions],
  );

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
  const nowMs = page.startMs + (frame / fps) * 1000;
  return (
    <AbsoluteFill
      style={{
        top: undefined,
        bottom: LOWER_BAND.bottom,
        height: LOWER_BAND.height,
        left: SAFE.left,
        width: SAFE_W,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          fontFamily: MANROPE,
          fontWeight: 800,
          fontSize: 92,
          lineHeight: 1.1,
          textAlign: "center",
          whiteSpace: "pre-wrap",
          color: WHITE,
          letterSpacing: "-0.01em",
          paintOrder: "stroke fill",
          WebkitTextStroke: "14px rgba(5,7,12,0.92)",
          textShadow: "0 10px 30px rgba(0,0,0,0.6)",
        }}
      >
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
                  color: active ? accent : WHITE,
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
