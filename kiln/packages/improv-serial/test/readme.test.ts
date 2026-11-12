import { expect, it } from 'vitest';
import { setUpWifi } from './readme-example';

it('the README example compiles and exports setUpWifi', () => {
  expect(typeof setUpWifi).toBe('function');
});
