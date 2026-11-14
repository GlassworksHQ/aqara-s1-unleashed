// @vitest-environment jsdom
// What a caller of the card gets: the stage's shape, the name as a heading or not, the link's text,
// what the install tags say to a screen reader, and ships() for a frame's stage and a pane's support.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, expectTypeOf, it } from 'vitest';
import mirrorJson from '../../../frames/lululemon-mirror/design/frame.json';
import s1Json from '../../../frames/aqara-s1-plus/design/frame.json';
import { DeviceModel, FrameCardBody, ships, type FrameDesign, type Support } from '../src';

const s1 = s1Json as FrameDesign;
// JSON imports widen the stand's [x, y, z] tuples to number[], so the cast goes through unknown
const mirror = mirrorJson as unknown as FrameDesign;

/** The declarations of each rule in FrameCard.module.css whose selector is exactly `selector`. */
const cssRules = (selector: string) => {
  const css = readFileSync(join(__dirname, '../src/FrameCard.module.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  return [...css.matchAll(/(?<=^|\})\s*([^{}]+?)\s*\{([^}]*)\}/g)].filter((m) => m[1] === selector).map((m) => m[2]!);
};

const render = (node: React.ReactElement) => {
  const el = document.createElement('div');
  el.innerHTML = renderToStaticMarkup(node);
  return el;
};

describe('DeviceModel', () => {
  const stage = (el: HTMLElement) => ({
    aspect: el.querySelector<HTMLElement>('[role="img"]')!.style.aspectRatio,
    viewBox: el.querySelector('svg')!.getAttribute('viewBox'),
  });

  it('draws on a 2:1 stage by default, as on Kiln\'s cards', () => {
    expect(stage(render(<DeviceModel id="s1" model={s1.model} label="S1" />))).toEqual({ aspect: '2', viewBox: '0 0 100 50' });
  });

  it('shows the frame\'s render on the glass when given one, and the example dashboard without', () => {
    const shown = render(<DeviceModel id="s1" model={s1.model} label="S1" screen="/r/render.webp" />);
    expect(shown.querySelector('img')!.getAttribute('src')).toBe('/r/render.webp');
    expect(shown.textContent).not.toMatch(/Living room/);
    const example = render(<DeviceModel id="s1" model={s1.model} label="S1" />);
    expect(example.querySelector('img')).toBeNull();
    expect(example.textContent).toMatch(/Living room/);
  });

  it('draws a portrait frame on a taller stage when asked', () => {
    expect(stage(render(<DeviceModel id="mirror" model={mirror.model} label="Mirror" aspect={0.8} />))).toEqual({ aspect: '0.8', viewBox: '0 0 100 125' });
  });
});

describe('FrameCardBody', () => {
  it('passes the render to the model', () => {
    expect(render(<FrameCardBody f={s1} screen="/r/render.webp" />).querySelector('img')!.getAttribute('src')).toBe('/r/render.webp');
  });

  it('keeps the name a span by default, so the body fits in a button', () => {
    const el = render(<FrameCardBody f={s1} />);
    expect(el.querySelector('h1, h2, h3, h4, h5, h6')).toBeNull();
    expect([...el.querySelectorAll('span')].some((n) => n.textContent === s1.name)).toBe(true);
  });

  describe('install tags', () => {
    const tags = (node: React.ReactElement) => render(node).querySelector('#c-tags')!.textContent;
    const EXPECTED = /^Expected, not tested yet: /;

    it('reads as tested on a frame that ships', () => {
      expect(ships(s1.status)).toBe(true);
      expect(tags(<FrameCardBody f={s1} idPrefix="c" />)).not.toMatch(EXPECTED);
    });

    it('reads as expected on a frame that doesn\'t ship yet, in the text aria-describedby reads', () => {
      expect(ships(mirror.status)).toBe(false);
      expect(tags(<FrameCardBody f={mirror} idPrefix="c" />)).toMatch(EXPECTED);
    });

    it('follows an explicit expected prop', () => {
      expect(tags(<FrameCardBody f={s1} idPrefix="c" expected />)).toMatch(EXPECTED);
      expect(tags(<FrameCardBody f={mirror} idPrefix="c" expected={false} />)).not.toMatch(EXPECTED);
    });

    it('hides the words from sight, out of the tags\' flow', () => {
      const rule = cssRules('.tags > .expected');
      expect(rule).toHaveLength(1);
      expect(rule[0]).toMatch(/position:\s*absolute/);
      expect(rule[0]).toMatch(/clip-path:\s*inset\(50%\)/);
    });
  });

  it('makes the name a heading when asked, not inside a span', () => {
    const h = render(<FrameCardBody f={s1} as="h2" />).querySelector('h2');
    expect(h?.textContent).toBe(s1.name);
    expect(h?.closest('span')).toBeNull();
  });

  it('resets a heading\'s margin and line height on the name, so it looks like the span', () => {
    const name = cssRules('.name');
    expect(name).toHaveLength(1);
    expect(name[0]).toMatch(/(^|;)\s*margin:\s*0\s*(;|$)/);
    expect(name[0]).toMatch(/(^|;)\s*line-height:\s*inherit\s*(;|$)/);
  });
});

it('the card\'s class doesn\'t underline its text, for a card that is a link', () => {
  const card = cssRules('.card');
  expect(card).toHaveLength(1);
  expect(card[0]).toMatch(/(^|;)\s*text-decoration:\s*none\s*(;|$)/);
});

describe('ships', () => {
  it('takes a pane\'s support as well as a frame\'s stage', () => {
    expectTypeOf(ships).parameter(0).toEqualTypeOf<Support>();
    expect((['alpha', 'beta', 'stable'] as const).map(ships)).toEqual([true, true, true]);
    expect((['planned', 'development', 'depends', 'unknown', 'no'] as const).map(ships)).toEqual([false, false, false, false, false]);
  });
});
