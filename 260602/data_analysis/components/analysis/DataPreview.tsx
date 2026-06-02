'use client';

import { ScrollArea, ScrollBar } from '@/components/ui/scroll-area';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { isMissing } from '@/lib/analysis';
import type { ParsedData } from '@/lib/types';

const PREVIEW_ROWS = 10;

interface Props {
  data: ParsedData;
}

export function DataPreview({ data }: Props) {
  const previewRows = data.rows.slice(0, PREVIEW_ROWS);

  return (
    <div className="rounded-xl border bg-white shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b flex items-center justify-between">
        <div>
          <h3 className="font-semibold text-gray-800">데이터 미리보기</h3>
          <p className="text-xs text-gray-500 mt-0.5">
            상위 {PREVIEW_ROWS}행 표시 (전체 {data.rows.length.toLocaleString('ko-KR')}행)
          </p>
        </div>
      </div>
      <ScrollArea className="w-full">
        <div className="min-w-max">
          <Table>
            <TableHeader>
              <TableRow className="bg-gray-50 hover:bg-gray-50">
                <TableHead className="w-10 text-center text-gray-400 font-mono text-xs sticky left-0 bg-gray-50">
                  #
                </TableHead>
                {data.columns.map((col) => (
                  <TableHead key={col} className="whitespace-nowrap text-sm min-w-[100px] max-w-[200px]">
                    {col}
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {previewRows.map((row, rowIdx) => (
                <TableRow key={rowIdx}>
                  <TableCell className="text-center text-xs text-gray-400 font-mono sticky left-0 bg-white">
                    {rowIdx + 1}
                  </TableCell>
                  {data.columns.map((col) => {
                    const val = row[col];
                    const missing = isMissing(val);
                    return (
                      <TableCell
                        key={col}
                        className="text-sm whitespace-nowrap max-w-[200px]"
                      >
                        {missing ? (
                          <span className="text-gray-300 italic text-xs">null</span>
                        ) : (
                          <span className="truncate block" title={String(val)}>
                            {String(val)}
                          </span>
                        )}
                      </TableCell>
                    );
                  })}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
        <ScrollBar orientation="horizontal" />
      </ScrollArea>
    </div>
  );
}
