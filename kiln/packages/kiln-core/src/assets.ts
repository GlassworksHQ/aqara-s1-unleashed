// Pinned blobs (kiln/scripts/stage-assets.mjs writes <base>manifest.json). Every blob is checked
// against its SHA-256 before use; a mismatch is refused. `A` is the recipe's asset names.
import { toHex } from './backup';

export interface AssetEntry<A extends string = string> { name: A; path: string; sha256: string; size: number; license: string; source: string }
export interface Manifest<A extends string = string> { generated: string; assets: AssetEntry<A>[] }

export class Assets<A extends string> {
  private manifest?: Promise<Manifest<A>>;
  private cache = new Map<A, Promise<Uint8Array>>();

  /** `base`: where manifest.json is, ending in a slash (e.g. `/assets/`). */
  constructor(private readonly base: string, private readonly f: typeof fetch = fetch.bind(globalThis)) {}

  getManifest(): Promise<Manifest<A>> {
    this.manifest ??= this.f(`${this.base}manifest.json`, { cache: 'no-cache' }).then(async (r) => {
      if (!r.ok) throw new Error(`installer files are missing (manifest.json: HTTP ${r.status})`);
      return (await r.json()) as Manifest<A>;
    });
    return this.manifest;
  }

  async has(name: A): Promise<boolean> {
    return (await this.getManifest()).assets.some((a) => a.name === name);
  }

  /** Fetches and verifies one blob; reports download progress when the size is known. */
  get(name: A, onProgress?: (done: number, total: number) => void): Promise<Uint8Array> {
    let p = this.cache.get(name);
    if (!p) {
      p = this.load(name, onProgress);
      p.catch(() => this.cache.delete(name));
      this.cache.set(name, p);
    }
    return p;
  }

  private async load(name: A, onProgress?: (done: number, total: number) => void): Promise<Uint8Array> {
    const entry = (await this.getManifest()).assets.find((a) => a.name === name);
    if (!entry) throw new Error(`installer file ${name} is not in the manifest`);
    const url = /^(\/|https?:)/.test(entry.path) ? entry.path : `${this.base}${entry.path}`;
    const r = await this.f(url);
    if (!r.ok || !r.body) throw new Error(`could not download ${name} (HTTP ${r.status})`);
    const out = new Uint8Array(entry.size);
    let got = 0;
    const reader = r.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (got + value.length > out.length) throw new Error(`${name} is larger than the manifest says`);
      out.set(value, got);
      got += value.length;
      onProgress?.(got, entry.size);
    }
    if (got !== entry.size) throw new Error(`${name}: got ${got} bytes, expected ${entry.size}`);
    const sha = toHex(await crypto.subtle.digest('SHA-256', out));
    if (sha !== entry.sha256) throw new Error(`${name} failed its SHA-256 check (got ${sha.slice(0, 16)}…): refusing to use it`);
    return out;
  }
}
