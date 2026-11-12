'use client';
// The wizard's screens (docs/design/kiln): one idea each, a short heading, at most one line under
// it, one ember button. They read the state and call the session; none keeps hardware state.
import * as Collapsible from '@radix-ui/react-collapsible';
import * as Dialog from '@radix-ui/react-dialog';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { fmtSize } from '@glassworks/kiln-core';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { FrameUi } from '../frames/aqara-s1-plus';
import { LABELS, mount } from '../recovery/player';
import type { Session } from './session';
import { eta, milestones, type Network, type Setup, type WizardState } from './state';

type P = { s: WizardState; session: Session; frame: FrameUi };

function Recovery({ frame }: { frame: FrameUi }) {
  const svg = useRef<SVGSVGElement>(null);
  const [beat, setBeat] = useState(-1);
  useEffect(() => {
    const p = mount(svg.current!, frame.recovery, setBeat);
    p.play();
    return () => p.stop();
  }, [frame]);
  const r = frame.recovery;
  return (
    <>
      <div className="recview">
        <svg ref={svg} role="img" aria-label={`How to put the ${r.name} in recovery mode: ${r.steps.map((x) => LABELS[x]).join(', ')}. ${r.button?.what ?? ''}`} />
      </div>
      <ol className="beats" aria-hidden="true">
        {r.steps.map((st, i) => <li key={st} className={i === beat ? 'on' : beat > i ? 'past' : ''}>{i + 1} · {r.labels?.[st] ?? LABELS[st]}</li>)}
      </ol>
    </>
  );
}

function os(): 'win' | 'linux' | 'other' {
  const ua = navigator.userAgent;
  return /Android/.test(ua) ? 'other' : /Windows/.test(ua) ? 'win' : /Linux/.test(ua) ? 'linux' : 'other';
}

/** 2 · Connect: the animation is the instruction. The picker opens inside the click. */
export function Connect({ s, session, frame }: P) {
  const driver = useMemo(() => { const o = os(); return o === 'other' ? undefined : frame.drivers[o]; }, [frame]);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h" className="huge">Hold the button. Plug it in.</h2>
      <Recovery frame={frame} />
      <p className="small">Then click Connect and pick <code>{frame.felPick}</code>.{s.flow === 'undo' ? ' Nothing is written until your backup checks out.' : ' Nothing is written yet.'}</p>
      {driver && <p className="small">{driver} <a className="link" href={frame.help.drivers} target="_blank" rel="noopener">How</a></p>}
      <div className="foot">
        <span className="links">
          <Dialog.Root>
            <Dialog.Trigger className="link">Show me a photo</Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="overlay" />
              <Dialog.Content className="sheet" style={{ width: 'min(640px, calc(100vw - 32px))' }}>
                <Dialog.Title asChild><h3>The recovery button</h3></Dialog.Title>
                <img className="photo" src={frame.photo.src} width={frame.photo.width} height={frame.photo.height} alt={frame.photoAlt} />
                <Dialog.Description>It&apos;s {frame.recovery.button?.what}. Not the reset button on the case.</Dialog.Description>
                <Dialog.Close className="btn ghost">Close</Dialog.Close>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
          {s.flow === 'undo' && <><span aria-hidden="true">·</span><button type="button" className="link" disabled={!!s.busy} onClick={() => void session.click('undo', () => session.openUndo())}>Back</button></>}
        </span>
        <button type="button" className="btn ember" disabled={!!s.busy} onClick={() => void session.click('connect', () => session.connectFel())}>Connect</button>
      </div>
    </section>
  );
}

/** 3 · Your turn: a sheet over the step when the browser needs a click. */
export function YourTurn({ s, session, frame }: P) {
  const ask = s.ask;
  const copy = ask && frame.asks[ask.kind];
  return (
    <Dialog.Root open={!!ask}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="sheet" onEscapeKeyDown={(e) => e.preventDefault()} onPointerDownOutside={(e) => e.preventDefault()}>
          {copy && <>
            <span className="label"><span className="pulse" />Your turn</span>
            <Dialog.Title asChild><h3>{copy.title}</h3></Dialog.Title>
            <Dialog.Description>{copy.body} Pick <code>{copy.pick}</code> in the list.</Dialog.Description>
            <button type="button" className="btn ember pulsing" disabled={ask.busy} onClick={() => void session.asks.click()}>Choose the panel</button>
            {ask.busy && <p className="status work" role="status">Pick it in the browser&apos;s list…</p>}
            {ask.error && <p className="status err" role="alert">{ask.error}</p>}
          </>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function TechLog({ s }: { s: WizardState }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
      <Collapsible.Trigger className="link">{open ? 'Hide technical log' : 'Technical log'}</Collapsible.Trigger>
      <Collapsible.Content className="tech">
        {s.parts.length > 0 && (
          <div className="parts">
            {s.parts.map(([p, x]) => (
              <span key={p} style={{ display: 'contents' }}>
                <code>{p}</code>
                <span className="small">{x.size ? fmtSize(x.size) : ''}</span>
                <span className="h">{x.sha256 ?? (x.state === 'cur' && x.pct !== undefined ? `${Math.round(x.pct * 100)}%` : x.state === 'pend' ? '–' : '')}</span>
              </span>
            ))}
          </div>
        )}
        <pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre>
      </Collapsible.Content>
    </Collapsible.Root>
  );
}

/** 4 · Install: the firing. One line, the bar, a short plain log; the technical log one click away. */
export function Firing({ s, frame }: P) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const steps = milestones(s, frame.short);
  const backedUp = s.flow === 'install' && ['patch', 'flash', 'verify'].includes(s.progress.phase);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h" className="huge">Keep it plugged in.</h2>
      <div style={{ display: 'grid', gap: 8 }}>
        <div className="spread"><span>{s.progress.title}</span><span className="small">{eta(s, now)}</span></div>
        <div className="bar" role="progressbar" aria-label={s.progress.title} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor(s.progress.overall * 100)}>
          <i style={{ width: `${Math.max(2, s.progress.overall * 100)}%` }} />
        </div>
      </div>
      <ul className="plain">
        {steps.map((m) => <li key={m.text}><span className={m.done ? 'ok' : 'go'}>{m.done ? '✓' : '→'}</span> {m.text}</li>)}
      </ul>
      <div className="foot">
        <span className="small">{s.flow === 'undo' ? 'Each part is checked after it is written.' : backedUp ? 'Your backup is safe in this browser.' : 'Nothing is written until your backup is safe in this browser.'}</span>
        <TechLog s={s} />
      </div>
    </section>
  );
}

const zones = () => (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
const LOCALES = ['en-US', 'en-GB', 'en-AU', 'en-CA', 'de-DE', 'fr-FR', 'fr-CA', 'es-ES', 'es-US', 'es-MX', 'it-IT', 'nl-NL', 'pt-PT', 'pt-BR',
  'pl-PL', 'cs-CZ', 'sv-SE', 'da-DK', 'nb-NO', 'fi-FI', 'ru-RU', 'uk-UA', 'tr-TR', 'el-GR', 'hu-HU', 'ro-RO', 'ja-JP', 'ko-KR', 'zh-CN', 'zh-TW', 'zh-HK'];

/** Language, time zone and clock, defaulting to this computer's; then the app goes in. */
function Settings({ s, session, frame, setup }: P & { setup: Extract<Setup, { phase: 'settings' }> }) {
  const here = typeof navigator === 'undefined' ? 'en-US' : navigator.language || 'en-US';
  const locales = useMemo(() => {
    const tags = [...LOCALES];
    if (/^[a-z]{2,3}-[A-Z]{2}$/.test(here) && !tags.includes(here)) tags.push(here);
    const names = new Intl.DisplayNames([here], { type: 'language' });
    return tags.map((t) => ({ t, n: names.of(t) ?? t })).sort((a, b) => a.n.localeCompare(b.n, here));
  }, [here]);
  const tzHere = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const tzs = useMemo(zones, []);
  const [locale, setLocale] = useState(() => (LOCALES.includes(here) ? here : LOCALES.find((t) => t.split('-')[0] === here.split('-')[0]) ?? ''));
  const [tz, setTz] = useState(tzs.includes(tzHere) ? tzHere : '');
  const [clock, setClock] = useState(new Intl.DateTimeFormat(here, { hour: 'numeric' }).resolvedOptions().hour12 ? '12' : '24');
  const [keyboard, setKeyboard] = useState(true);
  const busy = !!setup.busy;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{setup.update ? `Update ${frame.app}.` : 'A few settings.'}</h2>
      <p className="lede">These go to the panel with {frame.app}. They start as this computer&apos;s.</p>
      <div className="fields">
        <label className="field"><span>Language</span>
          <select className="select" value={locale} onChange={(e) => setLocale(e.target.value)} disabled={busy}>
            <option value="">Leave as it is</option>
            {locales.map(({ t, n }) => <option key={t} value={t}>{n} ({t})</option>)}
          </select>
        </label>
        <label className="field"><span>Time zone</span>
          <select className="select" value={tz} onChange={(e) => setTz(e.target.value)} disabled={busy}>
            <option value="">Leave as it is</option>
            {tzs.map((z) => <option key={z} value={z}>{z.replace(/_/g, ' ')}</option>)}
          </select>
        </label>
      </div>
      <div className="spread">
        <RadioGroup.Root className="seg" value={clock} onValueChange={setClock} aria-label="Clock" disabled={busy}>
          <RadioGroup.Item value="12">12-hour</RadioGroup.Item>
          <RadioGroup.Item value="24">24-hour</RadioGroup.Item>
        </RadioGroup.Root>
        <label className="check"><input type="checkbox" checked={keyboard} onChange={(e) => setKeyboard(e.target.checked)} disabled={busy} /> On-screen keyboard</label>
      </div>
      {setup.busy && <p className="status work" role="status">{setup.busy}</p>}
      {setup.error && <p className="status err" role="alert">{setup.error}</p>}
      <div className="foot">
        <span className="small">About a minute.</span>
        <button type="button" className="btn ember" disabled={busy || !!s.busy}
          onClick={() => void session.click('install-app', () => session.installApp({ locale: locale || undefined, timezone: tz || undefined, clock24: clock === '24', keyboard }))}>
          {setup.update ? 'Update and continue' : `Install ${frame.app}`}
        </button>
      </div>
    </section>
  );
}

function Signal({ rssi }: { rssi: number }) {
  const level = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -78 ? 2 : 1;
  return <span className="sig" aria-label={`signal ${level} of 4`}>{[1, 2, 3, 4].map((i) => <i key={i} style={{ height: i * 3 + 2, opacity: i <= level ? 1 : 0.25 }} />)}</span>;
}
const Lock = () => <svg className="lock" viewBox="0 0 24 24" aria-label="needs a password"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>;

/** 5 · Wi-Fi: which network, the password, connect. The list is what the panel can see. */
function Wifi({ s, session, setup }: P & { setup: Extract<Setup, { phase: 'wifi' }> }) {
  const nets: Network[] = setup.networks ?? [];
  const [pick, setPick] = useState('');
  const [other, setOther] = useState('');
  const [psk, setPsk] = useState('');
  useEffect(() => { if (!pick && setup.networks) setPick(setup.networks[0]?.ssid ?? '\u0000other'); }, [setup.networks, pick]);
  const isOther = pick === '\u0000other';
  const chosen = nets.find((n) => n.ssid === pick);
  const secure = isOther || !!chosen?.secure;
  const j = setup.joining;

  if (j) {
    const order = ['sent', 'join', 'ok'] as const;
    const at = j.step === 'err' ? 1 : order.indexOf(j.step);
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">{j.step === 'ok' ? `On ${j.ssid}.` : j.step === 'err' ? `Couldn't join ${j.ssid}.` : `Joining ${j.ssid}…`}</h2>
        <ol className="join" aria-live="polite">
          {['Sent to the panel over USB', 'The panel joins the network', 'Connected'].map((t, i) => (
            <li key={t} className={j.step === 'ok' || i < at ? 'done' : i === at ? (j.step === 'err' ? 'fail' : 'cur') : ''}>{t}</li>
          ))}
        </ol>
        {j.error && <p className="status err" role="alert">{j.error}</p>}
        <div className="foot">
          {j.step === 'err' ? <button type="button" className="link" disabled={!!s.busy} onClick={() => void session.click('ha', () => session.homeAssistant())}>Skip, do it on the panel</button> : <span />}
          {j.step === 'ok' && <button type="button" className="btn ember" disabled={!!s.busy} onClick={() => void session.click('ha', () => session.homeAssistant())}>Continue</button>}
          {j.step === 'err' && <button type="button" className="btn ember" disabled={!!s.busy} onClick={() => void session.click('wifi', () => session.wifi())}>Try again</button>}
        </div>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Which Wi-Fi?</h2>
      {setup.hint && <p className="lede">{setup.hint}</p>}
      {setup.networks === undefined && !setup.unreachable ? (
        <><p className="status" role="status">{setup.status}</p><div className="bar wait"><i /></div></>
      ) : setup.unreachable ? (
        <p className="status err" role="alert">{setup.status} Try again, or set up Wi-Fi on the panel: it shows Wi-Fi first.</p>
      ) : (
        <>
          <RadioGroup.Root className="list" value={pick} onValueChange={setPick} aria-label="Networks">
            {nets.map((n) => (
              <RadioGroup.Item key={n.ssid} value={n.ssid} className="opt" title={`${n.rssi} dBm`}>
                <span className="rd" /><span className="t">{n.ssid}</span>{n.secure && <Lock />}<Signal rssi={n.rssi} />
              </RadioGroup.Item>
            ))}
            <RadioGroup.Item value={'\u0000other'} className="opt"><span className="rd" /><span className="t">Other…</span></RadioGroup.Item>
          </RadioGroup.Root>
          <p className="small">{setup.status}</p>
          {isOther && <label className="field"><span>Network name</span><input className="input" value={other} onChange={(e) => setOther(e.target.value)} autoComplete="off" /></label>}
          {secure && <label className="field"><span>Password</span><input className="input" type="password" value={psk} onChange={(e) => setPsk(e.target.value)} autoComplete="off" /></label>}
        </>
      )}
      <div className="foot">
        <button type="button" className="link" disabled={!!s.busy} onClick={() => void session.click('ha', () => session.homeAssistant())}>Skip, do it on the panel</button>
        {setup.unreachable
          ? <button type="button" className="btn ember" disabled={!!s.busy} onClick={() => void session.click('wifi', () => session.wifi())}>Try again</button>
          : <button type="button" className="btn ember" disabled={!setup.networks || !!s.busy} onClick={() => void session.click('join', () => session.joinWifi(isOther ? other : pick, secure ? psk : ''))}>Connect</button>}
      </div>
    </section>
  );
}

/** 6 · Home Assistant: the address it found, and sign-in on the panel's own page (no token in this browser). */
function HomeAssistant({ s, session, setup }: P & { setup: Extract<Setup, { phase: 'ha' }> }) {
  const [url, setUrl] = useState(setup.ha.url || setup.ha.discovered || 'http://homeassistant.local:8123');
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{setup.ha.signedIn ? 'Signed in to Home Assistant.' : 'Sign in to Home Assistant.'}</h2>
      <p className="lede">{setup.ha.discovered ? `Found at ${new URL(setup.ha.discovered).host}.` : 'Not found by itself. Type its address.'}</p>
      <label className="field"><span>Address</span><input className="input" value={url} onChange={(e) => setUrl(e.target.value)} inputMode="url" autoComplete="off" /></label>
      {!setup.ha.signedIn && <button type="button" className="btn ghost" style={{ justifySelf: 'start' }} disabled={!!s.busy} onClick={() => void session.click('sign-in', () => session.signIn(url.trim()))}>Sign in</button>}
      {setup.ha.signedIn && <p className="status ok">The panel is signed in.</p>}
      {setup.note && !setup.ha.signedIn && <p className="status" role="status">{setup.note}</p>}
      {setup.error && <p className="status err" role="alert">{setup.error}</p>}
      <div className="foot">
        <button type="button" className="link" disabled={!!s.busy} onClick={() => void session.click('radio', () => session.radio())}>Skip, do it on the panel</button>
        <button type="button" className="btn ember" disabled={setup.busy || !!s.busy} onClick={() => void session.click('save-ha', () => session.saveHa(url.trim()))}>Continue</button>
      </div>
    </section>
  );
}

/** The radio pane's one choice: what the Zigbee/Thread radio does. */
function Radio({ s, session, setup }: P & { setup: Extract<Setup, { phase: 'radio' }> }) {
  const [mode, setMode] = useState(setup.radio.mode);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">What should the radio do?</h2>
      <p className="lede">The panel has a Zigbee and Thread radio. Leave it off, or let Home Assistant use it.</p>
      <RadioGroup.Root className="list" value={mode} onValueChange={setMode} aria-label="Radio role" disabled={setup.busy}>
        {setup.radio.options.map((o) => (
          <RadioGroup.Item key={o.id} value={o.id} className="opt" disabled={!setup.radio.helper && o.id !== 'off'}>
            <span className="rd" />
            <span className="t"><span>{o.label}{o.experimental && <em className="tag">Experimental</em>}</span><small>{o.detail}</small></span>
          </RadioGroup.Item>
        ))}
      </RadioGroup.Root>
      <p className={setup.error ? 'status err' : 'status'} role="status">{setup.error ?? setup.note}</p>
      <div className="foot">
        <button type="button" className="link" onClick={() => session.finish()}>Skip, leave it as it is</button>
        <button type="button" className="btn ember" disabled={setup.busy || !!s.busy} onClick={() => void session.click('radio', () => session.applyRadio(mode))}>Finish</button>
      </div>
    </section>
  );
}

function Copy({ value }: { value: string }) {
  const [t, setT] = useState('Copy');
  return <button type="button" className="link" onClick={async () => {
    try { await navigator.clipboard.writeText(value); setT('Copied'); } catch { setT('Select it'); }
    setTimeout(() => setT('Copy'), 1600);
  }}>{t}</button>;
}

function RadioNext({ session, setup }: P & { setup: Extract<Setup, { phase: 'radio-next' }> }) {
  const { plan } = setup;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{plan.title}</h2>
      <p className="lede">{plan.lead}</p>
      <ol className="next" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {plan.steps.map((st) => (
          <li key={st.title} style={{ display: 'grid', gap: 6, border: '1px solid var(--edge)', borderRadius: 14, padding: '12px 14px' }}>
            <b style={{ fontWeight: 500 }}>{st.title}</b>
            <span className="small" style={{ color: 'var(--muted)' }}>{st.body}</span>
            {st.kv && <dl className="kv">{st.kv.map(([k, v, copy]) => <span key={k} style={{ display: 'contents' }}><dt>{k}</dt><dd>{copy ? <><code>{v}</code><Copy value={v} /></> : v}</dd></span>)}</dl>}
            {st.link && <a className="link" href={st.link} target="_blank" rel="noopener">Open in Home Assistant</a>}
          </li>
        ))}
      </ol>
      <div className="foot"><span /><button type="button" className="btn ember" onClick={() => session.finish()}>Finish</button></div>
    </section>
  );
}

function Waiting({ setup }: { setup: Extract<Setup, { phase: 'waiting' }> }) {
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Starting the panel.</h2>
      <p className="lede">{setup.note}</p>
      <div className="bar wait"><i /></div>
    </section>
  );
}

/** The set-up step, one phase at a time. */
export function SetUp(p: P) {
  const setup = p.s.setup;
  switch (setup.phase) {
    case 'waiting': return <Waiting setup={setup} />;
    case 'settings': return <Settings {...p} setup={setup} />;
    case 'wifi': return <Wifi {...p} setup={setup} />;
    case 'ha': return <HomeAssistant key="ha" {...p} setup={setup} />;
    case 'radio': return <Radio key={setup.radio.mode} {...p} setup={setup} />;
    case 'radio-next': return <RadioNext {...p} setup={setup} />;
  }
}

/** 7 · Done: in one line. The backup is one row with a download; undo is a link. */
export function Done({ s, session }: P) {
  const d = s.done!;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{d.title}</h2>
      <p className="lede">{d.body}</p>
      {d.backup && (
        <div className="well">
          <span className="grow"><span>{d.backup.hint}</span><span className="small">{d.backup.name} · {d.backup.info}</span></span>
          <button type="button" className="btn ghost" onClick={() => session.downloadBackup()}>Download</button>
        </div>
      )}
      <div className="foot">
        {s.flow === 'install' ? <button type="button" className="link" onClick={() => void session.click('undo', () => session.openUndo())}>Undo</button> : <span />}
        <a className="btn ghost" href="/">Back to Kiln</a>
      </div>
    </section>
  );
}

/** 8 · Undo: how far back, as two choices. It asks for a file only if this browser has no backup. */
export function Undo({ s, session, frame }: P) {
  const u = s.undo;
  const file = useRef<HTMLInputElement>(null);
  const keep = u.depth === 'keep';
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Put it back.</h2>
      <RadioGroup.Root className="list" value={u.depth} onValueChange={(v) => session.store.dispatch({ type: 'undo', patch: { depth: v as 'factory' | 'keep', appsBack: false, note: undefined } })} aria-label="How far back">
        {(['factory', 'keep'] as const).map((d) => (
          <RadioGroup.Item key={d} value={d} className="opt"><span className="rd" /><span className="t"><span>{frame.undo[d].title}</span><small>{frame.undo[d].detail}</small></span></RadioGroup.Item>
        ))}
      </RadioGroup.Root>
      {u.note && <p className={`status ${u.note.kind === 'err' ? 'err' : u.note.kind === 'ok' ? 'ok' : 'work'}`} role="status"><b style={{ fontWeight: 500 }}>{u.note.title}</b> {u.note.sub}</p>}
      {u.source === 'browser' && u.inBrowser && (
        <div className="well">
          <span className="grow"><span>The backup in this browser</span><span className="small">{u.inBrowser}</span></span>
          <button type="button" className="btn ghost" onClick={() => session.downloadUndoBackup()}>Download this backup</button>
        </div>
      )}
      <input ref={file} type="file" accept=".zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void session.undoFromFile(f); }} />
      <div className="foot">
        <span className="links">
          {u.source !== 'browser' && <><span>{u.file ?? 'No backup in this browser.'}</span><span aria-hidden="true">·</span></>}
          {u.source === 'file' && u.inBrowser
            ? <button type="button" className="link" onClick={() => void session.undoFromBrowser()}>Use this browser&apos;s</button>
            : <button type="button" className="link" onClick={() => file.current?.click()}>{u.source === 'file' ? 'Another file' : 'Use a backup file'}</button>}
        </span>
        <button type="button" className="btn ember" disabled={!u.ready || !!s.busy} onClick={() => void session.click('undo-continue', () => session.undoContinue())}>
          {keep && !u.appsBack ? frame.undo.keep.button : 'Continue'}
        </button>
      </div>
      <p className="small">From a terminal instead: <code>{frame.help.undoCli}</code></p>
    </section>
  );
}

/** 9 · When something fails: what happened, whether anything was written, the most likely fix. */
export function Failure({ s, session, frame }: P) {
  const f = s.failure!;
  const [open, setOpen] = useState(false);
  const retry = () => {
    if (f.at >= 3) void session.click('resume', () => session.route('resume'));
    else session.store.dispatch({ type: 'go', screen: 'connect' });
  };
  return (
    <section className="card err" aria-labelledby="h" role="alert">
      <h2 id="h">{f.title}</h2>
      <p className="lede">{f.body}</p>
      <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
        <div className="foot">
          <span className="links">
            <Collapsible.Trigger className="link">{open ? 'Less' : 'More help'}</Collapsible.Trigger>
            {f.wrote && <><span aria-hidden="true">·</span><button type="button" className="link" onClick={retry}>Try again</button></>}
          </span>
          {f.wrote
            ? <button type="button" className="btn ember" onClick={() => void session.click('undo', () => session.openUndo())}>Undo with your backup</button>
            : <button type="button" className="btn ember" onClick={retry}>Try again</button>}
        </div>
        <Collapsible.Content className="more">
          <ol className="tips">{f.tips.map(([b, t]) => <li key={b}><b>{b}</b> {t}</li>)}</ol>
          {f.raw && <pre className="raw">{f.raw}</pre>}
          <p className="links">
            <a className="link" href={frame.help.recovery} target="_blank" rel="noopener">Recovery and troubleshooting</a>
            <span aria-hidden="true">·</span>
            <button type="button" className="link" onClick={() => void session.click('undo', () => session.openUndo())}>Undo with your backup</button>
          </p>
          <p className="small">Undo from a terminal: <code>{frame.help.undoCli}</code> (<a className="link" href={frame.help.undo} target="_blank" rel="noopener">how</a>)</p>
          <TechLog s={s} />
        </Collapsible.Content>
      </Collapsible.Root>
    </section>
  );
}

/** The browser check, as a screen only when it fails. */
export function Browser() {
  const secure = typeof window !== 'undefined' && isSecureContext;
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">{secure ? 'Use Chrome or Edge.' : 'Open this page over https.'}</h2>
      <p className="lede">{secure
        ? 'This browser can’t talk to USB devices. Kiln needs Chrome or Edge on a computer, or Chrome on Android.'
        : 'Browsers only open USB devices from a secure page (https, or localhost).'}</p>
    </section>
  );
}
