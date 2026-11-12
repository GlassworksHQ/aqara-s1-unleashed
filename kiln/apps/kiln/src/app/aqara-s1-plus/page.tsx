import type { Metadata } from 'next';
import { S1Wizard } from './S1Wizard';

export const metadata: Metadata = { title: 'Aqara S1 Plus' };

export default function Page() {
  return <S1Wizard />;
}
