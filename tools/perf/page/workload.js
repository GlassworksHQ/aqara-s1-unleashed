/* Seeded workloads for the Glassworks perf test (MIT).
 * Pure functions only (no DOM), so tools/perf/test/page.test.mjs can run them in node and pin the
 * checksum the page reports. A checksum mismatch on a panel means the work did not run as written.
 */
(function (root) {
  "use strict";

  function mulberry32(seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  const DOMAINS = ["sensor", "light", "switch", "binary_sensor", "climate", "media_player"];
  const UNITS = ["°C", "%", "W", "kWh", "lx", ""];

  // A Home Assistant-like `states` payload: entity count and churn are fixed by the seed and tick.
  function makeStates(seed, count, tick) {
    const rnd = mulberry32(seed);
    const out = [];
    for (let i = 0; i < count; i++) {
      const d = DOMAINS[i % DOMAINS.length];
      const base = Math.floor(rnd() * 1000);
      const changes = rnd() < 0.3 ? tick : 0;
      out.push({
        entity_id: `${d}.entity_${i}`,
        state: d === "sensor" ? ((base + changes * 7) / 10).toFixed(1) : (base + changes) % 2 ? "on" : "off",
        attributes: {
          friendly_name: `Entity ${i} ${d.replace("_", " ")}`,
          unit_of_measurement: UNITS[i % UNITS.length],
          icon: `mdi:icon-${base % 50}`,
        },
        last_changed: new Date(1700000000000 + (base + changes) * 1000).toISOString(),
      });
    }
    return JSON.stringify(out);
  }

  const TEMPLATE = /\{\{\s*(state|unit|name)\s*\}\}/g;

  // One state-update burst: parse, format each entity through a template, diff against the last
  // burst, and sort the changes. Returns the changes and a checksum over everything computed.
  function burst(json, prev) {
    const states = JSON.parse(json);
    const next = new Map();
    const changed = [];
    let sum = 0;
    for (const s of states) {
      const vars = { state: s.state, unit: s.attributes.unit_of_measurement, name: s.attributes.friendly_name };
      const text = "{{ name }}: {{ state }} {{ unit }}".replace(TEMPLATE, (_, k) => vars[k]);
      next.set(s.entity_id, text);
      if (prev.get(s.entity_id) !== text) changed.push({ id: s.entity_id, text, at: s.last_changed });
      for (let i = 0; i < text.length; i++) sum = (sum * 31 + text.charCodeAt(i)) >>> 0;
    }
    changed.sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : a.id < b.id ? -1 : 1));
    for (const c of changed) sum = (sum * 33 + c.id.length) >>> 0;
    return { next, changed, checksum: sum };
  }

  // The fixed run the page times: WARMUP + RUNS bursts over successive ticks.
  const JS_SPEC = { seed: 89, entities: 400, warmup: 5, runs: 30 };

  function expectedChecksum(spec) {
    let prev = new Map();
    let sum = 0;
    for (let t = 0; t < spec.warmup + spec.runs; t++) {
      const r = burst(makeStates(spec.seed, spec.entities, t), prev);
      prev = r.next;
      sum = (sum ^ r.checksum) >>> 0;
    }
    return sum;
  }

  const api = { mulberry32, makeStates, burst, JS_SPEC, expectedChecksum };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GWWorkload = api;
})(this);
