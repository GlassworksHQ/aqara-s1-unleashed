import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { S1Wizard } from './S1Wizard';

export const metadata: Metadata = { title: 'Aqara S1 Plus' };

/**
 * The wizard lives in the layout, so it stays mounted while the step routes below change; each
 * page only names its route. What a route shows comes from the session (wizard/routes.ts).
 */
export default function Layout({ children }: { children: ReactNode }) {
  return (
    <>
      <S1Wizard />
      {children}
    </>
  );
}
