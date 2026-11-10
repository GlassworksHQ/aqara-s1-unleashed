#!/usr/bin/env python3
"""Render every concept, validate it, and write index.html, the gallery.

    python3 build_all.py            # render + validate + gallery
    python3 build_all.py --gallery  # only re-validate and rebuild index.html
    python3 build_all.py --previews # re-encode previews from the zips, then the gallery

Needs Pillow + numpy. With pyoxipng installed, the frames come out ~20% smaller.
Example venv: python3 -m venv /tmp/s1venv && /tmp/s1venv/bin/pip install pillow numpy pyoxipng
"""
import base64
import html
import importlib.util
import io
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from PIL import Image  # noqa: E402

import common  # noqa: E402

CONCEPTS = ["1-thin-wordmark", "2-glass-tile", "3-progress-halo"]


def load(folder):
    spec = importlib.util.spec_from_file_location(folder.replace("-", "_"),
                                                  os.path.join(HERE, folder, "generate.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def kb(n):
    return f"{n / 1024:.0f} KB" if n < 1024 * 1024 else f"{n / 1024 / 1024:.2f} MB"


def data_uri(path_or_bytes, mime):
    b = path_or_bytes if isinstance(path_or_bytes, bytes) else open(path_or_bytes, "rb").read()
    return f"data:{mime};base64," + base64.b64encode(b).decode()


def gallery():
    cards = []
    for folder in CONCEPTS:
        d = os.path.join(HERE, folder)
        mod = load(folder)
        problems = common.validate(d)
        splash = Image.open(os.path.join(d, "splash-landscape.png")).convert("RGB").resize((720, 360), Image.LANCZOS)
        buf = io.BytesIO()
        splash.save(buf, "WEBP", quality=90, method=6)
        with zipfile.ZipFile(os.path.join(d, "bootanimation.zip")) as z:
            n0 = sum(1 for n in z.namelist() if n.startswith("part0/") and n.endswith(".png"))
            n1 = sum(1 for n in z.namelist() if n.startswith("part1/") and n.endswith(".png"))
        sizes = {f: os.path.getsize(os.path.join(d, f)) for f in ("bootlogo.bmp", "bootanimation.zip", "preview.webp")}
        status = ("<span class='ok'>All checks passed</span>" if not problems else
                  "<span class='bad'>" + html.escape("; ".join(problems)) + "</span>")
        cards.append(f"""
  <section class="card">
    <div class="head">
      <div class="label">{html.escape(folder.split('-', 1)[0])} &middot; concept</div>
      <h2>{html.escape(mod.META['name'])}</h2>
      <p class="why">{html.escape(mod.META['rationale'])}</p>
    </div>
    <div class="media">
      <figure><img src="{data_uri(buf.getvalue(), 'image/webp')}" alt="{html.escape(mod.META['name'])} power-on splash" loading="lazy">
        <figcaption><span class="label">Power-on splash</span><span>static, about 10 s from the bootloader</span></figcaption></figure>
      <figure><img src="{data_uri(os.path.join(d, 'preview.webp'), 'image/webp')}" alt="{html.escape(mod.META['name'])} boot animation preview" loading="lazy">
        <figcaption><span class="label">Boot sequence</span><span>splash &rarr; intro {n0 / 30:.1f} s &rarr; loop {n1 / 30:.1f} s</span></figcaption></figure>
    </div>
    <dl class="meta">
      <div><dt>bootlogo.bmp</dt><dd>{kb(sizes['bootlogo.bmp'])} &middot; 720&times;1440 24-bit</dd></div>
      <div><dt>bootanimation.zip</dt><dd>{kb(sizes['bootanimation.zip'])} &middot; stored &middot; {n0}+{n1} frames</dd></div>
      <div><dt>Validation</dt><dd>{status}</dd></div>
      <div><dt>Folder</dt><dd><code>concepts/{html.escape(folder)}/</code></dd></div>
    </dl>
  </section>""")

    page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>S1 Boot Branding</title>
<style>
  :root {{
    --bg: #0a0b0f; --tx: #f4f4f5; --mu: #a1a1aa; --fa: #63636b;
    --glass: rgba(255,255,255,.045); --bd: rgba(255,255,255,.07);
    --amber: #fbbf24; --green: #34d399; --rose: #fb7185;
    color-scheme: dark;
  }}
  * {{ box-sizing: border-box; }}
  html {{ background: var(--bg); }}
  body {{
    margin: 0; color: var(--tx); min-height: 100vh;
    font: 15px/1.5 Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    background:
      radial-gradient(85vw 85vw at -10% -20%, rgba(56,189,248,.13), transparent 70%),
      radial-gradient(65vw 65vw at 110% 120%, rgba(167,139,250,.14), transparent 70%),
      var(--bg);
    background-attachment: fixed;
  }}
  main {{ max-width: 1240px; margin: 0 auto; padding: 56px 24px 72px; }}
  .label {{ font-size: 11.5px; font-weight: 500; letter-spacing: .14em; text-transform: uppercase; color: var(--fa); }}
  header h1 {{ font-weight: 300; font-size: clamp(32px, 5vw, 48px); letter-spacing: -.02em; margin: 10px 0 12px; line-height: 1.1; }}
  header p {{ color: var(--mu); max-width: 70ch; margin: 0 0 8px; }}
  .card {{
    background: var(--glass); border: 1px solid var(--bd); border-radius: 26px;
    padding: 28px; margin-top: 28px;
  }}
  .card h2 {{ font-weight: 300; font-size: 30px; letter-spacing: -.02em; margin: 6px 0 6px; }}
  .why {{ color: var(--mu); margin: 0; max-width: 80ch; }}
  .media {{ display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-top: 22px; }}
  figure {{ margin: 0; }}
  figure img {{ display: block; width: 100%; aspect-ratio: 2 / 1; border-radius: 18px; border: 1px solid var(--bd); background: #0a0b0f; }}
  figcaption {{ display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; margin-top: 10px; color: var(--mu); font-size: 13.5px; }}
  .meta {{ display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 22px 0 0; }}
  .meta > div {{ background: rgba(255,255,255,.03); border: 1px solid var(--bd); border-radius: 18px; padding: 12px 16px; min-width: 0; }}
  .meta dt {{ font-size: 11.5px; font-weight: 500; letter-spacing: .14em; text-transform: uppercase; color: var(--fa); }}
  .meta dd {{ margin: 4px 0 0; font-size: 14px; overflow-wrap: anywhere; }}
  .ok {{ color: var(--green); }} .bad {{ color: var(--rose); }}
  code {{ font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--mu); }}
  footer {{ margin-top: 36px; color: var(--fa); font-size: 13.5px; }}
  footer li {{ margin: 4px 0; }}
  @media (max-width: 860px) {{
    .media {{ grid-template-columns: 1fr; }}
    .meta {{ grid-template-columns: 1fr 1fr; }}
  }}
  @media (max-width: 480px) {{
    main {{ padding: 32px 16px 48px; }}
    .card {{ padding: 18px; border-radius: 22px; }}
    .meta {{ grid-template-columns: 1fr; }}
  }}
</style>
</head>
<body>
<main>
  <header>
    <div class="label">S1 Unleashed &middot; boot branding</div>
    <h1>Pick a power-on look</h1>
    <p>Three concepts in the onboarding screen's visual language: a #0a0b0f backdrop with sky and violet glows, light type, letter-spaced labels, glass and an amber accent.
       Each one has a bootloader splash and an Android boot animation. The animation's first frame is the splash, pixel for pixel, so the handoff is seamless.</p>
  </header>
{''.join(cards)}
  <footer>
    <div class="label">Notes</div>
    <ul>
      <li>Android draws the 800&times;400 frames 1:1 in the centre of the 1440&times;720 screen and clears the rest to the <code>desc.txt</code> colour (#0a0b0f). The glows fade out before the frame edge, so no window shows.</li>
      <li>Previews are downscaled to 720&times;360 and lossy-compressed. The deployable files are lossless.</li>
      <li>Type: Inter (SIL Open Font License 1.1), fetched from google/fonts at build time. Everything else is drawn with shapes. No third-party marks.</li>
    </ul>
  </footer>
</main>
</body>
</html>
"""
    out = os.path.join(HERE, "index.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"wrote {out} ({kb(os.path.getsize(out))})")


def previews_from_zips():
    """Re-encode preview.webp from the frames already in bootanimation.zip."""
    for folder in CONCEPTS:
        d = os.path.join(HERE, folder)
        with zipfile.ZipFile(os.path.join(d, "bootanimation.zip")) as z:
            def frames(part):
                return [Image.open(io.BytesIO(z.read(n))).convert("RGB")
                        for n in sorted(z.namelist()) if n.startswith(part + "/") and n.endswith(".png")]
            intro, loop = frames("part0"), frames("part1")
        common.write_preview(os.path.join(d, "preview.webp"), intro[0], intro, loop)
        print(f"{folder}: preview.webp {kb(os.path.getsize(os.path.join(d, 'preview.webp')))}")


def main():
    if "--previews" in sys.argv:
        previews_from_zips()
    elif "--gallery" not in sys.argv:
        for folder in CONCEPTS:
            subprocess.run([sys.executable, os.path.join(HERE, folder, "generate.py")], check=True)
    gallery()


if __name__ == "__main__":
    main()
