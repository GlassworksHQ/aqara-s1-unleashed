'use client';
// /aqara-s1-plus: the S1 recipe on real WebUSB, or with ?demo=… a simulated panel.
import { loadOrCreateAdbKey } from '@glassworks/adb';
import { s1Plus, type AssetName } from '@glassworks/aqara-s1-plus-installer';
import { FelDevice } from '@glassworks/fel';
import { Assets, BackupStore, webUsbDevices } from '@glassworks/kiln-core';
import { useEffect, useState } from 'react';
import { s1PlusUi } from '../frames/aqara-s1-plus';
import { DEMOS, demoSession, enterDemo, type Demo } from './demo';
import { Session } from './session';
import { Wizard } from './Wizard';

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

/**
 * One session per page load. React's StrictMode runs component setup twice in development, and a
 * second Session would load (or create) a second adb key in IndexedDB at the same time.
 */
let page: { session: Session; demo?: Demo; entered: boolean } | undefined;

function pageSession(): NonNullable<typeof page> {
  if (!page) {
    const d = new URLSearchParams(location.search).get('demo');
    const demo = DEMO_ON && DEMOS.includes(d as Demo) ? (d as Demo) : undefined;
    page = { session: demo ? demoSession(demo) : realSession(), demo, entered: false };
  }
  return page;
}

export function S1() {
  const [{ session, demo }] = useState(pageSession);
  useEffect(() => {
    const p = pageSession();
    if (p.demo && !p.entered) { p.entered = true; void enterDemo(p.session, p.demo); }
  }, []);
  return <Wizard session={session} frame={s1PlusUi} routes={!demo} />;
}
