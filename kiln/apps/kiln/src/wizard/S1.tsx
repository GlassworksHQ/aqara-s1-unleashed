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

/** Demos run in `next dev`, and in a build made with KILN_DEMO=1 (previews, screenshots). Never in production. */
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

export function S1() {
  const [demo] = useState(() => {
    const d = new URLSearchParams(location.search).get('demo');
    return DEMO_ON && DEMOS.includes(d as Demo) ? (d as Demo) : undefined;
  });
  const [session] = useState(() => (demo ? demoSession(demo) : realSession()));
  useEffect(() => { if (demo) void enterDemo(session, demo); }, [demo, session]);
  return <Wizard session={session} frame={s1PlusUi} routes={!demo} />;
}
