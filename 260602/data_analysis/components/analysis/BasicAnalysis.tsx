'use client';

import { useMemo } from 'react';
import { useDataStore } from '@/store/dataStore';
import { analyzeData } from '@/lib/analysis';
import { SummaryCards } from './SummaryCards';
import { ColumnInfoTable } from './ColumnInfoTable';
import { DataPreview } from './DataPreview';

export function BasicAnalysis() {
  const { data, fileName } = useDataStore();

  const summary = useMemo(() => {
    if (!data) return null;
    return analyzeData(data.columns, data.rows);
  }, [data]);

  if (!data || !summary) return null;

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold text-gray-800">기본 데이터 분석</h2>
        <p className="text-sm text-gray-500 mt-0.5">{fileName}</p>
      </div>
      <SummaryCards summary={summary} />
      <ColumnInfoTable summary={summary} />
      <DataPreview data={data} />
    </div>
  );
}
