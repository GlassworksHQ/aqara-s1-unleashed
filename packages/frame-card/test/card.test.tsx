// @vitest-environment jsdom
// What a caller of the card gets: the stage's shape, the name as a heading or not, the link's text,
// and ships() for both a frame's stage and a pane's support.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, expectTypeOf, it } from 'vitest';
import mirrorJson from '../../../frames/lululemon-mirror/design/frame.json';
import s1Json from '../../../frames/aqara-s1-plus/design/frame.json';
import { DeviceModel, FrameCardBody, ships, type FrameDesign, type Support } from '../src';

const s1 = s1Json as FrameDesign;
const mirror = mirrorJson as FrameDesign;

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

  it('draws a portrait frame on a taller stage when asked', () => {
    expect(stage(render(<DeviceModel id="mirror" model={mirror.model} label="Mirror" aspect={0.8} />))).toEqual({ aspect: '0.8', viewBox: '0 0 100 125' });
  });
});

describe('FrameCardBody', () => {
  it('keeps the name a span by default, so the body fits in a button', () => {
    const el = render(<FrameCardBody f={s1} />);
    expect(el.querySelector('h1, h2, h3, h4, h5, h6')).toBeNull();
    expect([...el.querySelectorAll('span')].some((n) => n.textContent === s1.name)).toBe(true);
  });

  it('makes the name a heading when asked, not inside a span', () => {
    const h = render(<FrameCardBody f={s1} as="h2" />).querySelector('h2');
    expect(h?.textContent).toBe(s1.name);
    expect(h?.closest('span')).toBeNull();
  });
});

it('the card\'s class doesn\'t underline its text, for a card that is a link', () => {
  const css = readFileSync(join(__dirname, '../src/FrameCard.module.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const card = [...css.matchAll(/(?<=^|\})\s*([^{}]+?)\s*\{([^}]*)\}/g)].filter((m) => m[1] === '.card');
  expect(card).toHaveLength(1);
  expect(card[0]![2]).toMatch(/(^|;)\s*text-decoration:\s*none\s*(;|$)/);
});

describe('ships', () => {
  it('takes a pane\'s support as well as a frame\'s stage', () => {
    expectTypeOf(ships).parameter(0).toEqualTypeOf<Support>();
    expect((['alpha', 'beta', 'stable'] as const).map(ships)).toEqual([true, true, true]);
    expect((['planned', 'development', 'depends', 'unknown', 'no'] as const).map(ships)).toEqual([false, false, false, false, false]);
  });
});
