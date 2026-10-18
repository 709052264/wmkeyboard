# Shorts — scripts

60 vertical shorts, one a day. Each entry has the posting day, slate number (from the plan),
style, target length, the hook (on screen within 1.5 s), the VO or text beats, the shot list, and
who drives each shot: **adb** means scripted taps, **you** means a real finger or voice is needed.

**Zoom punch-ins:** the templates support a zoom beat (`zoom: {at, to: [x, y, w, h], hold}`) that
pushes in on part of the frame, holds, then eases back out. Use one or two per video, on the thing the
viewer has to notice: a chip appearing, a slider moving, a button. Each script marks them as "zoom in on …".

Styles: **V** pro voiceover with word-timed captions · **T** music and kinetic text · **H** meme-style
hook, text only. VO runs at about 2.3 words a second.

Every claim below is checked against `FEATURES.md`. Counts that drift between docs (languages,
layouts) are said as "hundreds" on purpose.

End card on every video: logo, "WM Keyboard", "Free on Google Play" (link in the caption).

---

## Batch 1 (days 1–15)

### Day 1 · #1 · Works offline, no account · V · 25 s
**Hook (0–1.5 s):** "Your keyboard reads everything you type."
**VO:** Your keyboard sees every password, every message, every search. So where does it all go?
With WM Keyboard, nowhere. There's no account to make. Prediction, autocorrect and learning all
run on your phone. Switch on airplane mode and it types exactly the same. WM Keyboard, free on
Google Play.
**Shots**
1. Typing a message in the chat canvas, prediction strip live (adb `input text`).
2. Airplane mode on, then typing a sentence with suggestions still appearing (**you**: airplane
   mode drops wireless adb and scrcpy, so this one is filmed with the phone's own screen recorder).
3. Settings › Privacy, scrolling the on-device learning controls (adb deep link).
4. End card.

### Day 2 · #7 · Glide typing · T · 18 s
**Hook:** "Still tapping every letter?"
**Beats:** glide "see you tomorrow" in one stroke across the spacebar → "Whole sentences. One
stroke." · draw a small loop on o for "good" → "Circle a key for double letters" · glide over
Shift → "Swipe through Shift for a capital" · "It learns how *your* thumb draws." · end card.
**Shots:** all **you** (adb can't inject a glide). Three clean takes of each stroke; we keep the
one whose strip shows the right word.

### Day 3 · #17 · Bangla Avro phonetic · H · 15 s
**Hook:** "POV: you text in Banglish but your friends want real বাংলা"
**Beats:** type `ami valo achi` → আমি ভালো আছি · `bangla` → বাংলা · lazy spelling `kotha` →
কথা · zoom in on the suggestion strip as the Bangla words appear · "Type how you talk." · end card.
**Shots:** adb `input text` on the বাংলা Avro subtype, in short chunks (long chunks drop letters).

### Day 4 · #47 · Calculator in the strip · H · 15 s
**Hook:** "When the group chat asks you to split the bill"
**Scenario:** a group chat in the chat canvas. Bubbles: "dinner was 2,840 total", "we're 4 people",
"how much each?". The reply gets worked out without ever leaving the chat.
**Beats:** start typing the reply `2840/4=` → calc chip shows 710 → tap → the field reads
`2840/4=710` → finish with ` each, send it 🙏` and send · zoom in on the chip as it appears ·
"No calculator app. No leaving the chat." · end card.
**Shots:** adb. The chat canvas needs these three bubbles: check whether `DocsShotActivity`
takes custom bubble text through an extra; if it doesn't, use a real messaging app with a test
contact whose name and avatar are fake.

### Day 5 · #31 · Photo backgrounds, everything they can do · H · 20 s
**Hook:** "POV: your keyboard, but it's your cat"
**Beats:** a fast carousel, one cut per feature (~1.5 s each), the same sentence being typed
across all of them so the keyboard stays the subject:
1. Your own photo, pinch-cropped to the keyboard band → "Any photo"
2. Blur slider dragged 0 → 25 → "Blur it"
3. Opacity slider, the photo fading behind the keys → "Fade it"
4. The board colour over the photo as a coloured tint (seed swatches cycling) → "Tint it"
5. An animated GIF background playing → "Animate it"
6. The stock-photo search grid (Unsplash + Pexels, topics like space and nature) → "Or search millions"
7. Rotating background set to Daily, then Shuffle tapped three times, a new photo each time →
   "A new one every day"
8. Palette seeded from the photo: keys recolour to match → "Keys match the photo"
Closing card: "All in the theme editor." · end card.
**Shots:** adb for navigation and the sliders (adb drags work outside glide); **you** for the cropper
pinch. Photos: Pixabay (commercial use OK) pushed to the phone; GIF from the same source. The
stock search needs the build's Unsplash/Pexels keys; check the grid loads before filming.

### Day 6 · #39 · Emoji search in 125 languages · H · 15 s
**Hook:** "Searching emoji in your own language? Finally."
**Beats (as captured):** friend asks "guess what day it is tomorrow 👀" · reply "wait is it your
birthday??" · emoji search `party` → 🥳🎉 · typo `birthdy` still finds 🎂 · Banglish `jonmodin`
(জন্মদিন) → 🎈 · Spanish `fiesta` → 🪅 · send · zoom in on the results row ·
"125 languages. Typos forgiven." · end card.
**Shots:** adb. Before filming: the bn and es keyword packs are downloaded (auto-download for
enabled languages).

### Day 7 · #48 · Clipboard history · V · 22 s · STAGED
**Hook:** "Copied it an hour ago. Need it again."
**VO:** You copied it an hour ago, and now you need it again. WM Keyboard keeps your clipboard
history: text, links, even screenshots. Pin what matters and swipe away what doesn't. All of it
stays on your phone.
**Shots (as captured):** friend asks "whats the address again?" · open the clipboard panel (five
seeded fake clips) · hold the flight clip for the info popup · swipe the shopping list away ("Clip
deleted · Undo") · pin the address · tap it to paste · send. Zoom in on the pin. The masked-password
beat was dropped: the in-app default keeps sensitive clips like any other (FEATURES.md says the
default is short-lived; one of the two is stale).

### Day 8 · #11 · Spacebar cursor gestures · H · 12 s
**Hook:** "You've been fixing typos wrong your whole life."
**Beats:** long paragraph, typo in the middle · slide along the spacebar, caret glides to it ·
swipe up from the spacebar → full touchpad, moving between lines · "Slide the spacebar. Swipe up
for a touchpad." · end card.
**Shots:** try adb `input swipe` on the spacebar first (a slow drag, unlike a glide, may register);
fall back to **you**.

### Day 9 · #29 · Theme speed-run + addon repos · T · 18 s
**Hook:** "Pick a vibe."
**Beats:** fast cuts timed to the beat, one theme per cut: Catppuccin Mocha, Dracula, Nord, Tokyo
Night, Solarized, Pitch black, Aurora (animated), Cyberpunk (animated) · "28 looks built in." ·
Settings › Add-ons › WM Keyboard Official Addons (47 add-ons): open Arcade Cabinet, Install, "Download all"
(it brings its font), Switch to it, then type "ready player one" with its particle bursts → "More from addon repos" · zoom in on the Install button · "Or build your own." · end card.
**Shots:** adb via the keyboard's Themes tool over the chat canvas. Card names in that tool are
variant names (Ocean, Snow…), so check the label before each take. Addons: one of the 2
pre-added repos; check what themes it actually lists before claiming a number on screen (the
"hundreds" caption changes to whatever is true).

### Day 10 · #2 · Network activity log · V · 25 s
**Hook:** "This keyboard shows its receipts."
**VO:** Most apps ask you to trust them. This keyboard shows you its receipts. Every request WM
Keyboard's own code makes is logged right on your phone: which server, why it happened, how much
data. Never what you typed. And if new code ever tries to skip the log, the build fails. Settings,
Privacy, Network activity.
**Shots:** adb. Make a few real requests first (a GIF search, a dictionary lookup) so the log has
rows. Open the screen › scrub the chart › tap a row › request sheet, with a zoom in on "what this feature sends".

### Day 11 · #24 · Hundreds of languages · T · 18 s
**Hook:** "One keyboard. Hundreds of languages."
**Beats:** a rapid carousel, about 1.5 s per language. Each cut: a spacebar swipe to the next
layout, then a whole sentence typing itself in that language with its own suggestions. The
language name rides in a chip at the top of the frame:
English "See you tomorrow!" → বাংলা "কাল দেখা হবে!" → हिन्दी "कल मिलते हैं!" →
日本語 "またあした!" → العربية "مرحبا!" → Русский "До завтра!" → Español "hasta luego!" →
한국어 "내일 봐요!" (as captured: each one sent as its own bubble in a group chat)
Last beat: the language search screen scrolling fast through the list → "Switch with one swipe." ·
end card.
**Shots:** adb. Enable the eight layouts beforehand. Latin and Avro text goes through `input text`;
scripts adb can't type (Devanagari, CJK, Arabic, Cyrillic, Hangul) are committed through the
field instead (`DocsShotActivity --es text`, or paste), so the cut shows the layout and the
sentence while the typing is sped up. Check with a dry run which of the two reads better on screen.

### Day 12 · #58 · Offline Whisper voice typing · V · 22 s
**Hook:** "Voice typing without sending your voice anywhere."
**VO:** Voice typing usually sends your voice to a server. Not here. WM Keyboard turns speech into
text right on your phone, and it keeps working in airplane mode. It can even translate what you
say into English. (Filmed with the Offline Whistle engine, so the line names no engine.)
**Shots:** adb for the settings catalog. **You** speak into the phone (or play a clip from the Mac
next to it). Prerequisites: RECORD_AUDIO granted, a model downloaded beforehand. Let the mic chip
go idle before any shot that needs the status bar.

### Day 13 · #45 · Custom sticker packs · V · 22 s
**Hook:** "Turn your camera roll into stickers."
**VO:** Make stickers from your own photos. WM Keyboard cuts out the background, sizes every
sticker for WhatsApp, and keeps them in packs you send straight from the keyboard. Already have
favourites on Signal? Paste the pack link and bring them over.
**Shots:** adb for navigation, **you** for any crop drag. Photos: Pixabay or your own. Signal: a
public signal.art pack link. Send into the chat canvas (it accepts images).

### Day 14 · #26 · One-handed / split / floating · T · 15 s
**Hook:** "Big phone? Small hands?"
**Beats:** one-handed, docked right, flip to left → "One-handed" · split with a gap → "Split" ·
floating panel dragged around, then resized → "Floating" · "Your keyboard, your reach." · end card.
**Shots:** adb for the toggles and the Modes tools; try adb drags on the floating handle, falling
back to **you**. Afterwards, Settings › Layout & size › "Reset the floating panel".

### Day 15 · #9 · Autocorrect undo chip · T · 12 s
**Hook:** "Autocorrect changed your word?"
**Beats (as captured):** friend: "sent you the photos from last night 📸" · type `thx` → autocorrect
makes it "the" → the strip shows ↶ thx · tap → "thx so much!!" · next message "thx again" stays as typed
(the chip only appears for corrections that were a guess, by design) · was: type a slang word or name that gets corrected → the strip shows the original as a chip
→ tap, word back · zoom in on the chip · "One tap. And it remembers." · "Fix it twice and it learns the fix." · end card.
**Shots:** adb `input text`. Find a word that actually gets rewritten on a dry run before filming.

---

## Capture sessions for batch 1

The order is fixed: sessions that need nothing from you run first, the ones that need your hands
or voice run last, so the bulk of the footage is done before you're pulled in.

| Order | Session | Days | Needs you for |
|---|---|---|---|
| 1 | B: languages and emoji | 3, 6, 11 | nothing |
| 2 | D: privacy and text | 7, 10, 15, 4, 1 (shots 1, 3) | nothing |
| 3 | C: looks | 9, 5 (all but the crop) | nothing |
| 4 | E: media and voice | 13, 12 | crop drag (13), speaking (12) |
| 5 | A: fingers | 2, 8, 14, 5 (crop), 1 (airplane shot) | every glide, the pinch, spacebar/floating drags if adb fails, airplane mode with the phone's own recorder |

Before every session: DND on, demo-mode status bar (`play/capture.sh`), screen timeout raised,
`DocsShotActivity mode=chat` as the canvas. Never force-stop the app: that kills wireless adb.
Progress is tracked in `tracker.md`.

---

## Batch 2 (days 16–30)

Drafted 2026-10-18 while the user was away from the laptop; claims checked against `FEATURES.md`.
Not yet approved: the hands-free days are filmed first so footage exists to judge, and any script
can still change.

### Day 16 · #49 · OTP code chip · V · 18 s
**Hook:** "Stop copying codes out of your notifications."
**VO:** A sign-in code arrives. Normally you pull down the shade, copy it, come back, paste. Here
the code is already waiting in the suggestion strip. One tap and you're in. The code is never
saved anywhere, and it expires after three minutes.
**Shots:** adb. Turn on "One-time codes from notifications" (off by default) and grant notification
access if it isn't granted. Post a fake code with `cmd notification post` ("482913 is your
verification code"); the canvas needs a number field (chip is number-fields-only by default).
Zoom in on the chip. Restore the setting afterwards.

### Day 17 · #18 · Hindi phonetic · H · 14 s
**Hook:** "POV: you think in Hindi but type in English letters"
**Beats:** Hindi phonetic layout · `kal milte hain` → कल मिलते हैं · `kya haal hai` → क्या हाल है ·
zoom in on the strip · "Type Hinglish, send Hindi." · end card.
**Shots:** adb `input text` (works for Hindi phonetic). Add the layout for the take, remove after.

### Day 18 · #50 · Snippets / text expansion · T · 15 s
**Hook:** "Typing your address again?"
**Beats:** type `@addr` + space → a full fake address expands · `@date` → today's date ·
backspace once → the trigger comes back ("Backspace undoes it") · "Type less." · end card.
**Shots:** adb. Create two demo snippets in Settings › Snippets with fake data, delete after.

### Day 19 · #19 · Japanese flick · T · 15 s
**Hook:** "How Japan types."
**Beats:** the 12-key kana pad, flicks drawing the 3×3 cross popup · `またあした` building up ·
conversion strip offers 又明日 / また明日 · "Flick keys, with the arms drawn on the face." · end card.
**Shots:** **you** for the flicks (adb swipes may work for single flicks: try first).

### Day 20 · #51 · Translator · T · 15 s
**Hook:** "Reply in a language you don't speak."
**Beats:** friend writes in Spanish · open Translate tool · type "see you at eight, I'll bring
snacks" → Spanish appears live · Insert → send · "63 languages. Without leaving the chat." · end card.
**Shots:** adb.

### Day 21 · #30 · Theme editor colours · V · 20 s
**Hook:** "Make it yours. Every colour."
**VO:** Every colour on this keyboard is yours to change: keys, text, the enter key, even the glide
trail. Pick one seed colour and the whole palette rebuilds around it. The preview on top shows the
real keyboard as you go.
**Shots:** adb. Theme editor on a test theme: tap seed swatches (palette rebuilds), open a key
colour, drag hue, hex field. Delete the test theme after.

### Day 22 · #52 · Dictionary · T · 12 s
**Hook:** "What does that word even mean?"
**Beats:** friend uses "ephemeral" · double-tap/select it in the reply or type it · Dictionary tool
shows definition, IPA, play button, synonym chips · tap a synonym → it looks that up · end card.
**Shots:** adb.

### Day 23 · #8 · Gliding very long words · H · 12 s
**Hook:** "Can it glide 'internationalization'?"
**Beats:** one stroke each: `internationalization`, `incomprehensible`, `responsibilities` ·
"Yes." · end card.
**Shots:** **you** (adb can't glide).

### Day 24 · #20 · Korean Cheonjiin / Naratgeul · T · 15 s
**Hook:** "Korean on 12 keys."
**Beats:** 천지인 pad: ㅣ ㆍ ㅡ strokes folding into vowels, `안녕` building · switch to 나랏글:
획추가 turning 로 into 롭 · "Both Samsung-style pads. Built in." · end card.
**Shots:** adb taps (Korean needs key taps, not `input text`); add the layouts, remove after.

### Day 25 · #32 · Rotating backgrounds · T · 14 s
**Hook:** "A new keyboard every morning."
**Beats:** rotation set to Daily · Shuffle tapped three times, a new photo each time, keys
recolouring to match · "Your keyboard, never the same twice." · end card.
**Shots:** adb. Let the pool fill first (batch 1 shuffle failed while fetching). Rotation off after.

### Day 26 · #53 · Scanner / OCR · H · 14 s
**Hook:** "Typing what's on paper? Don't."
**Beats:** Text scan over a printed page or receipt · words appear as chips · deselect a line ·
Insert → text lands in the chat · "On-device. 100+ languages." · end card.
**Shots:** **you** to point the camera (adb can drive the taps).

### Day 27 · #10 · Personal learning + word card · V · 20 s
**Hook:** "It learns how you actually talk."
**VO:** Type a word the dictionary doesn't know, and WM Keyboard learns it. Hold any suggestion to
see where it came from: every list it's in and its rank. Change the spelling, pin the capitals, or
tell it never to suggest that word again.
**Shots:** adb. Type a made-up name twice so it is learned, then hold its chip → menu → word card.
Delete the test word after.

### Day 28 · #21 · Chinese Pinyin · T · 14 s
**Hook:** "Chinese, from initials."
**Beats:** Pinyin layout · `nihao` → 你好 · jianpin `wm` → 我们 · `zgrm` → 中国人民 ·
"Six Chinese input schemes." · end card.
**Shots:** adb `input text`; add the layout (and its dictionary), remove after.

### Day 29 · #33 · Key shapes · T · 12 s
**Hook:** "Rounded keys are so last year."
**Beats:** carousel through shapes on the live keyboard: pill, squircle, hexagon, scallop, ticket,
leaf, circle, none · "12 key shapes." · end card.
**Shots:** adb in the theme editor's shape picker on a test theme.

### Day 30 · #40 · Emoji suggested while typing · T · 12 s
**Hook:** "Emoji, without opening the emoji panel."
**Beats:** type `happy birthday` → 🎂 in the strip · `alarm clock` → ⏰ · `:tada:` → 🎉 ·
"Type the word, tap the emoji." · end card.
**Shots:** adb `input text`.

## Capture sessions for batch 2

| Order | Session | Days | Needs you for |
|---|---|---|---|
| 1 | F: text and tools | 30, 18, 20, 22, 16, 27 | nothing |
| 2 | G: languages | 17, 28, 24 | nothing (Korean by taps) |
| 3 | H: looks | 21, 29, 25 | nothing |
| 4 | I: hands | 19, 23, 26 | flicks, glides, pointing the camera |
