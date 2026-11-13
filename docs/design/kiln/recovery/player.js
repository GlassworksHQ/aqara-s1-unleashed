/*
 * Kiln recovery-mode player (design prototype).
 *
 * Draws a frame's device face down at the brand's 30° stack angle and animates the steps
 * that put it in recovery mode, from data alone (see README.md, "The recovery section").
 * No per-device art: every frame gets the same moves and transitions.
 *
 *   const p = KilnRecovery.mount(svgElement, labelsElement, frameData);
 *   p.draw(seconds)   // render one moment
 *   p.play()          // loop; respects prefers-reduced-motion
 *
 * Moves: screws, cover, hold, plug. Between them the player adds its own transitions:
 * the light passes over the board once the cover is off, a spotlight closes on the button,
 * then opens to the port and a guide line shows where the cable goes.
 */
(function () {
  const C = Math.cos(Math.PI / 6);
  const SN = 0.5;
  const VB = { w: 640, h: 400 };
  const LABELS = { screws: "Take out the screws", cover: "Remove the back cover", hold: "Hold the button", plug: "Plug in the USB cable" };

  const clamp = (v) => Math.max(0, Math.min(1, v));
  const seg = (t, a, b) => (b <= a ? (t >= b ? 1 : 0) : clamp((t - a) / (b - a)));
  const ease = (x) => x * x * (3 - 2 * x);
  const outBack = (x) => 1 + 2.5 * Math.pow(x - 1, 3) + 1.5 * Math.pow(x - 1, 2);
  const lerp = (a, b, k) => a + (b - a) * k;

  /* The timeline, built from the frame's steps. Each move gets a window; transitions are folded in. */
  function timeline(f) {
    const steps = f.steps;
    const tl = { beats: [] };
    let t = 0.3;
    for (const step of steps) {
      const start = t;
      if (step === "screws") {
        const n = (f.cover && f.cover.screws.length) || 0;
        tl.screws = { start: t, each: 0.32, dur: 0.6 };
        t += n * 0.32 + 0.6;
      } else if (step === "cover") {
        tl.cover = { start: t, end: t + 1.6 };
        tl.reveal = { start: t + 1.4, end: t + 2.2 }; // the light passes over the board
        t += 1.8;
      } else if (step === "hold") {
        tl.focus = { start: t, end: t + 0.8 }; // spotlight closes on the button
        tl.hold = { start: t + 0.6 };
        t += 2.6;
      } else if (step === "plug") {
        tl.toPort = { start: t, end: t + 0.8 }; // spotlight opens to the port, guide draws
        tl.plug = { start: t + 0.8, end: t + 1.5 };
        tl.click = { start: t + 1.45, end: t + 2.0 };
        t += 1.5;
        tl.ready = { start: t + Math.min(2, (f.ready && f.ready.seconds) || 2) * 0.4 + 0.2 };
        t += 2.3;
      }
      tl.beats.push([start, t]);
    }
    if (tl.hold) tl.hold.end = tl.ready ? tl.ready.start : t;
    tl.out = t;
    tl.T = t + 0.6;
    return tl;
  }

  function mount(svg, labelsEl, f) {
    const W = f.body.w, H = f.body.h;
    const k = Math.min(520 / W, 300 / H); // plate units per mm
    const s = 0.78;
    const u = (mm) => mm * k;
    const Wu = u(W), Hu = u(H);
    const depth = f.body.thickness * k * s * 0.4;
    // centre the projected plate in the view, leaving headroom for the cover and the cable
    const proj0 = (x, y) => [C * s * (x - y), SN * s * (x + y)];
    const cs = [proj0(0, 0), proj0(Wu, 0), proj0(Wu, Hu), proj0(0, Hu)];
    const xs = cs.map((c) => c[0]), ys = cs.map((c) => c[1]);
    const OX = VB.w / 2 - (Math.min(...xs) + Math.max(...xs)) / 2;
    const OY = VB.h / 2 + 14 - (Math.min(...ys) + Math.max(...ys) + depth) / 2;
    const P = (x, y) => [OX + C * s * (x - y), OY + SN * s * (x + y)];
    const M = `matrix(${C * s} ${SN * s} ${-C * s} ${SN * s} ${OX} ${OY})`;
    const R = u(f.body.radius || 3);

    const BTN = f.button ? f.button.at.map(u) : null;
    const SCREWS = ((f.cover && f.cover.screws) || []).map((p) => p.map(u));

    // the port: a point on an edge of the top surface, its outward direction, and whether that side face is visible
    let PORT = null;
    if (f.port) {
      const a = u(f.port.at);
      const e = f.port.edge;
      const edge = { top: [a, 0, -30, false], bottom: [a, Hu, 150, true], left: [0, a, -150, false], right: [Wu, a, 30, true] }[e];
      const [x, y, angle, visible] = edge;
      const [px, py] = P(x, y);
      PORT = { top: [px, py], at: [px, py + depth / 2], angle, visible };
    }

    const rect = (r, extra) => `<rect x="${u(r.at[0])}" y="${u(r.at[1])}" width="${u(r.size[0])}" height="${u(r.size[1])}" rx="${u(1)}" ${extra}/>`;
    const board = (f.board || []).map((b) => {
      if (b.kind === "window") return `<circle cx="${u(b.at[0])}" cy="${u(b.at[1])}" r="${u(b.r)}" fill="#050506" stroke="#f4f4f5" stroke-opacity=".14"/>`;
      if (b.kind === "shield") return rect(b, `fill="#f4f4f5" fill-opacity=".1" stroke="#f4f4f5" stroke-opacity=".15"`);
      if (b.kind === "speaker") return rect(b, `fill="#050506" stroke="#f4f4f5" stroke-opacity=".1"`);
      return rect(b, `fill="#f4f4f5" fill-opacity=".08"`);
    }).join("");
    const coverArt = ((f.cover && f.cover.features) || []).map((c) => {
      if (c.kind === "ribs") return rect(c, `fill="url(#kr-rib)"`);
      if (c.kind === "plate") return rect(c, `fill="#262932" stroke="rgba(244,244,245,.1)"`);
      if (c.kind === "hole") return `<ellipse cx="${u(c.at[0])}" cy="${u(c.at[1])}" rx="${u(c.size[0]) / 2}" ry="${u(c.size[1]) / 2}" fill="#1e2028" stroke="rgba(244,244,245,.1)"/>`;
      if (c.kind === "clip") return rect(c, `fill="#0b0c10"`);
      return "";
    }).join("");

    const plug = PORT
      ? `<g transform="translate(${PORT.at[0]} ${PORT.at[1]}) rotate(${PORT.angle})"><clipPath id="kr-out"><rect x="0" y="-60" width="400" height="120"/></clipPath>
         <path id="kr-guide" d="M8 0H8" stroke="#fdba74" stroke-opacity=".55" stroke-width="1" stroke-dasharray="4 4" fill="none"/>
         <g clip-path="url(#kr-out)"><g id="kr-plug">
           <path d="M42 0 C 87 0, 112 26, 182 40" stroke="#2a2d35" stroke-width="9" fill="none" stroke-linecap="round"/>
           <path d="M30 -4.5 L42 -3.6 L42 3.6 L30 4.5 Z" fill="#30333c"/>
           <rect x="-10" y="-3.2" width="11" height="6.4" rx="1.6" fill="#c8cbd2"/>
           <rect x="0" y="-6.5" width="31" height="13" rx="3.5" fill="url(#kr-plugG)" stroke="rgba(244,244,245,.25)" stroke-width=".6"/>
         </g></g></g>`
      : "";

    const [bx, by] = BTN ? P(...BTN) : [VB.w / 2, VB.h / 2];
    svg.setAttribute("viewBox", `0 0 ${VB.w} ${VB.h}`);
    svg.innerHTML = `
<defs>
  <linearGradient id="kr-ember" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fdba74"/><stop offset="1" stop-color="#f43f5e"/></linearGradient>
  <linearGradient id="kr-case" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2a2d36"/><stop offset="1" stop-color="#1a1c23"/></linearGradient>
  <pattern id="kr-rib" width="5" height="10" patternUnits="userSpaceOnUse"><rect width="5" height="10" fill="#22252d"/><rect width="1.6" height="10" fill="#2c2f38"/></pattern>
  <linearGradient id="kr-plugG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#6b6f7a"/><stop offset=".5" stop-color="#3d414b"/><stop offset="1" stop-color="#2a2d35"/></linearGradient>
  <linearGradient id="kr-sweepG" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#e4e4e7" stop-opacity="0"/><stop offset=".5" stop-color="#e4e4e7" stop-opacity=".09"/><stop offset="1" stop-color="#e4e4e7" stop-opacity="0"/></linearGradient>
  <radialGradient id="kr-spot" gradientUnits="userSpaceOnUse" cx="${bx}" cy="${by}" r="80"><stop offset=".45" stop-color="#0a0b0f" stop-opacity="0"/><stop offset="1" stop-color="#0a0b0f" stop-opacity=".78"/></radialGradient>
  <radialGradient id="kr-warm" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#f43f5e" stop-opacity=".14"/><stop offset="1" stop-color="#f43f5e" stop-opacity="0"/></radialGradient>
  <clipPath id="kr-board"><rect x="${u(3)}" y="${u(3)}" width="${Wu - u(6)}" height="${Hu - u(6)}" rx="${R * 0.6}"/></clipPath>
</defs>
<rect width="${VB.w}" height="${VB.h}" fill="#0a0b0f"/>
<ellipse cx="${VB.w / 2}" cy="${VB.h * 0.58}" rx="300" ry="170" fill="url(#kr-warm)"/>
<g id="kr-all"><g id="kr-zoom">
  ${PORT && !PORT.visible ? plug : ""}
  <g transform="translate(0 ${depth})"><g transform="${M}"><rect width="${Wu}" height="${Hu}" rx="${R}" fill="#101116" stroke="rgba(244,244,245,.16)"/></g></g>
  <g transform="${M}">
    <rect width="${Wu}" height="${Hu}" rx="${R}" fill="url(#kr-case)" stroke="rgba(244,244,245,.22)"/>
    <rect x="${u(3)}" y="${u(3)}" width="${Wu - u(6)}" height="${Hu - u(6)}" rx="${R * 0.6}" fill="#0c0d11" stroke="rgba(244,244,245,.08)"/>
    <g clip-path="url(#kr-board)">${board}<rect id="kr-sweep" x="-200" y="-60" width="160" height="${Hu + 120}" fill="url(#kr-sweepG)" transform="rotate(20 ${Wu / 2} ${Hu / 2})"/></g>
    ${BTN ? `${[0, 1, 2].map((i) => `<circle id="kr-r${i}" cx="${BTN[0]}" cy="${BTN[1]}" r="10" fill="none" stroke="#f43f5e" stroke-width="2"/>`).join("")}
    <rect x="${BTN[0] - 6}" y="${BTN[1] - 6}" width="12" height="12" rx="2" fill="#9a9ca3"/><circle id="kr-btn" cx="${BTN[0]}" cy="${BTN[1]}" r="3.6" fill="#2b2c33"/>` : ""}
  </g>
  ${PORT ? `<ellipse id="kr-portGlow" cx="${PORT.top[0]}" cy="${PORT.top[1]}" rx="16" ry="9" fill="none" stroke="#f43f5e" stroke-width="1.6"/>
  <ellipse id="kr-click" cx="${PORT.top[0]}" cy="${PORT.top[1]}" rx="9" ry="5" fill="none" stroke="#fdba74" stroke-width="1.5"/>` : ""}
  ${PORT && PORT.visible ? plug : ""}
  ${f.cover ? `<g id="kr-cover"><g transform="${M}">
    <rect width="${Wu}" height="${Hu}" rx="${R}" fill="url(#kr-case)" stroke="rgba(244,244,245,.28)"/>${coverArt}
    ${SCREWS.map(([x, y]) => `<circle class="kr-halo" cx="${x}" cy="${y}" r="12" fill="none" stroke="#f43f5e" stroke-width="1.5"/>`).join("")}
  </g></g>
  <g>${SCREWS.map(([x, y], i) => { const [X, Y] = P(x, y); return `<g id="kr-s${i}"><ellipse cx="${X}" cy="${Y}" rx="6.5" ry="4" fill="#c6c9cf" stroke="#8d9199"/><path id="kr-slot${i}" d="M${X - 4} ${Y}H${X + 4}" stroke="#5f636b" stroke-width="1.4"/></g>`; }).join("")}</g>` : ""}
  <rect id="kr-spotRect" width="${VB.w}" height="${VB.h}" fill="url(#kr-spot)"/>
</g>
<g id="kr-hold" opacity="0"><rect x="0" y="-16" width="132" height="32" rx="16" fill="rgba(10,11,15,.92)" stroke="rgba(253,186,116,.45)"/><circle cx="16" cy="0" r="4" fill="#fdba74"/><text x="28" y="4.5" font-family="Inter, sans-serif" font-size="12.5" fill="#fed7aa">Keep holding</text></g>
<g id="kr-ready" opacity="0"><rect x="0" y="-16" width="${36 + 6.6 * ((f.ready && f.ready.says) || "Ready").length}" height="32" rx="16" fill="rgba(10,11,15,.92)" stroke="rgba(52,211,153,.5)"/><circle cx="16" cy="0" r="4" fill="#34d399"/><text x="28" y="4.5" font-family="Inter, sans-serif" font-size="12.5" fill="#d1fae5">${(f.ready && f.ready.says) || "Ready."}</text></g>
</g>`;

    const tl = timeline(f);
    if (labelsEl) labelsEl.innerHTML = f.steps.map((st, i) => `<span>${i + 1} · ${(f.labels && f.labels[st]) || LABELS[st] || st}</span>`).join("");
    const beats = labelsEl ? [...labelsEl.children] : [];
    const $ = (id) => svg.querySelector("#kr-" + id);
    const set = (id, a, v) => { const el = $(id); if (el) el.setAttribute(a, v); };
    const MID = PORT ? [(bx + PORT.top[0]) / 2, (by + PORT.top[1]) / 2] : [bx, by];
    const never = { start: 1e9, end: 1e9 };

    function draw(t) {
      const sc = tl.screws;
      SCREWS.forEach(([x, y], i) => {
        const kk = sc ? ease(seg(t, sc.start + i * sc.each, sc.start + i * sc.each + sc.dur)) : 1;
        const [X, Y] = P(x, y);
        set("s" + i, "transform", `translate(0 ${-16 * kk})`);
        set("s" + i, "opacity", (1 - kk).toFixed(3));
        set("slot" + i, "transform", `rotate(${-540 * kk} ${X} ${Y})`);
      });
      const coverStart = tl.cover ? tl.cover.start : 0;
      svg.querySelectorAll(".kr-halo").forEach((h) => h.setAttribute("opacity", sc && t < coverStart ? 0.8 : 0));
      if (tl.cover) {
        const c = ease(seg(t, tl.cover.start, tl.cover.end));
        set("cover", "transform", `translate(${40 * c} ${-110 * c})`);
        set("cover", "opacity", (1 - c).toFixed(3));
      }
      const rv = tl.reveal || never;
      set("sweep", "x", lerp(-200, Wu + 40, ease(seg(t, rv.start, rv.end))).toFixed(1));
      const fo = tl.focus || never, tp = tl.toPort || never;
      const close = ease(seg(t, fo.start, fo.end)), open = ease(seg(t, tp.start, tp.end)), out = ease(seg(t, tl.out - 0.8, tl.out));
      const fx = lerp(bx, MID[0], open), fy = lerp(by, MID[1], open);
      set("spot", "cx", fx.toFixed(1)); set("spot", "cy", fy.toFixed(1));
      const spot = svg.querySelector("#kr-spot");
      spot.setAttribute("cx", fx.toFixed(1)); spot.setAttribute("cy", fy.toFixed(1));
      spot.setAttribute("r", lerp(lerp(700, 90, close), 190, open).toFixed(1));
      set("spotRect", "opacity", Math.min(close, 1 - out).toFixed(3));
      const z = 1 + 0.16 * close - 0.06 * open - 0.1 * out;
      set("zoom", "transform", `translate(${fx} ${fy}) scale(${z.toFixed(4)}) translate(${-fx} ${-fy})`);
      const hd = tl.hold || never;
      const holding = t > hd.start && t < hd.end;
      [0, 1, 2].forEach((i) => {
        const ph = (((t - hd.start) / 1.5) + i / 3) % 1;
        set("r" + i, "r", (8 + 30 * ph).toFixed(1));
        set("r" + i, "opacity", holding ? (0.9 * (1 - ph)).toFixed(3) : 0);
      });
      set("btn", "fill", holding ? "#f43f5e" : "#2b2c33");
      if (PORT) {
        const g = ease(seg(t, tp.start + 0.1, tp.end));
        set("guide", "d", `M8 0H${(8 + 122 * g).toFixed(1)}`);
        const pl = tl.plug;
        set("guide", "opacity", t > tp.start && t < pl.start + 0.5 ? 1 - seg(t, pl.start + 0.2, pl.start + 0.5) : 0);
        set("portGlow", "opacity", t > tp.start + 0.1 && t < pl.start + 0.55 ? (0.35 + 0.6 * (Math.sin((t - tp.start) * 7) + 1) / 2).toFixed(3) : 0);
        const pp = seg(t, pl.start, pl.end);
        const d = t < pl.start - 0.3 ? 170 : t < pl.start ? lerp(170, 120, ease(seg(t, pl.start - 0.3, pl.start))) : 120 * (1 - outBack(pp));
        set("plug", "transform", `translate(${d.toFixed(2)} 0)`);
        set("plug", "opacity", (t > pl.start - 0.3 && t < tl.T ? ease(seg(t, pl.start - 0.3, pl.start + 0.3)) * (1 - out) : 0).toFixed(3));
        const kc = seg(t, tl.click.start, tl.click.end);
        const [cx, cy] = PORT.top, scl = 1 + 2.4 * kc;
        set("click", "transform", `translate(${cx} ${cy}) scale(${scl}) translate(${-cx} ${-cy})`);
        set("click", "opacity", t > tl.click.start && t < tl.click.end ? (1 - kc).toFixed(3) : 0);
      }
      const lx = fx + (bx - fx) * z + 46, ly = fy + (by - fy) * z - 56;
      set("hold", "transform", `translate(${lx} ${ly})`);
      set("ready", "transform", `translate(${lx} ${ly})`);
      set("hold", "opacity", holding ? 1 : 0);
      set("ready", "opacity", tl.ready && t >= tl.ready.start && t < tl.out - 0.2 ? 1 : 0);
      set("all", "opacity", (t < 0.4 ? seg(t, 0, 0.4) : 1 - seg(t, tl.out, tl.T)).toFixed(3));
      beats.forEach((b, i) => {
        const [a, e] = tl.beats[i];
        b.classList.toggle("on", t >= a && t < e);
        b.classList.toggle("past", t >= e);
      });
    }

    let raf = 0;
    function play() {
      if (matchMedia("(prefers-reduced-motion: reduce)").matches) { draw(tl.ready ? tl.ready.start - 0.4 : tl.T / 2); return; }
      const start = performance.now();
      const loop = (now) => { if (!window.KILN_PAUSED) draw(((now - start) / 1000) % tl.T); raf = requestAnimationFrame(loop); };
      raf = requestAnimationFrame(loop);
    }
    draw(0.5);
    return { draw, play, stop: () => cancelAnimationFrame(raf), duration: tl.T, timeline: tl };
  }

  window.KilnRecovery = { mount, timeline };
})();
