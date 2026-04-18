import { parseInfotable, computeNasdaqMegacapExposure, NASDAQ_MEGACAP_CUSIPS, FundPositions } from '../collectors/institutional-13f';

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
