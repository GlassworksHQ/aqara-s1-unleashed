import type { Metadata, Viewport } from 'next';
import { Inter, JetBrains_Mono } from 'next/font/google';
import icon from '@glassworks/brand/icon-kiln.svg';
import { LeaveGuard } from '../components/LeaveGuard';
import { MarkDefs } from '../components/Mark';
import './globals.css';

// The same faces and variable names as glassworks.dev, which the brand tokens (--f-sans, --f-mono) read.
const inter = Inter({ variable: '--font-inter', subsets: ['latin'], weight: ['200', '300', '400', '500'] });
const mono = JetBrains_Mono({ variable: '--font-mono', subsets: ['latin'], weight: ['500'] });

export const metadata: Metadata = {
  title: { default: 'Glassworks Kiln', template: '%s · Kiln' },
  description: 'Kiln installs Glassworks on your wall panel from this browser, over USB. It backs the panel up first, and can put it back.',
  icons: { icon: icon.src },
};

export const viewport: Viewport = {
  viewportFit: 'cover',
  colorScheme: 'light dark',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${inter.variable} ${mono.variable}`}>
      <body>
        <MarkDefs />
        <LeaveGuard />
        {children}
      </body>
    </html>
  );
}
