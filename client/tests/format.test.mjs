import assert from 'node:assert/strict';
import test from 'node:test';

import { formatKstDateTime } from '../src/lib/format.ts';

test('KST timestamp formatting is deterministic across SSR and browser timezones', () => {
  assert.equal(
    formatKstDateTime('2026-08-05T12:34:56.789Z'),
    '2026. 08. 05. 21:34:56 KST',
  );
});

test('invalid timestamps fail closed without throwing during render', () => {
  assert.equal(formatKstDateTime('not-a-date'), '-');
});
