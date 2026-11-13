/* Frame-time statistics for Clarity, the Glassworks perf test (MIT).
 * The same definitions are in tools/perf/perflib.py (frame_stats); both are tested against
 * tools/perf/test/stats-cases.json, so the page and the runner agree to the millisecond.
 */
(function (root) {
  "use strict";

  const BUDGET_MS = 1000 / 60;
  const FROZEN_MS = 700;

  function nearestRank(sorted, p) {
    if (!sorted.length) return null;
    const i = Math.max(0, Math.ceil(p * sorted.length) - 1);
    return sorted[Math.min(i, sorted.length - 1)];
  }

  function round2(x) {
    return x === null ? null : Math.round(x * 100) / 100;
  }

  // Frame intervals are snapped to whole vsync periods before the percentile, so timer jitter on
  // a frame that made its vsync (16.9 ms on a 60 Hz display) does not count as over budget.
  function frameStats(intervalsMs, vsyncMs) {
    const period = vsyncMs > 0 ? vsyncMs : BUDGET_MS;
    const xs = intervalsMs.filter((x) => Number.isFinite(x) && x > 0);
    const n = xs.length;
    if (!n) return { n: 0 };
    const snapped = xs.map((x) => Math.max(1, Math.round(x / period)) * period);
    const raw = xs.slice().sort((a, b) => a - b);
    const q = snapped.slice().sort((a, b) => a - b);
    const sum = xs.reduce((a, b) => a + b, 0);
    const over = snapped.filter((x) => x > BUDGET_MS + 0.5).length;
    const frozen = xs.filter((x) => x > FROZEN_MS).length;
    return {
      n,
      median_ms: round2(nearestRank(raw, 0.5)),
      p95_ms: round2(nearestRank(q, 0.95)),
      p95_raw_ms: round2(nearestRank(raw, 0.95)),
      max_ms: round2(raw[n - 1]),
      fps: round2(1000 * n / sum),
      jank_pct: round2(100 * over / n),
      frozen_pct: round2(100 * frozen / n),
    };
  }

  function band(value, rule) {
    if (value === null || value === undefined) return null;
    if ("poor_at" in rule && value >= rule.poor_at) return "poor";
    if ("poor_above" in rule && value > rule.poor_above) return "poor";
    return value <= rule.good_max ? "good" : "needs-improvement";
  }

  const ORDER = { good: 0, "needs-improvement": 1, poor: 2 };

  // The same rule as grade.frame_band: the worst band of p95, jank and frozen frames.
  function frameBand(stats, rubric) {
    if (!stats || !stats.n) return null;
    const m = rubric.metrics;
    const bands = [band(stats.p95_ms, m.frame_p95_ms), band(stats.jank_pct, m.frame_jank_pct), band(stats.frozen_pct, m.frame_frozen_pct)];
    return bands.reduce((a, b) => (ORDER[b] > ORDER[a] ? b : a));
  }

  // SurfaceFlinger keeps the last 127 presented frames, and the runner reads them after the window
  // ends. 100 refreshes leave 27 slots (450 ms at 60 Hz) for that read: 1.67 s at 60 Hz, 0.83 s at 120 Hz.
  function measureMs(vsyncMs) {
    return Math.floor(100 * (vsyncMs > 0 ? vsyncMs : BUDGET_MS));
  }

  // A page's console line reaches logcat through the content process, which keeps 1023 bytes of it
  // (the S1, 2026-11-12: smoke7's 4 s updates steps were cut at 1023 bytes and didn't parse). Lines
  // over LINE_MAX bytes of UTF-8 are split into numbered parts the runner joins (perflib.PageLines):
  // "TAG+ key k/n chunk". The split counts bytes, as logcat does, and never cuts a character; no part
  // starts or ends with whitespace, which logcat may trim.
  const LINE_MAX = 900;
  const utf8 = (s) => new TextEncoder().encode(s).length;
  function consoleLines(tag, key, text) {
    if (utf8(text) <= LINE_MAX) return [tag + " " + text];
    const chars = Array.from(text);             // whole code points
    const sizes = chars.map(utf8);
    const parts = [];
    let i = 0;
    while (i < chars.length) {
      let j = i, bytes = 0;
      while (j < chars.length && bytes + sizes[j] <= LINE_MAX) bytes += sizes[j++];
      while (j < chars.length && j > i + 1 && (/\s/.test(chars[j - 1]) || /\s/.test(chars[j]))) j--;
      parts.push(chars.slice(i, j).join(""));
      i = j;
    }
    return parts.map((p, k) => `${tag}+ ${key} ${k}/${parts.length} ${p}`);
  }

  // A hue between the brand's sky (#38bdf8, 198.4°) and violet (#a78bfa, 255.1°) for a random number
  // in [0, 1): generated colours never land on ember (Kiln's) or on green, amber or rose, which mean
  // status only.
  function skyToViolet(r) {
    return Math.round((198.4 + r * (255.1 - 198.4)) * 10) / 10;
  }

  function intervals(timestamps) {
    const out = [];
    for (let i = 1; i < timestamps.length; i++) out.push(timestamps[i] - timestamps[i - 1]);
    return out;
  }

  // Clarity's "usable" (docs/perf.md, "How Clarity measures HA's frontend"), one animation frame at a
  // time. f: launch (HA's launch screen is still up), viewIndex and wantIndex (the view hui-root shows,
  // the one asked for), head (the view's first headCards configured cards: { rendered }), onScreen
  // (every card on the screen: { rendered, top }). prev: { last, stable }. Done when, for two frames
  // in a row, the launch screen is gone, it is the asked view, every head card and every card on the
  // screen is drawn, and the layout hasn't moved.
  function usableStep(f, prev, headCards) {
    const no = { last: "", stable: 0, done: false };
    if (f.launch || f.viewIndex !== f.wantIndex) return no;
    const head = f.head || [];
    if (head.length < headCards || !head.slice(0, headCards).every((c) => c.rendered)) return no;
    if (!f.onScreen.length || !f.onScreen.every((c) => c.rendered)) return no;
    const key = f.onScreen.length + ":" + f.onScreen.map((c) => Math.round(c.top)).join(",");
    const stable = key === prev.last ? prev.stable + 1 : 0;
    return { last: key, stable, done: stable >= 1 };
  }

  // The main thread free: every task posted back to back ran within 50 ms (RAIL's input budget) for
  // windowMs. One probe: posted and ran (ms); start is when the current free stretch began, or null.
  function freeStep(posted, ran, start, windowMs) {
    if (ran - posted > 50) return { start: null, done: false };
    const s = start === null ? ran : start;
    return { start: s, done: ran - s >= windowMs };
  }

  // A card (hui-card) is drawn when its ha-card is laid out. A card made of cards (HA's grid, the heavy
  // view's camera sub-streams) has no ha-card of its own: it is drawn when it is laid out and every
  // card in it is. Works on any DOM-like object (firstElementChild, shadowRoot, querySelector(All),
  // offsetHeight), so node can test it.
  function cardRendered(card) {
    const inner = card && card.firstElementChild;
    const root = inner && inner.shadowRoot;
    if (!root) return false;
    const nested = Array.from(root.querySelectorAll("hui-card"));
    if (nested.length) return inner.offsetHeight > 0 && nested.every(cardRendered);
    const ha = root.querySelector("ha-card");
    return !!(ha && ha.offsetHeight > 0);
  }

  // A camera stream's gaps over a window [t0, t1]: the presented frames' times (requestVideoFrameCallback,
  // or timeupdate without it) with the window's edges, the longest gap and the gaps over 500 ms added
  // up (stalls). `pfs`, parallel to `times`, are rVFC's presentedFrames: a cross-check, since a busy
  // main thread delays the callbacks while the compositor goes on showing frames. `stall_presented`
  // is how many frames were presented inside those stalls (between two callbacks; the edges can't
  // say): many means the stall was the callbacks', not the stream's. Detail only; null without pfs.
  function videoGaps(times, pfs, t0, t1) {
    const inWin = [];
    times.forEach((t, i) => { if (t >= t0 && t <= t1) inWin.push({ t, pf: pfs ? pfs[i] : null }); });
    const pts = [t0].concat(inWin.map((x) => x.t), [t1]);
    const gaps = pts.slice(1).map((t, k) => t - pts[k]);
    let presented = pfs ? 0 : null;
    if (pfs) {
      for (let k = 1; k < inWin.length; k++) {
        const a = inWin[k - 1], b = inWin[k];
        if (b.t - a.t > 500 && a.pf != null && b.pf != null) presented += Math.max(0, b.pf - a.pf - 1);
      }
    }
    return {
      max_gap_ms: Math.max(...gaps),
      stall_total_ms: gaps.filter((g) => g > 500).reduce((x, y) => x + y, 0),
      stall_presented: presented,
    };
  }

  // The camera streams' HLS fetches in a window, from Resource Timing entries (hls.js's fetches of
  // /api/hls/<token>/...). Where a late segment comes from: `queued` is fetch start to request start
  // (waiting for a connection: HTTP/1.1 allows a few per host), `wait` request start to the first
  // byte (the server and the link), `total` start to the last byte. `arrival_gap` is the longest
  // time between two segments of one stream arriving; segments are 1 s, so much more is a stall.
  function hlsFetches(entries) {
    const kinds = { segment: [], playlist: [] };
    const byStream = new Map();
    for (const e of entries) {
      const m = /\/api\/hls\/([^/]+)\/(?:(segment)\/\d+\.m4s|(?:master_)?playlist\.m3u8)/.exec(e.name || "");
      if (!m) continue;
      (m[2] ? kinds.segment : kinds.playlist).push(e);
      if (m[2]) {
        if (!byStream.has(m[1])) byStream.set(m[1], []);
        byStream.get(m[1]).push(e.responseEnd);
      }
    }
    const summary = (xs, f) => {
      const v = xs.map(f).filter((x) => x !== null && isFinite(x) && x >= 0).sort((a, b) => a - b);
      return v.length ? { median: round2(nearestRank(v, 0.5)), p95: round2(nearestRank(v, 0.95)), max: round2(v[v.length - 1]) } : null;
    };
    const out = {};
    for (const [k, xs] of Object.entries(kinds)) {
      out[k] = {
        n: xs.length,
        queued_ms: summary(xs, (e) => (e.requestStart > 0 ? e.requestStart - e.fetchStart : null)),
        wait_ms: summary(xs, (e) => (e.requestStart > 0 && e.responseStart > 0 ? e.responseStart - e.requestStart : null)),
        total_ms: summary(xs, (e) => e.responseEnd - e.startTime),
      };
    }
    out.segment.kb = Math.round(kinds.segment.reduce((a, e) => a + (e.transferSize || e.encodedBodySize || 0), 0) / 1024);
    let gap = null;
    for (const ends of byStream.values()) {
      const s = ends.slice().sort((a, b) => a - b);
      for (let i = 1; i < s.length; i++) gap = Math.max(gap === null ? 0 : gap, s[i] - s[i - 1]);
    }
    out.segment.arrival_gap_max_ms = gap === null ? null : round2(gap);
    out.streams = byStream.size;
    return out;
  }

  // A WebRTC camera over a window, from getStats() samples of every connection that carried its
  // video (HA's player can make a new one, or renegotiate the same one, mid-window: #202, the S1's
  // heavy view). `samples`: [{t, pcs: [{pc, reports}]}] in time order, the first at the window's
  // start. Each inbound video receiver (its connection, id and SSRC) counts its own increments only:
  // one first seen after the window's start counts from 0, a counter that goes back (a receiver
  // reset) counts on from 0, and two receivers are never subtracted, so no count is negative.
  // `no_video_ms`: the time between samples in which no receiver decoded a frame (between
  // sessions, or a stalled one); the graded stalls come from the video element (videoGaps).
  const RX = ["framesReceived", "framesDecoded", "framesDropped", "keyFramesDecoded", "freezeCount", "totalFreezesDuration",
              "pauseCount", "totalPausesDuration", "packetsLost", "nackCount", "pliCount", "jitterBufferDelay",
              "jitterBufferEmittedCount"];
  function webrtcWindow(samples) {
    if (!samples || !samples.length) return null;
    const total = Object.fromEntries(RX.map((k) => [k, null]));
    const prev = new Map(), pcs = new Set();
    let last = null, noVideo = 0, lastT = null;
    samples.forEach((smp, i) => {
      let decoded = 0;                     // frames decoded since the sample before, all receivers
      for (const { pc, reports } of smp.pcs || []) {
        const byId = new Map((reports || []).map((r) => [r.id, r]));
        for (const r of reports || []) {
          if (r.type !== "inbound-rtp" || (r.kind || r.mediaType) !== "video") continue;
          const key = `${pc}|${r.id}|${r.ssrc}`;
          pcs.add(pc);
          const was = prev.get(key) || (i === 0 ? r : {});
          for (const k of RX) {
            if (typeof r[k] !== "number") continue;
            const before = typeof was[k] === "number" ? was[k] : 0;
            const add = r[k] >= before ? r[k] - before : r[k];       // a reset counts on from 0
            total[k] = (total[k] || 0) + add;
            if (k === "framesDecoded") decoded += add;
          }
          prev.set(key, r);
          const transport = (reports || []).find((x) => x.type === "transport" && x.selectedCandidatePairId);
          const pair = (transport && byId.get(transport.selectedCandidatePairId)) ||
            (reports || []).find((x) => x.type === "candidate-pair" && (x.nominated || x.selected) && x.state === "succeeded");
          last = { r, codec: byId.get(r.codecId) || {}, local: pair ? byId.get(pair.localCandidateId) || {} : null };
        }
      }
      if (lastT !== null && decoded === 0) noVideo += smp.t - lastT;
      lastT = smp.t;
    });
    if (!last) return { inbound: false, sessions: 0, receivers: 0 };
    const r2 = (k) => (total[k] === null ? null : round2(total[k]));
    return {
      inbound: true, sessions: pcs.size, receivers: prev.size, no_video_ms: round2(noVideo),
      frames_received: r2("framesReceived"), frames_decoded: r2("framesDecoded"), frames_dropped: r2("framesDropped"),
      key_frames: r2("keyFramesDecoded"), freezes: r2("freezeCount"), freeze_s: r2("totalFreezesDuration"),
      pauses: r2("pauseCount"), pause_s: r2("totalPausesDuration"), packets_lost: r2("packetsLost"),
      nacks: r2("nackCount"), plis: r2("pliCount"),
      jitter_buffer_ms: total.jitterBufferDelay !== null && total.jitterBufferEmittedCount
        ? round2((1000 * total.jitterBufferDelay) / total.jitterBufferEmittedCount) : null,
      decoder: last.r.decoderImplementation || null,
      power_efficient: typeof last.r.powerEfficientDecoder === "boolean" ? last.r.powerEfficientDecoder : null,
      codec: last.codec.mimeType || null, fmtp: last.codec.sdpFmtpLine || null,
      candidate: last.local ? { local: last.local.candidateType || null, relay: last.local.relayProtocol || null,
                                protocol: last.local.protocol || null } : null,
    };
  }

  // A stream's frames shown over the window, from the best count the engine gives, and which:
  // - "webrtc": the WebRTC receiver's own counts (getStats framesDecoded less framesDropped);
  // - "quality": getVideoPlaybackQuality (totalVideoFrames less dropped), the decoder's count. Gecko
  //   fills it only for an element with a media decoder, not for a MediaStream (HTMLVideoElement.cpp,
  //   GetVideoPlaybackQuality), where it reads 0: so 0 while rVFC saw frames is "not reported";
  // - "rvfc": requestVideoFrameCallback's callbacks, which can undercount (one callback for two frames
  //   in one rendering update: Chrome, 2026-11-13, 60 callbacks for 77 frames).
  function shownFrames({ quality, webrtc, callbacks, rvfc }) {
    if (webrtc && typeof webrtc.frames_decoded === "number") {
      return { frames: Math.max(0, webrtc.frames_decoded - (webrtc.frames_dropped || 0)), source: "webrtc" };
    }
    if (quality && typeof quality.decoded === "number" && !(quality.decoded === 0 && callbacks > 0)) {
      return { frames: Math.max(0, quality.decoded - (quality.dropped || 0)), source: "quality" };
    }
    return rvfc ? { frames: callbacks, source: "rvfc" } : { frames: null, source: "none" };
  }

  const api = { BUDGET_MS, FROZEN_MS, LINE_MAX, frameStats, intervals, nearestRank, band, frameBand, measureMs, consoleLines,
                skyToViolet, usableStep, freeStep, hlsFetches, cardRendered, videoGaps, webrtcWindow, shownFrames };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWStats = api;
})(this);
