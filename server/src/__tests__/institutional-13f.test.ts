import {
  parseInfotable,
  computeNasdaqMegacapExposure,
  computePositionChanges,
  computeNasdaqMegacapFlow,
  NASDAQ_MEGACAP_CUSIPS,
  FundPositions,
  FundQuarterlyPositions,
} from '../collectors/institutional-13f';

describe('institutional-13f parser', () => {
  const sampleXml = `<?xml version="1.0" encoding="UTF-8"?>
<informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
  <infoTable>
    <nameOfIssuer>APPLE INC</nameOfIssuer>
    <cusip>037833100</cusip>
    <value>1500000</value>
    <shrsOrPrnAmt>
      <sshPrnamt>5000000</sshPrnamt>
      <sshPrnamtType>SH</sshPrnamtType>
    </shrsOrPrnAmt>
  </infoTable>
  <infoTable>
    <nameOfIssuer>MICROSOFT CORP</nameOfIssuer>
    <cusip>594918104</cusip>
    <value>800000</value>
    <shrsOrPrnAmt>
      <sshPrnamt>2000000</sshPrnamt>
      <sshPrnamtType>SH</sshPrnamtType>
    </shrsOrPrnAmt>
  </infoTable>
  <infoTable>
    <nameOfIssuer>FORD MOTOR CO</nameOfIssuer>
    <cusip>345370860</cusip>
    <value>200000</value>
    <shrsOrPrnAmt>
      <sshPrnamt>10000000</sshPrnamt>
      <sshPrnamtType>SH</sshPrnamtType>
    </shrsOrPrnAmt>
  </infoTable>
</informationTable>`;

  it('parses infoTable blocks into positions with CUSIP / value / shares', () => {
    const positions = parseInfotable(sampleXml);
    expect(positions).toHaveLength(3);
    expect(positions[0]).toEqual({ cusip: '037833100', value: 1500000, shares: 5000000 });
    expect(positions[1]).toEqual({ cusip: '594918104', value: 800000, shares: 2000000 });
    expect(positions[2]).toEqual({ cusip: '345370860', value: 200000, shares: 10000000 });
  });

  it('handles namespaced tags (ns1:) correctly', () => {
    const nsXml = sampleXml
      .replace(/<infoTable>/g, '<ns1:infoTable>')
      .replace(/<\/infoTable>/g, '</ns1:infoTable>')
      .replace(/<cusip>/g, '<ns1:cusip>')
      .replace(/<\/cusip>/g, '</ns1:cusip>')
      .replace(/<value>/g, '<ns1:value>')
      .replace(/<\/value>/g, '</ns1:value>')
      .replace(/<sshPrnamt>/g, '<ns1:sshPrnamt>')
      .replace(/<\/sshPrnamt>/g, '</ns1:sshPrnamt>');
    const positions = parseInfotable(nsXml);
    expect(positions).toHaveLength(3);
    expect(positions[0].cusip).toBe('037833100');
  });
});

describe('computeNasdaqMegacapExposure', () => {
  const buildFund = (name: string, positions: { cusip: string; value: number }[]): FundPositions => ({
    cik: '0000000000',
    fundName: name,
    filingDate: '2026-02-14',
    quarter: '2025Q4',
    positions: positions.map((p) => ({ ...p, shares: 0 })),
    totalValue: positions.reduce((s, p) => s + p.value, 0),
  });

  it('returns null when fewer than 3 funds', () => {
    const funds = [
      buildFund('A', [{ cusip: '037833100', value: 100 }]),
      buildFund('B', [{ cusip: '037833100', value: 50 }]),
    ];
    expect(computeNasdaqMegacapExposure(funds)).toBeNull();
  });

  it('computes simple average megacap share across funds', () => {
    const AAPL = NASDAQ_MEGACAP_CUSIPS.AAPL;
    const MSFT = NASDAQ_MEGACAP_CUSIPS.MSFT;
    const OTHER = '345370860'; // non-megacap
    const funds = [
      // 50% megacap
      buildFund('F1', [{ cusip: AAPL, value: 500 }, { cusip: OTHER, value: 500 }]),
      // 20% megacap
      buildFund('F2', [{ cusip: MSFT, value: 200 }, { cusip: OTHER, value: 800 }]),
      // 0% megacap
      buildFund('F3', [{ cusip: OTHER, value: 1000 }]),
    ];
    const result = computeNasdaqMegacapExposure(funds);
    expect(result).not.toBeNull();
    expect(result!.fundCount).toBe(3);
    // (50 + 20 + 0) / 3 = 23.33
    expect(result!.avgSharePct).toBeCloseTo(23.33, 1);
  });

  it('skips funds with totalValue = 0', () => {
    const AAPL = NASDAQ_MEGACAP_CUSIPS.AAPL;
    const funds = [
      buildFund('F1', [{ cusip: AAPL, value: 100 }]), // 100%
      buildFund('F2', [{ cusip: AAPL, value: 0 }]),   // totalValue=0 → skipped
      buildFund('F3', [{ cusip: AAPL, value: 50 }]), // 100%
      buildFund('F4', [{ cusip: AAPL, value: 200 }]), // 100%
    ];
    const result = computeNasdaqMegacapExposure(funds);
    expect(result).not.toBeNull();
    expect(result!.fundCount).toBe(3); // F2 제외
    expect(result!.avgSharePct).toBeCloseTo(100, 1);
  });
});

describe('computePositionChanges (Phase 2)', () => {
  const buildFund = (positions: { cusip: string; shares: number; value: number }[]): FundPositions => ({
    cik: '0',
    fundName: 'Test',
    filingDate: '2026-01-01',
    quarter: '2025Q4',
    positions,
    totalValue: positions.reduce((s, p) => s + p.value, 0),
  });

  it('classifies NEW / CLOSED / ADDED / REDUCED / UNCHANGED correctly', () => {
    const prev = buildFund([
      { cusip: 'A', shares: 1000, value: 100 },     // → REDUCED (shares 1000→500, -50%)
      { cusip: 'B', shares: 500, value: 50 },       // → CLOSED
      { cusip: 'C', shares: 1000, value: 100 },     // → UNCHANGED (shares 1000→1050, +5%)
      { cusip: 'D', shares: 500, value: 50 },       // → ADDED (shares 500→800, +60%)
    ]);
    const cur = buildFund([
      { cusip: 'A', shares: 500, value: 50 },
      { cusip: 'C', shares: 1050, value: 105 },
      { cusip: 'D', shares: 800, value: 80 },
      { cusip: 'E', shares: 200, value: 20 },       // → NEW
    ]);
    const changes = computePositionChanges(cur, prev);
    const byKind = changes.reduce<Record<string, string[]>>((acc, c) => {
      acc[c.kind] ||= [];
      acc[c.kind].push(c.cusip);
      return acc;
    }, {});
    expect(byKind.NEW).toEqual(['E']);
    expect(byKind.CLOSED).toEqual(['B']);
    expect(byKind.ADDED).toEqual(['D']);
    expect(byKind.REDUCED).toEqual(['A']);
    expect(byKind.UNCHANGED).toEqual(['C']);
  });

  it('respects cusipFilter', () => {
    const prev = buildFund([{ cusip: 'A', shares: 100, value: 10 }]);
    const cur = buildFund([{ cusip: 'A', shares: 200, value: 20 }, { cusip: 'B', shares: 50, value: 5 }]);
    const filter = new Set(['A']);
    const changes = computePositionChanges(cur, prev, filter);
    expect(changes).toHaveLength(1);
    expect(changes[0].cusip).toBe('A');
  });
});

describe('computeNasdaqMegacapFlow (Phase 2)', () => {
  const AAPL = NASDAQ_MEGACAP_CUSIPS.AAPL;
  const MSFT = NASDAQ_MEGACAP_CUSIPS.MSFT;
  const OTHER = '345370860';

  const build = (name: string, cur: any[], prev: any[] | null): FundQuarterlyPositions => ({
    cik: '0',
    fundName: name,
    current: {
      cik: '0', fundName: name, filingDate: '2026-02-14', quarter: '2025Q4',
      positions: cur.map((p) => ({ ...p, shares: 0 })),
      totalValue: cur.reduce((s, p) => s + p.value, 0),
    },
    previous: prev ? {
      cik: '0', fundName: name, filingDate: '2025-11-14', quarter: '2025Q3',
      positions: prev.map((p) => ({ ...p, shares: 0 })),
      totalValue: prev.reduce((s, p) => s + p.value, 0),
    } : null,
  });

  it('returns null when fewer than 3 funds have previous data', () => {
    const quarterly = [
      build('F1', [{ cusip: AAPL, value: 100 }], [{ cusip: AAPL, value: 100 }]),
      build('F2', [{ cusip: AAPL, value: 100 }], null), // no previous
    ];
    expect(computeNasdaqMegacapFlow(quarterly)).toBeNull();
  });

  it('level=+2 when delta > +2%p', () => {
    const quarterly = [1, 2, 3].map((i) =>
      build(`F${i}`,
        [{ cusip: AAPL, value: 30 }, { cusip: OTHER, value: 70 }], // current 30%
        [{ cusip: AAPL, value: 20 }, { cusip: OTHER, value: 80 }], // previous 20%
      )
    );
    const result = computeNasdaqMegacapFlow(quarterly);
    expect(result).not.toBeNull();
    expect(result!.deltaPct).toBeCloseTo(10, 1);
    expect(result!.level).toBe(2);
  });

  it('level=-1 when delta slightly negative (-0.5%p to -2%p)', () => {
    const quarterly = [1, 2, 3].map((i) =>
      build(`F${i}`,
        [{ cusip: AAPL, value: 19 }, { cusip: OTHER, value: 81 }], // 19%
        [{ cusip: AAPL, value: 20 }, { cusip: OTHER, value: 80 }], // 20%
      )
    );
    const result = computeNasdaqMegacapFlow(quarterly);
    expect(result!.deltaPct).toBeCloseTo(-1, 1);
    expect(result!.level).toBe(-1);
  });

  it('level=0 when |delta| <= 0.5%p', () => {
    const quarterly = [1, 2, 3].map((i) =>
      build(`F${i}`,
        [{ cusip: AAPL, value: 20 }, { cusip: OTHER, value: 80 }],
        [{ cusip: AAPL, value: 20 }, { cusip: OTHER, value: 80 }],
      )
    );
    const result = computeNasdaqMegacapFlow(quarterly);
    expect(result!.level).toBe(0);
  });
});
