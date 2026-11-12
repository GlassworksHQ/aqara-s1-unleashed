'use client';
// /aqara-s1-plus and its step routes: the S1 recipe on real WebUSB, or with ?demo=… a simulated panel.
import { loadOrCreateAdbKey } from '@glassworks/adb';
import { s1Plus, type AssetName } from '@glassworks/aqara-s1-plus-installer';
import { FelDevice } from '@glassworks/fel';
import { Assets, BackupStore, webUsbDevices } from '@glassworks/kiln-core';
import { usePathname, useRouter } from 'next/navigation';
import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { s1PlusUi } from '../frames/aqara-s1-plus';
import { DEMOS, demoSession, enterDemo, type Demo } from './demo';
import { registry, type Entry } from './registry';
import { Session } from './session';
import { useRouteSync } from './useRouteSync';
import { Wizard } from './Wizard';

export const BASE = '/aqara-s1-plus';

/**
 * Demos run in `next dev`, and in a build made with NEXT_PUBLIC_KILN_DEMO=1 set at build time (Next
 * inlines it into the bundle). A build without it has none, whatever is set later.
 */
const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

function realSession(): Session {
  const assets = new Assets<AssetName>('/assets/');
  return new Session({
    recipe: s1Plus,
    frame: s1PlusUi,
    devices: webUsbDevices,
    requestFel: () => FelDevice.request(),
    asset: (n, p) => assets.get(n, p),
    hasAsset: (n) => assets.has(n),
    backups: new BackupStore(s1Plus.backup.db),
    loadKey: loadOrCreateAdbKey,
  });
}

function create(withDemo: boolean): Entry {
  const d = withDemo ? new URLSearchParams(location.search).get('demo') : null;
  const demo = DEMO_ON && DEMOS.includes(d as Demo) ? (d as Demo) : undefined;
  return { base: BASE, name: s1PlusUi.name, session: demo ? demoSession(demo) : realSession(), demo };
}

/**
 * The S1's session lives in the registry, not in this component: the routes below /aqara-s1-plus
 * share it, and it outlives a visit to the picker, so a running install keeps running and the picker
 * can lead back to it. It is created once (StrictMode's double setup reuses it) and replaced only
 * when someone enters a route after the old session began a flow (routes.ts, `fresh`).
 */
function current(): Entry {
  const e = registry.get();
  return e?.base === BASE ? e : registry.set(create(true));
}

export function S1() {
  const entry = useSyncExternalStore(registry.subscribe, current, current);
  const router = useRouter();
  const path = usePathname();
  const replace = useCallback((url: string) => router.replace(url, { scroll: false }), [router]);
  const renew = useCallback(() => registry.set(create(false)), []);
  useRouteSync(entry, path, replace, renew);
  useEffect(() => {
    if (entry.demo && !entry.demoEntered) { entry.demoEntered = true; void enterDemo(entry.session, entry.demo as Demo); }
  }, [entry]);
  return <Wizard session={entry.session} frame={s1PlusUi} />;
}
