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
    // Detail: the longest gaps, where they were (page clock), longest first.
    const at = gaps.map((g, k) => [g, pts[k], pts[k + 1]]).filter(([g]) => g > 500).sort((a, b) => b[0] - a[0]).slice(0, 10);
    return {
      max_gap_ms: Math.max(...gaps),
      stall_total_ms: gaps.filter((g) => g > 500).reduce((x, y) => x + y, 0),
      stall_presented: presented,
      stalls_at: at.map(([, a, b]) => [round2(a), round2(b)]),
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
  // sessions, or a stalled one).
  //
  // Protocol 8 (#202, s1rtc4): the graded gaps and stalls come from here, not from the page's frame
  // callbacks. On the S1's heavy view a busy main thread ran rVFC 127 times in 147 s while the
  // compositor showed 2198 frames (1866 of them inside the callbacks' "stalls"), and the receiver
  // counted 2 freezes, 0.49 s. Per interval between two samples: nothing decoded by any receiver is
  // a stall for the whole interval; else each receiver's new freezes (libwebrtc's
  // VideoQualityObserver: an inter-frame gap of at least max(3x the mean of the last 30, the mean +
  // 150 ms), counted at its full length) and pauses (the stream inactive) are stalls, less the time
  // with nothing decoded just before, which they cover too. `max_gap_ms`: the longest freeze or
  // pause (a sample with two counts the sum: an upper bound) or run with nothing decoded; without
  // any, every gap was under the freeze rule, and it is that rule's bound for the window's mean
  // frame interval (`max_gap_bound`). Samples come when the page can take them, so a stalled
  // interval is as coarse as the sampling, never shorter than the stall.
  const RX = ["framesReceived", "framesDecoded", "framesDropped", "keyFramesDecoded", "freezeCount", "totalFreezesDuration",
              "pauseCount", "totalPausesDuration", "packetsLost", "nackCount", "pliCount", "jitterBufferDelay",
              "jitterBufferEmittedCount"];
  const EMPTY_MIN_MS = 200;                // an interval with nothing decoded this short is the sampling's
  // A sample's time: when its counts were taken. The inbound reports' own `timestamp` (Gecko sets it
  // on its call thread as it reads them, RTCRtpReceiver.cpp GetTimestampMaker().GetNow(), right
  // after the ask; a busy main thread delays only the answer), on the page's clock from the first
  // sample's ask; the ask time when a sample has none. So a late answer can't count frames or
  // freezes past the time it says (#205's verifier, harness.js's end-of-window sample).
  function sampleTimes(samples) {
    const ts = samples.map((smp) => {
      const v = [];
      for (const { reports } of smp.pcs || []) {
        for (const r of reports || []) {
          if (r.type === "inbound-rtp" && (r.kind || r.mediaType) === "video" && typeof r.timestamp === "number") v.push(r.timestamp);
        }
      }
      return v.length ? Math.max(...v) : null;
    });
    // One sample without a timestamp (an engine that doesn't fill it, or a test's samples): the
    // reports' clock can't be tied to the page's for that sample, so every interval runs on the ask
    // times instead (`clock: "asked"`). Then a late answer can count frames and freezes up to its
    // own snapshot against an interval that ends at the ask: at most the end sample's wait,
    // statsWaitMs (10 s), on the window's last interval. Gecko and Chrome both fill it.
    if (ts.some((x) => x === null)) return { times: samples.map((s) => s.t), clock: "asked" };
    return { times: ts.map((x) => samples[0].t + (x - ts[0])), clock: "stats" };
  }

  function webrtcWindow(samples) {
    if (!samples || !samples.length) return null;
    const total = Object.fromEntries(RX.map((k) => [k, null]));
    const prev = new Map(), pcs = new Set();
    let last = null, noVideo = 0, lastT = null, run = 0, stall = 0, maxGap = 0, gaps = 0;
    const where = [];                      // detail: where the stalls were, on the page's clock (a window each)
    const { times, clock } = sampleTimes(samples);
    samples.forEach((smp0, i) => {
      const smp = { t: times[i], pcs: smp0.pcs };
      let decoded = 0;                     // frames decoded since the sample before, all receivers
      let frozenMs = 0, longest = 0;       // the freezes and pauses that ended since then
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
          // This receiver's new freezes and pauses, in ms (each counter on its own, as above). One
          // outage that a receiver reports as both a freeze and a pause in the same interval counts
          // once, the longer (libwebrtc counts a gap as one or the other, but an engine needn't: #205's
          // verifier gave 4000 ms for one 2 s outage); different receivers add up.
          const moved = [["freezeCount", "totalFreezesDuration"], ["pauseCount", "totalPausesDuration"]].map(([n, d]) => {
            const cnt = typeof r[n] === "number" ? r[n] - (typeof was[n] === "number" && r[n] >= was[n] ? was[n] : 0) : 0;
            const dur = typeof r[d] === "number" ? r[d] - (typeof was[d] === "number" && r[d] >= was[d] ? was[d] : 0) : 0;
            return cnt > 0 && dur > 0 ? { cnt, ms: 1000 * dur } : null;
          }).filter(Boolean);
          if (moved.length) {
            const ms = moved.length === 2 ? Math.max(moved[0].ms, moved[1].ms) : moved[0].ms;
            frozenMs += ms;
            longest = Math.max(longest, ms);               // two in one interval: their sum, an upper bound
            gaps += Math.max(...moved.map((m) => m.cnt));
          }
          prev.set(key, r);
          const transport = (reports || []).find((x) => x.type === "transport" && x.selectedCandidatePairId);
          const pair = (transport && byId.get(transport.selectedCandidatePairId)) ||
            (reports || []).find((x) => x.type === "candidate-pair" && (x.nominated || x.selected) && x.state === "succeeded");
          last = { r, codec: byId.get(r.codecId) || {}, local: pair ? byId.get(pair.localCandidateId) || {} : null };
        }
      }
      if (lastT !== null && decoded === 0 && smp.t - lastT < EMPTY_MIN_MS && frozenMs === 0) {
        noVideo += smp.t - lastT;          // two samples too close to say (the window's end): neither
      } else if (lastT !== null && decoded === 0) {
        noVideo += smp.t - lastT;
        if (!run) where.push([lastT, smp.t]); else where[where.length - 1][1] = smp.t;
        run += smp.t - lastT;
        stall += smp.t - lastT;
        maxGap = Math.max(maxGap, run);
      } else if (lastT !== null) {
        stall += Math.max(0, frozenMs - run);               // the run before is already counted
        if (frozenMs > run) where.push([Math.max(samples[0].t, smp.t - frozenMs - (smp.t - lastT)), smp.t]);
        maxGap = Math.max(maxGap, longest, run);
        run = 0;
      }
      lastT = smp.t;
    });
    if (!last) return { inbound: false, sessions: 0, receivers: 0 };
    const r2 = (k) => (total[k] === null ? null : round2(total[k]));
    const span = times[times.length - 1] - times[0];
    // A receiver that never reported freezes (freezeCount): its stalls and gaps are n/a, not 0.
    if (total.freezeCount === null) {
      maxGap = null;
      stall = null;
    }
    // No freeze, pause or empty interval: every gap was under the freeze rule for the mean interval.
    const mean = total.framesDecoded ? span / total.framesDecoded : null;
    const bound = maxGap === 0 && mean !== null ? Math.max(3 * mean, mean + 150) : null;
    return {
      inbound: true, sessions: pcs.size, receivers: prev.size, no_video_ms: round2(noVideo),
      span_ms: round2(span), clock, stalls_reported: total.freezeCount !== null,
      stall_ms: stall === null ? null : round2(stall),
      max_gap_ms: maxGap === null ? null : bound !== null ? round2(bound) : (maxGap || total.framesDecoded ? round2(maxGap) : null),
      max_gap_bound: bound !== null, gaps,
      stalls_at: where.slice(0, 20).map(([a, b]) => [round2(a), round2(b)]),
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

  // Rubric 2's blocking time (Clarity v2): the main thread's blocking after a view is drawn, from the
  // animation frames between `start` (the first screen drawn) and `end` (usable: the main thread
  // free). Lighthouse's Total Blocking Time adds, over long tasks between FCP and TTI, each task's
  // time past 50 ms; Gecko has no Long Tasks API (bugzil.la/1348405) and no Long Animation Frames.
  // Gecko runs the refresh driver's frames ahead of ordinary tasks, so a frame comes late by the
  // length of the task that was running, not by a queue of short ones: each interval between
  // frames past 50 ms is that much blocking, as a long animation frame's is. The edges count from
  // `start` to the first frame and from the last frame to `end`. {blocking_ms, long_frames,
  // longest_ms}, or null without a span.
  function frameBlocking(times, start, end) {
    if (start === null || end === null || end < start) return null;
    const pts = [start].concat((times || []).filter((t) => t > start && t < end), [end]);
    let blocking = 0, n = 0, longest = 0;
    for (let i = 1; i < pts.length; i++) {
      const gap = pts[i] - pts[i - 1];
      longest = Math.max(longest, gap);
      if (gap > 50) {
        blocking += gap - 50;
        n++;
      }
    }
    return { blocking_ms: round2(blocking), long_frames: n, longest_ms: round2(longest) };
  }

  // A wait of `ms` that ends at the first of a timer and an animation frame past the time (protocol
  // 8, #202: on the S1's heavy view a 30 s wait checked by a timer took 140 s, the timers queued
  // behind the frontend's work while frames, which Gecko runs first, kept coming). The timer still
  // ends it when no frame comes (a hidden page draws none). `env`: now, setTimeout,
  // requestAnimationFrame (the page's own; a test's fakes).
  function pacedSleep(ms, env) {
    return new Promise((resolve) => {
      const end = env.now() + ms;
      let over = false;
      const finish = () => { if (!over) { over = true; resolve(); } };
      env.setTimeout(finish, ms);
      const tick = () => { if (over) return; if (env.now() >= end) finish(); else env.requestAnimationFrame(tick); };
      env.requestAnimationFrame(tick);
    });
  }

  // A stream's frames shown over the window, from the best count the engine gives, and which:
  // - "webrtc": the WebRTC receiver's framesDecoded: frames the decoder handed to the renderer. Its
  //   framesDropped are frames that never came out of the decoder (the frame buffer's drops, and
  //   frames the decoder skipped or lost on a reset: libwebrtc video_stream_buffer_controller.cc and
  //   generic_decoder.cc in GeckoView 159), so they are not in framesDecoded and aren't subtracted;
  //   drops after the decoder are in no count ("Not including frames dropped in the rendering pipe",
  //   Gecko's RTCRtpReceiver.cpp). #202's smoke 4 on the S1: 1090 decoded, 690 dropped on heavy;
  // - "quality": getVideoPlaybackQuality (totalVideoFrames less dropped), the decoder's count. Gecko
  //   fills it only for an element with a media decoder, not for a MediaStream (HTMLVideoElement.cpp,
  //   GetVideoPlaybackQuality), where it reads 0: so 0 while rVFC saw frames is "not reported";
  // - "rvfc": requestVideoFrameCallback's callbacks, which can undercount (one callback for two frames
  //   in one rendering update: Chrome, 2026-11-13, 60 callbacks for 77 frames).
  function shownFrames({ quality, webrtc, callbacks, rvfc }) {
    if (webrtc && typeof webrtc.frames_decoded === "number") {
      return { frames: Math.max(0, webrtc.frames_decoded), source: "webrtc" };
    }
    if (quality && typeof quality.decoded === "number" && !(quality.decoded === 0 && callbacks > 0)) {
      return { frames: Math.max(0, quality.decoded - (quality.dropped || 0)), source: "quality" };
    }
    return rvfc ? { frames: callbacks, source: "rvfc" } : { frames: null, source: "none" };
  }

  const api = { BUDGET_MS, FROZEN_MS, LINE_MAX, frameStats, intervals, nearestRank, band, frameBand, measureMs, consoleLines,
                skyToViolet, usableStep, freeStep, hlsFetches, cardRendered, videoGaps, webrtcWindow, shownFrames,
                pacedSleep, frameBlocking };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWStats = api;
})(this);
