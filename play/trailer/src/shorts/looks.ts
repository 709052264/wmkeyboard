import { createContext, useContext } from "react";
import { BODY, CYAN, INK, VIOLET, WHITE } from "../theme";
import { CARD, LOWER_BAND, SAFE, TEXT_ZONE } from "./layout";
import type { Look } from "./types";

type Band = { top: number; height: number };

export type LookDef = {
  name: Look;
  // How the footage sits: a rounded card, the whole frame, or a phone.
  frame: "card" | "bleed" | "device";
  card: { top: number; height: number; maxWidth: number; radius: number };
  head: Band; // hook and beat headlines
  low: Band; // spoken words and meme reactions
  light: boolean;
  ink: string; // headline colour
  soft: string; // secondary text
  caption: "outline" | "pill" | "highlight";
  cut: "punch" | "slide" | "swing" | "drop";
  headScale: number; // headline size multiplier
};

const lowFromBottom = (bottom: number, height: number): Band => ({ top: 1920 - bottom - height, height });

export const LOOKS: Record<Look, LookDef> = {
  card: {
    name: "card",
    frame: "card",
    card: { ...CARD, radius: 54 },
    head: TEXT_ZONE,
    low: lowFromBottom(LOWER_BAND.bottom, LOWER_BAND.height),
    light: false,
    ink: WHITE,
    soft: BODY,
    caption: "outline",
    cut: "punch",
    headScale: 1,
  },
  // Footage edge to edge; headlines sit low on a dark scrim, spoken words
  // ride mid-frame like native captions.
  bleed: {
    name: "bleed",
    frame: "bleed",
    card: { top: 0, height: 1920, maxWidth: 1080, radius: 0 },
    head: { top: 1140, height: 340 },
    low: { top: 820, height: 260 },
    light: false,
    ink: WHITE,
    soft: "rgba(255,255,255,0.8)",
    caption: "outline",
    cut: "slide",
    headScale: 1.05,
  },
  // A tilted phone below oversized type; captions as solid pills.
  device: {
    name: "device",
    frame: "device",
    card: { top: 640, height: 1060, maxWidth: 1080, radius: 0 },
    head: { top: SAFE.top + 10, height: 400 },
    low: { top: 1220, height: 240 },
    light: false,
    ink: WHITE,
    soft: BODY,
    caption: "pill",
    cut: "swing",
    headScale: 1.22,
  },
  // Cream paper, ink type, highlighter accents, soft shadows.
  paper: {
    name: "paper",
    frame: "card",
    card: { top: 620, height: 820, maxWidth: 780, radius: 40 },
    head: TEXT_ZONE,
    low: { top: 1190, height: 260 },
    light: true,
    ink: INK,
    soft: "#5A6070",
    caption: "highlight",
    cut: "drop",
    headScale: 1,
  },
};

export const PAPER = "#F4EFE6";

export const resolveLook = (look?: Look) => LOOKS[look ?? "card"] ?? LOOKS.card;

// The look plus the short's accent pair, provided once by ShortScaffold.
export type LookValue = LookDef & { accent: string; accentB: string };

export const LookContext = createContext<LookValue>({ ...LOOKS.card, accent: VIOLET, accentB: CYAN });
export const useLook = () => useContext(LookContext);
