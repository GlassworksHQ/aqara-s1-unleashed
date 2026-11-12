// "Keep the panel's settings" gives the panel back to Aqara's apps before the software is restored,
// and that removes or disables S1 Kiosk. So the backup must pass undo's checks first: a backup that
// can't be restored stops here, with the kiosk still in place.
import { checkRestorable, type BackupPlan } from '@glassworks/kiln-core';

export async function giveBackApps(plan: BackupPlan, files: ReadonlyMap<string, Uint8Array>, giveBack: () => Promise<void>): Promise<void> {
  await checkRestorable(plan, files);
  await giveBack();
}
