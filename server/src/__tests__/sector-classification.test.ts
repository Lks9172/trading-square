import { classifySector, getSectorDefinition, listSectorDefinitions } from '../engines/sector-classification';

describe('sector-classification', () => {
  it('classifies major sectors into cyclical/structural/defensive buckets', () => {
    expect(classifySector('SECTOR_XLK')).toBe('structural');
    expect(classifySector('SECTOR_XLI')).toBe('cyclical');
    expect(classifySector('SECTOR_XLV')).toBe('defensive');
  });

  it('includes newly added proxies in expected classifications', () => {
    expect(classifySector('SECTOR_XLP')).toBe('defensive');
    expect(classifySector('SECTOR_ITA')).toBe('structural');
    expect(classifySector('SECTOR_GRID')).toBe('structural');
    expect(classifySector('SECTOR_IGF')).toBe('structural');
  });

  it('includes semiconductor proxies in structural sector definitions', () => {
    const keys = listSectorDefinitions().map((item) => item.key);
    expect(keys).toContain('SECTOR_SOXX');
    expect(keys).toContain('SECTOR_SMH');
    expect(getSectorDefinition('SECTOR_SOXX')?.label).toBe('반도체');
  });
});
