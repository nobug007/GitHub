'use client';

import { useRef, useState, useCallback, DragEvent } from 'react';
import { UploadCloud, FileText, AlertCircle, CheckCircle2, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { parseFile } from '@/lib/parsers';
import { useDataStore } from '@/store/dataStore';

type UploadState = 'idle' | 'dragging' | 'parsing' | 'success' | 'error';

const ACCEPTED_EXTENSIONS = ['.csv', '.xlsx', '.xls'];
const ACCEPTED_MIME = [
  'text/csv',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-excel',
];

function isValidFile(file: File) {
  const ext = '.' + file.name.split('.').pop()?.toLowerCase();
  return ACCEPTED_EXTENSIONS.includes(ext);
}

export function FileUpload() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [state, setState] = useState<UploadState>('idle');
  const [errorMsg, setErrorMsg] = useState('');
  const { setData, setActiveView } = useDataStore();

  const handleFile = useCallback(
    async (file: File) => {
      if (!isValidFile(file)) {
        setErrorMsg(`지원하지 않는 파일 형식입니다. CSV, XLSX, XLS 파일만 업로드할 수 있습니다.`);
        setState('error');
        return;
      }

      setState('parsing');
      setErrorMsg('');

      try {
        const parsed = await parseFile(file);
        if (parsed.columns.length === 0) {
          throw new Error('파일에 데이터가 없습니다. 헤더 행을 포함한 CSV/XLSX 파일을 업로드해 주세요.');
        }
        setData(parsed, file.name);
        setState('success');
        setTimeout(() => setActiveView('analysis'), 800);
      } catch (err) {
        setErrorMsg(err instanceof Error ? err.message : '파일 파싱 중 오류가 발생했습니다.');
        setState('error');
      }
    },
    [setData, setActiveView]
  );

  const onDrop = useCallback(
    (e: DragEvent<HTMLDivElement>) => {
      e.preventDefault();
      setState('idle');
      const file = e.dataTransfer.files[0];
      if (file) handleFile(file);
    },
    [handleFile]
  );

  const onInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) handleFile(file);
      e.target.value = '';
    },
    [handleFile]
  );

  return (
    <div className="flex flex-col items-center justify-center h-full">
      <div className="w-full max-w-xl">
        <h2 className="text-xl font-semibold text-gray-800 mb-1">파일 업로드</h2>
        <p className="text-sm text-gray-500 mb-6">CSV 또는 Excel 파일을 업로드해서 데이터를 분석합니다.</p>

        {/* Drop zone */}
        <div
          onDragOver={(e) => { e.preventDefault(); setState('dragging'); }}
          onDragLeave={() => setState('idle')}
          onDrop={onDrop}
          onClick={() => inputRef.current?.click()}
          className={cn(
            'relative flex flex-col items-center justify-center gap-4 rounded-xl border-2 border-dashed',
            'cursor-pointer transition-all duration-150 p-12',
            state === 'dragging'
              ? 'border-blue-500 bg-blue-50'
              : state === 'success'
              ? 'border-green-400 bg-green-50'
              : state === 'error'
              ? 'border-red-300 bg-red-50'
              : 'border-gray-300 bg-gray-50 hover:border-blue-400 hover:bg-blue-50/30'
          )}
        >
          {state === 'parsing' ? (
            <>
              <Loader2 className="w-12 h-12 text-blue-500 animate-spin" />
              <p className="text-sm font-medium text-blue-600">파일을 파싱하는 중...</p>
            </>
          ) : state === 'success' ? (
            <>
              <CheckCircle2 className="w-12 h-12 text-green-500" />
              <p className="text-sm font-medium text-green-700">파싱 완료! 분석 화면으로 이동합니다...</p>
            </>
          ) : (
            <>
              <UploadCloud
                className={cn(
                  'w-12 h-12 transition-colors',
                  state === 'dragging' ? 'text-blue-500' : 'text-gray-400'
                )}
              />
              <div className="text-center">
                <p className="text-sm font-medium text-gray-700">
                  파일을 여기에 드래그하거나{' '}
                  <span className="text-blue-600 underline underline-offset-2">클릭해서 선택</span>
                </p>
                <p className="text-xs text-gray-400 mt-1">CSV, XLSX, XLS 지원 · 최대 100MB</p>
              </div>
              <div className="flex gap-2">
                {['.csv', '.xlsx', '.xls'].map((ext) => (
                  <span
                    key={ext}
                    className="px-2 py-0.5 text-xs font-mono rounded bg-gray-200 text-gray-600"
                  >
                    {ext}
                  </span>
                ))}
              </div>
            </>
          )}
        </div>

        {/* Error message */}
        {state === 'error' && errorMsg && (
          <div className="mt-4 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3">
            <AlertCircle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-sm text-red-700">{errorMsg}</p>
          </div>
        )}

        {/* File select button */}
        {(state === 'idle' || state === 'error') && (
          <button
            onClick={() => inputRef.current?.click()}
            className="mt-6 w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white
                       hover:bg-blue-700 active:bg-blue-800 transition-colors"
          >
            <FileText className="inline w-4 h-4 mr-2 -mt-0.5" />
            파일 선택
          </button>
        )}

        <input
          ref={inputRef}
          type="file"
          accept=".csv,.xlsx,.xls"
          className="hidden"
          onChange={onInputChange}
        />
      </div>
    </div>
  );
}
