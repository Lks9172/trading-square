import { parseGuidanceValue, summarizeGuidance } from '../collectors/sec/filing-detail';

describe('filing detail guidance parsing', () => {
  it('parses natural language percent bands', () => {
    const lowDouble = parseGuidanceValue('revenue growth expected in the low double digits');
    expect(lowDouble?.unit).toBe('percent');
    expect(lowDouble?.min).toBe(10);
    expect(lowDouble?.max).toBe(13);

    const midForties = parseGuidanceValue('gross margin is expected in the mid-40s');
    expect(midForties?.unit).toBe('percent');
    expect(midForties?.min).toBe(43);
    expect(midForties?.max).toBe(46);
  });

  it('parses between/approximately usd ranges', () => {
    const value = parseGuidanceValue('capex between $3 and $4 billion this year');
    expect(value?.unit).toBe('usd');
    expect(value?.min).toBe(3_000_000_000);
    expect(value?.max).toBe(4_000_000_000);

    const approx = parseGuidanceValue('free cash flow around $2.5 billion');
    expect(approx?.unit).toBe('usd');
    expect(approx?.min).toBe(2_500_000_000);
    expect(approx?.max).toBe(2_500_000_000);
  });

  it('parses bounded directional guidance phrases', () => {
    const atLeast = parseGuidanceValue('operating margin at least 15%');
    expect(atLeast?.unit).toBe('percent');
    expect(atLeast?.min).toBe(15);
    expect(atLeast?.max).toBeNull();

    const upto = parseGuidanceValue('capex up to $750 million');
    expect(upto?.unit).toBe('usd');
    expect(upto?.min).toBeNull();
    expect(upto?.max).toBe(750_000_000);
  });

  it('summarizes raised / affirmed guidance blocks', () => {
    const summary = summarizeGuidance('The company raised revenue guidance to low double digits, affirmed gross margin in the mid-40s and expects capex between $3 and $4 billion.');
    expect(summary.stance).toBe('raised');
    expect(summary.revenue).toBe('raised');
    expect(summary.revenueValue?.unit).toBe('percent');
    expect(summary.marginValue?.unit).toBe('percent');
    expect(summary.capexValue?.unit).toBe('usd');
  });
});
