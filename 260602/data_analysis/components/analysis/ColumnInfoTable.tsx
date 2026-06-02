'use client';

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { ScrollArea } from '@/components/ui/scroll-area';
import { cn } from '@/lib/utils';
import type { ColumnStats, ColumnType, DatasetSummary } from '@/lib/analysis';

interface Props {
  summary: DatasetSummary;
}

const TYPE_META: Record<ColumnType, { label: string; className: string }> = {
  numeric:     { label: '연속형',  className: 'bg-blue-100 text-blue-700' },
  categorical: { label: '범주형',  className: 'bg-violet-100 text-violet-700' },
  datetime:    { label: '날짜형',  className: 'bg-emerald-100 text-emerald-700' },
};

function MissingCell({ count, ratio }: { count: number; ratio: number }) {
  const pct = (ratio * 100).toFixed(1) + '%';
  const cellClass =
    ratio === 0
      ? 'text-gray-400'
      : ratio < 0.05
      ? 'bg-yellow-50 text-yellow-700 font-medium'
      : ratio < 0.2
      ? 'bg-orange-100 text-orange-700 font-semibold'
      : 'bg-red-100 text-red-700 font-semibold';

  return (
    <div className={cn('rounded px-1.5 py-0.5 text-xs inline-flex items-center gap-1.5', cellClass)}>
      <span>{count.toLocaleString('ko-KR')}</span>
      <span className="opacity-70">({pct})</span>
    </div>
  );
}

export function ColumnInfoTable({ summary }: Props) {
  return (
    <div className="rounded-xl border bg-white shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b">
        <h3 className="font-semibold text-gray-800">컬럼 정보</h3>
        <p className="text-xs text-gray-500 mt-0.5">각 컬럼의 데이터 타입, 고유값, 결측치 현황</p>
      </div>
      <ScrollArea className="max-h-[440px]">
        <Table>
          <TableHeader>
            <TableRow className="bg-gray-50 hover:bg-gray-50">
              <TableHead className="w-8 text-center">#</TableHead>
              <TableHead>컬럼명</TableHead>
              <TableHead className="text-center">데이터 타입</TableHead>
              <TableHead className="text-right">고유값</TableHead>
              <TableHead className="text-center">결측치 (비율)</TableHead>
              <TableHead>데이터 범위 / 예시</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {summary.columns.map((col, idx) => {
              const typeMeta = TYPE_META[col.type];
              return (
                <TableRow key={col.name} className="align-middle">
                  <TableCell className="text-center text-xs text-gray-400 font-mono">
                    {idx + 1}
                  </TableCell>
                  <TableCell className="font-medium text-sm text-gray-800 max-w-[160px]">
                    <span className="truncate block" title={col.name}>{col.name}</span>
                  </TableCell>
                  <TableCell className="text-center">
                    <span className={cn('text-xs font-medium px-2 py-0.5 rounded-full', typeMeta.className)}>
                      {typeMeta.label}
                    </span>
                  </TableCell>
                  <TableCell className="text-right text-sm text-gray-600">
                    {col.uniqueCount.toLocaleString('ko-KR')}
                  </TableCell>
                  <TableCell className="text-center">
                    <MissingCell count={col.missingCount} ratio={col.missingRatio} />
                  </TableCell>
                  <TableCell className="text-sm text-gray-500 max-w-[280px]">
                    <span className="truncate block" title={col.rangeOrExamples}>
                      {col.rangeOrExamples || '—'}
                    </span>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </ScrollArea>
    </div>
  );
}
