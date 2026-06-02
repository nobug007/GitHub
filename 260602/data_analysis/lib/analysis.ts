// ─── Types ──────────────────────────────────────────────────────────────────

export type ColumnType = 'numeric' | 'categorical' | 'datetime';

export interface ColumnStats {
  name: string;
  type: ColumnType;
  uniqueCount: number;
  missingCount: number;
  missingRatio: number;
  rangeOrExamples: string;
}

export interface DatasetSummary {
  rowCount: number;
  columnCount: number;
  totalMissing: number;
  missingRatio: number;
  columns: ColumnStats[];
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

export function isMissing(v: unknown): boolean {
  return v === null || v === undefined || v === '';
}

function isNumericValue(v: unknown): boolean {
  if (isMissing(v)) return false;
  const n = Number(v);
  return !isNaN(n) && isFinite(n);
}

const DATE_PATTERNS = [
  /^\d{4}-\d{2}-\d{2}/,             // 2024-01-15
  /^\d{4}\/\d{2}\/\d{2}/,           // 2024/01/15
  /^\d{2}\/\d{2}\/\d{4}/,           // 15/01/2024
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/, // ISO datetime
  /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/, // 2024-01-15 12:00
];

function isDateValue(v: unknown): boolean {
  if (isMissing(v)) return false;
  const str = String(v).trim();
  if (!DATE_PATTERNS.some((p) => p.test(str))) return false;
  const d = new Date(str);
  return !isNaN(d.getTime()) && d.getFullYear() > 1900 && d.getFullYear() < 2200;
}

// ─── Type detection (pure, easy to unit-test) ─────────────────────────────────

export function detectColumnType(values: unknown[]): ColumnType {
  const nonNull = values.filter((v) => !isMissing(v));
  if (nonNull.length === 0) return 'categorical';

  const numericRatio = nonNull.filter(isNumericValue).length / nonNull.length;
  if (numericRatio >= 0.7) return 'numeric';

  const dateRatio = nonNull.filter(isDateValue).length / nonNull.length;
  if (dateRatio >= 0.7) return 'datetime';

  return 'categorical';
}

// ─── Column statistics ────────────────────────────────────────────────────────

export function computeColumnStats(
  name: string,
  values: unknown[],
  totalRows: number
): ColumnStats {
  const missingCount = values.filter(isMissing).length;
  const missingRatio = totalRows > 0 ? missingCount / totalRows : 0;
  const nonNull = values.filter((v) => !isMissing(v));
  const uniqueCount = new Set(nonNull.map(String)).size;
  const type = detectColumnType(values);

  let rangeOrExamples = '';

  if (type === 'numeric') {
    const nums = nonNull.map(Number).filter((n) => !isNaN(n));
    if (nums.length > 0) {
      const min = Math.min(...nums);
      const max = Math.max(...nums);
      const mean = nums.reduce((a, b) => a + b, 0) / nums.length;
      const fmt = (n: number) =>
        n.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
      rangeOrExamples = `${fmt(min)} ~ ${fmt(max)}  (평균 ${fmt(mean)})`;
    }
  } else if (type === 'datetime') {
    const dates = nonNull
      .map((v) => new Date(String(v)))
      .filter((d) => !isNaN(d.getTime()))
      .sort((a, b) => a.getTime() - b.getTime());
    if (dates.length > 0) {
      const fmt = (d: Date) => d.toLocaleDateString('ko-KR');
      rangeOrExamples = `${fmt(dates[0])} ~ ${fmt(dates[dates.length - 1])}`;
    }
  } else {
    // categorical: top-5 by frequency
    const freq: Record<string, number> = {};
    nonNull.forEach((v) => {
      const key = String(v);
      freq[key] = (freq[key] ?? 0) + 1;
    });
    const top = Object.entries(freq)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([k]) => k);
    rangeOrExamples = top.join(', ') + (uniqueCount > 5 ? ' ...' : '');
  }

  return { name, type, uniqueCount, missingCount, missingRatio, rangeOrExamples };
}

// ─── Full dataset analysis ────────────────────────────────────────────────────

export function analyzeData(
  columns: string[],
  rows: Record<string, unknown>[]
): DatasetSummary {
  const totalRows = rows.length;

  const columnStats = columns.map((name) => {
    const values = rows.map((row) => row[name]);
    return computeColumnStats(name, values, totalRows);
  });

  const totalMissing = columnStats.reduce((sum, c) => sum + c.missingCount, 0);
  const totalCells = totalRows * columns.length;
  const missingRatio = totalCells > 0 ? totalMissing / totalCells : 0;

  return {
    rowCount: totalRows,
    columnCount: columns.length,
    totalMissing,
    missingRatio,
    columns: columnStats,
  };
}
