/*
 * Ogg Opus writer: see ogg_opus.h. One logical stream, three kinds of page:
 * the OpusHead page (beginning of stream), the OpusTags page, then audio pages
 * of up to a second of 20 ms packets each, the last one flagged end of stream
 * with a granule position that trims the padding off the end (RFC 7845 4.4).
 */
#include "ogg_opus.h"

#include <opus.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define FRAMES_PER_SECOND 50   /* 20 ms frames: what Opus is tuned for */
#define PACKETS_PER_PAGE 50    /* one second of audio a page: 27 + 50 bytes of framing */
#define MAX_PACKET 1500        /* above Opus's 1275-byte ceiling for one frame */
#define MAX_SEGMENTS 255
#define MAX_BODY (MAX_SEGMENTS * 255)
#define GRANULE_RATE 48000     /* Ogg Opus counts every position at 48 kHz */
#define STREAM_SERIAL 0x574d4b42u /* "WMKB": one stream a file, so a fixed serial keeps output reproducible */

#define FLAG_BOS 0x02
#define FLAG_EOS 0x04

struct wm_ogg_opus {
    OpusEncoder *encoder;
    int rate;
    int frame;          /* samples a frame at [rate] */
    int scale;          /* GRANULE_RATE / rate */
    int lookahead;      /* encoder delay, samples at [rate] */
    int failed;
    int finished;

    float *pending;
    int pending_count;
    int64_t samples_in;     /* real samples written */
    int64_t samples_coded;  /* samples handed to the encoder, padding included */

    uint32_t sequence;
    unsigned char lacing[MAX_SEGMENTS];
    int segments;
    unsigned char body[MAX_BODY];
    size_t body_len;
    int packets;
    int64_t granule;

    unsigned char *out;
    size_t out_len;
    size_t out_cap;
};

static uint32_t crc_update(uint32_t crc, const unsigned char *p, size_t n) {
    /* Ogg's CRC-32: polynomial 0x04c11db7, MSB first, no reflection, no final xor. */
    while (n--) {
        crc ^= (uint32_t)*p++ << 24;
        for (int k = 0; k < 8; k++) crc = (crc & 0x80000000u) ? (crc << 1) ^ 0x04c11db7u : crc << 1;
    }
    return crc;
}

static void put32(unsigned char *p, uint32_t v) {
    p[0] = (unsigned char)v;
    p[1] = (unsigned char)(v >> 8);
    p[2] = (unsigned char)(v >> 16);
    p[3] = (unsigned char)(v >> 24);
}

static void put64(unsigned char *p, uint64_t v) {
    put32(p, (uint32_t)v);
    put32(p + 4, (uint32_t)(v >> 32));
}

static int reserve(wm_ogg_opus *w, size_t more) {
    if (w->out_len + more <= w->out_cap) return 0;
    size_t cap = w->out_cap ? w->out_cap : 16384;
    while (cap < w->out_len + more) cap *= 2;
    unsigned char *grown = realloc(w->out, cap);
    if (!grown) return -1;
    w->out = grown;
    w->out_cap = cap;
    return 0;
}

static int emit_page(wm_ogg_opus *w, int flags, int64_t granule,
                     const unsigned char *lacing, int segments,
                     const unsigned char *body, size_t body_len) {
    size_t header_len = 27 + (size_t)segments;
    if (reserve(w, header_len + body_len)) return -1;
    unsigned char *page = w->out + w->out_len;
    memcpy(page, "OggS", 4);
    page[4] = 0; /* stream structure version */
    page[5] = (unsigned char)(flags | (w->sequence == 0 ? FLAG_BOS : 0));
    put64(page + 6, (uint64_t)granule);
    put32(page + 14, STREAM_SERIAL);
    put32(page + 18, w->sequence++);
    put32(page + 22, 0); /* the CRC, computed with this field zero */
    page[26] = (unsigned char)segments;
    memcpy(page + 27, lacing, (size_t)segments);
    memcpy(page + header_len, body, body_len);
    put32(page + 22, crc_update(0, page, header_len + body_len));
    w->out_len += header_len + body_len;
    return 0;
}

/* A packet that has a page of its own: the two header packets. */
static int emit_alone(wm_ogg_opus *w, const unsigned char *packet, size_t len) {
    unsigned char lacing[MAX_SEGMENTS];
    int segments = 0;
    size_t left = len;
    while (left >= 255 && segments < MAX_SEGMENTS - 1) {
        lacing[segments++] = 255;
        left -= 255;
    }
    if (left >= 255) return -1;
    lacing[segments++] = (unsigned char)left;
    return emit_page(w, 0, 0, lacing, segments, packet, len);
}

static int flush_audio(wm_ogg_opus *w, int flags) {
    if (w->segments == 0) return 0;
    int status = emit_page(w, flags, w->granule, w->lacing, w->segments, w->body, w->body_len);
    w->segments = 0;
    w->body_len = 0;
    w->packets = 0;
    return status;
}

static int add_packet(wm_ogg_opus *w, const unsigned char *packet, int len, int64_t granule) {
    int needed = len / 255 + 1;
    /* Full before the packet goes in, never after: the last packet always
       waits on an open page, which finish then flags as the end of stream. */
    if (w->segments + needed > MAX_SEGMENTS || w->packets >= PACKETS_PER_PAGE) {
        if (flush_audio(w, 0)) return -1;
    }
    for (int i = 0; i < needed - 1; i++) w->lacing[w->segments++] = 255;
    w->lacing[w->segments++] = (unsigned char)(len % 255);
    memcpy(w->body + w->body_len, packet, (size_t)len);
    w->body_len += (size_t)len;
    w->packets++;
    w->granule = granule;
    return 0;
}

static int encode_pending(wm_ogg_opus *w) {
    unsigned char packet[MAX_PACKET];
    int len = opus_encode_float(w->encoder, w->pending, w->frame, packet, MAX_PACKET);
    if (len < 0) return -1;
    w->samples_coded += w->frame;
    w->pending_count = 0;
    return add_packet(w, packet, len, w->samples_coded * w->scale);
}

static int write_headers(wm_ogg_opus *w) {
    unsigned char head[19];
    memcpy(head, "OpusHead", 8);
    head[8] = 1;  /* version */
    head[9] = 1;  /* channels */
    int pre_skip = w->lookahead * w->scale;
    head[10] = (unsigned char)pre_skip;
    head[11] = (unsigned char)(pre_skip >> 8);
    put32(head + 12, (uint32_t)w->rate); /* the input's rate, for information */
    head[16] = 0; /* output gain, Q7.8 dB */
    head[17] = 0;
    head[18] = 0; /* channel mapping family 0: mono or stereo, no table */
    if (emit_alone(w, head, sizeof head)) return -1;

    const char *vendor = opus_get_version_string();
    size_t vendor_len = strlen(vendor);
    size_t tags_len = 8 + 4 + vendor_len + 4;
    unsigned char *tags = malloc(tags_len);
    if (!tags) return -1;
    memcpy(tags, "OpusTags", 8);
    put32(tags + 8, (uint32_t)vendor_len);
    memcpy(tags + 12, vendor, vendor_len);
    put32(tags + 12 + vendor_len, 0); /* no user comments */
    int status = emit_alone(w, tags, tags_len);
    free(tags);
    return status;
}

wm_ogg_opus *wm_ogg_opus_open(int sample_rate, int bitrate) {
    if (sample_rate != 8000 && sample_rate != 12000 && sample_rate != 16000 &&
        sample_rate != 24000 && sample_rate != 48000) return NULL;
    if (bitrate < 6000 || bitrate > 510000) return NULL;
    wm_ogg_opus *w = calloc(1, sizeof *w);
    if (!w) return NULL;
    w->rate = sample_rate;
    w->frame = sample_rate / FRAMES_PER_SECOND;
    w->scale = GRANULE_RATE / sample_rate;
    w->pending = calloc((size_t)w->frame, sizeof(float));
    int error = OPUS_OK;
    w->encoder = opus_encoder_create(sample_rate, 1, OPUS_APPLICATION_VOIP, &error);
    if (!w->pending || !w->encoder || error != OPUS_OK) {
        wm_ogg_opus_free(w);
        return NULL;
    }
    opus_encoder_ctl(w->encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(w->encoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(w->encoder, OPUS_SET_VBR_CONSTRAINT(0));
    opus_encoder_ctl(w->encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(w->encoder, OPUS_SET_COMPLEXITY(10));
    if (opus_encoder_ctl(w->encoder, OPUS_GET_LOOKAHEAD(&w->lookahead)) != OPUS_OK || write_headers(w)) {
        wm_ogg_opus_free(w);
        return NULL;
    }
    return w;
}

int wm_ogg_opus_write(wm_ogg_opus *w, const float *samples, size_t count) {
    if (w->failed || w->finished) return -1;
    while (count > 0) {
        size_t room = (size_t)(w->frame - w->pending_count);
        size_t take = count < room ? count : room;
        memcpy(w->pending + w->pending_count, samples, take * sizeof(float));
        w->pending_count += (int)take;
        w->samples_in += (int64_t)take;
        samples += take;
        count -= take;
        if (w->pending_count == w->frame && encode_pending(w)) {
            w->failed = 1;
            return -1;
        }
    }
    return 0;
}

int wm_ogg_opus_finish(wm_ogg_opus *w, const unsigned char **out, size_t *len) {
    if (w->failed) return -1;
    if (!w->finished) {
        /* Zeros until the encoder has given back every real sample: the last
           ones are still inside its lookahead. At least one frame, so the
           end-of-stream page always has a packet on it. */
        int64_t target = w->samples_in + w->lookahead;
        do {
            memset(w->pending + w->pending_count, 0, (size_t)(w->frame - w->pending_count) * sizeof(float));
            if (encode_pending(w)) {
                w->failed = 1;
                return -1;
            }
        } while (w->samples_coded < target);
        /* The end trimmed to the real length: a decoder drops what lies past it. */
        w->granule = (int64_t)w->lookahead * w->scale + w->samples_in * w->scale;
        if (flush_audio(w, FLAG_EOS)) {
            w->failed = 1;
            return -1;
        }
        w->finished = 1;
    }
    *out = w->out;
    *len = w->out_len;
    return 0;
}

void wm_ogg_opus_free(wm_ogg_opus *w) {
    if (!w) return;
    if (w->encoder) opus_encoder_destroy(w->encoder);
    free(w->pending);
    free(w->out);
    free(w);
}
