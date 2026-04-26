import {
  DEFAULT_MANUAL_INPUTS,
  derivePolicyDirectionFromSeries,
  mergeEffectiveManualInputs,
} from '../services/policy-inputs';

describe('policy input helpers', () => {
  it('merges auto inputs when policy controls are still default', () => {
    const merged = mergeEffectiveManualInputs(
      { ...DEFAULT_MANUAL_INPUTS, ismPmi: 48 },
      { policyDirection: 2, geoRisk: 3, cbBuying: false, ismPmi: null },
      DEFAULT_MANUAL_INPUTS,
    );

    expect(merged).toEqual({
      policyDirection: 2,
      geoRisk: 3,
      cbBuying: false,
      ismPmi: 48,
    });
  });

  it('keeps explicit manual controls when user overrode policy inputs', () => {
    const manual = { policyDirection: -1, geoRisk: 4, cbBuying: true, ismPmi: 47 };
    const merged = mergeEffectiveManualInputs(
      manual,
      { policyDirection: 2, geoRisk: 1, cbBuying: false, ismPmi: 52 },
      DEFAULT_MANUAL_INPUTS,
    );

    expect(merged).toBe(manual);
  });

  it('derives easing policy when EFFR falls and jobless claims rise', () => {
    const effr = Array.from({ length: 30 }, (_, i) => ({ value: i < 10 ? 4.0 : 5.0 }));
    const curve = [{ value: 0.2 }];
    const icsa = [
      { value: 260000 }, { value: 258000 }, { value: 255000 }, { value: 252000 },
      { value: 240000 }, { value: 238000 }, { value: 235000 }, { value: 232000 },
    ];

    expect(derivePolicyDirectionFromSeries(effr, curve, icsa)).toBeGreaterThan(0);
  });
});

