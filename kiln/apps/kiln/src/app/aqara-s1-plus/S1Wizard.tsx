'use client';
import dynamic from 'next/dynamic';

// WebUSB, IndexedDB and the adb key exist only in the browser, so the wizard never renders on the server.
export const S1Wizard = dynamic(() => import('../../wizard/S1').then((m) => m.S1), { ssr: false });
