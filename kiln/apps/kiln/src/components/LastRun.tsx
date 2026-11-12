'use client';
// "The last run stopped": the kept failure (wizard/registry.ts), with its log as a download, on the
// picker and on Connect, so moving on from the Error screen doesn't lose what went wrong.
import { useSyncExternalStore } from 'react';
import { failureLog, registry } from '../wizard/registry';

export function LastRun() {
  const f = useSyncExternalStore(registry.subscribe, registry.lastFailure, () => undefined);
  if (!f) return null;
  const download = () => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([failureLog(f)], { type: 'text/plain' }));
    a.download = `kiln-log-${f.when.toISOString().slice(0, 19).replace(/[-:T]/g, '')}.txt`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
  };
  return (
    <p className="small" role="status">
      The last run stopped: {f.failure.title}{' '}
      <button type="button" className="link" onClick={download}>Download its log</button>
    </p>
  );
}
