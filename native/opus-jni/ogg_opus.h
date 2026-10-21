/*
 * An Ogg Opus file writer over libopus: mono float samples in, the bytes of a
 * whole .opus/.ogg file out (RFC 7845 framing, RFC 3533 pages). Written by
 * hand rather than with libogg because the keyboard needs one stream per file,
 * written front to back, and nothing else from it.
 *
 * Not thread-safe: one caller per writer.
 */
#ifndef WM_OGG_OPUS_H
#define WM_OGG_OPUS_H

#include <stddef.h>

typedef struct wm_ogg_opus wm_ogg_opus;

/*
 * A writer for audio at [sample_rate] (8, 12, 16, 24 or 48 kHz) encoded at
 * [bitrate] bits per second, VBR, tuned for speech. NULL on a rate Opus does
 * not take, a bitrate outside 6..510 kbps, or no memory.
 */
wm_ogg_opus *wm_ogg_opus_open(int sample_rate, int bitrate);

/* Adds [count] samples in [-1, 1]. Returns 0, or -1 once the writer has failed. */
int wm_ogg_opus_write(wm_ogg_opus *w, const float *samples, size_t count);

/*
 * Ends the stream: pads the last frame, drains the encoder's lookahead and
 * writes the last page. The file is then [*out], [*len] bytes long, owned by
 * the writer until wm_ogg_opus_free. Returns 0, or -1 when the writer failed.
 * Writing after this is an error.
 */
int wm_ogg_opus_finish(wm_ogg_opus *w, const unsigned char **out, size_t *len);

void wm_ogg_opus_free(wm_ogg_opus *w);

#endif
