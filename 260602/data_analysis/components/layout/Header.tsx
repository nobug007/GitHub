'use client';

import { FileSpreadsheet } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { useDataStore } from '@/store/dataStore';

export function Header() {
  const { data, fileName } = useDataStore();

  return (
    <header className="h-14 border-b bg-white flex items-center px-4 gap-3 shrink-0">
      <div className="flex items-center gap-2 font-semibold text-gray-800">
        <FileSpreadsheet className="w-5 h-5 text-blue-600" />
        <span>데이터 분석기</span>
      </div>

      <div className="w-px h-5 bg-gray-200 mx-1" />

      {fileName ? (
        <div className="flex items-center gap-2 text-sm text-gray-600">
          <span className="font-medium text-gray-800">{fileName}</span>
          {data && (
            <>
              <Badge variant="secondary">{data.rows.length.toLocaleString()} 행</Badge>
              <Badge variant="secondary">{data.columns.length} 열</Badge>
            </>
          )}
        </div>
      ) : (
        <span className="text-sm text-gray-400">업로드된 파일 없음</span>
      )}
    </header>
  );
}
