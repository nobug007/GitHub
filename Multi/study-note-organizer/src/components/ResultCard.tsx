import { useState } from 'react';
import { Copy, Download, RefreshCw, Zap, Hash } from 'lucide-react';
import type { ModelResult, FinalResult } from '../types';
import { PROVIDER_COLORS, MODEL_OPTIONS } from '../utils/constants';
import { removeKeywordLine, toYYMMDD } from '../utils/formatters';
import { downloadTxt } from '../utils/download';
import LoadingSpinner from './LoadingSpinner';
import ErrorDisplay from './ErrorDisplay';

interface Props {
  result: ModelResult | FinalResult;
  label: string;
  isMerge?: boolean;
  inputDate: string;
  onRetry: () => void;
  onToast: (text: string, type?: 'success' | 'warning' | 'error') => void;
  animDelay?: number;
}

function getProvider(modelId: string): string {
  return MODEL_OPTIONS.find((m) => m.id === modelId)?.provider ?? 'Other';
}

export default function ResultCard({ result, label, isMerge, inputDate, onRetry, onToast, animDelay = 0 }: Props) {
  const [copied, setCopied] = useState(false);

  const provider = getProvider(result.modelId);
  const badge = PROVIDER_COLORS[provider] ?? 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200';

  const displayContent = result.status === 'success'
    ? (isMerge ? removeKeywordLine(result.content) : result.content)
    : '';

  async function handleCopy() {
    if (!displayContent) return;
    await navigator.clipboard.writeText(displayContent);
    setCopied(true);
    onToast('클립보드에 복사되었습니다');
    setTimeout(() => setCopied(false), 2000);
  }

  function handleDownload() {
    if (!displayContent) return;
    const yymmdd = toYYMMDD(inputDate);
    let filename: string;
    if (isMerge && result.status === 'success') {
      const kws = (result as FinalResult).keywords;
      filename = `${yymmdd}_${kws[0]}_${kws[1]}.txt`;
    } else {
      const alias = result.modelName.replace(/\s+/g, '_').replace(/[^\w가-힣]/g, '');
      filename = `${yymmdd}_${alias}.txt`;
    }
    downloadTxt(filename, displayContent);
    onToast('파일이 다운로드되었습니다');
  }

  return (
    <div
      className="flex flex-col rounded-2xl border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800 animate-fadeIn"
      style={{ animationDelay: `${animDelay}ms`, animationFillMode: 'both' }}
    >
      {/* Card Header */}
      <div className="flex items-start justify-between border-b border-gray-100 p-4 dark:border-gray-700">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              {label}
            </span>
            {result.modelId && (
              <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${badge}`}>
                {provider}
              </span>
            )}
            {isMerge && (
              <span className="rounded-full bg-gradient-to-r from-blue-500 to-purple-500 px-2 py-0.5 text-xs font-medium text-white">
                통합
              </span>
            )}
          </div>
          <span className="text-sm font-medium text-gray-800 dark:text-gray-200 truncate max-w-[200px]">
            {result.modelName || '—'}
          </span>
        </div>
        <div className="flex flex-col items-end gap-1 shrink-0">
          {result.status === 'success' && result.latencyMs > 0 && (
            <div className="flex items-center gap-1 text-xs text-gray-400 dark:text-gray-500">
              <Zap className="h-3 w-3" />
              {(result.latencyMs / 1000).toFixed(1)}s
            </div>
          )}
          {result.status === 'success' && result.tokenUsage && (
            <div className="flex items-center gap-1 text-xs text-gray-400 dark:text-gray-500">
              <Hash className="h-3 w-3" />
              {result.tokenUsage.total.toLocaleString()} tok
            </div>
          )}
        </div>
      </div>

      {/* Card Body */}
      <div className="flex-1 p-4">
        {result.status === 'idle' && (
          <div className="flex items-center justify-center py-10 text-gray-300 dark:text-gray-600 text-sm">
            결과가 여기에 표시됩니다
          </div>
        )}
        {result.status === 'loading' && (
          <div className="flex flex-col items-center justify-center gap-3 py-10">
            <LoadingSpinner size="lg" />
            <p className="text-sm text-gray-400 dark:text-gray-500">정리 중...</p>
          </div>
        )}
        {result.status === 'error' && (
          <ErrorDisplay message={result.errorMessage ?? '알 수 없는 오류'} onRetry={onRetry} />
        )}
        {result.status === 'success' && (
          <pre className="whitespace-pre-wrap font-sans text-sm leading-relaxed text-gray-800 dark:text-gray-200">
            {displayContent}
          </pre>
        )}
      </div>

      {/* Card Footer */}
      {result.status === 'success' && (
        <div className="flex items-center gap-2 border-t border-gray-100 p-3 dark:border-gray-700">
          <button
            onClick={handleCopy}
            className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-700 transition-colors"
          >
            <Copy className="h-3.5 w-3.5" />
            {copied ? '복사됨!' : '복사'}
          </button>
          <button
            onClick={handleDownload}
            className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-700 transition-colors"
          >
            <Download className="h-3.5 w-3.5" />
            다운로드
          </button>
          <div className="flex-1" />
          <button
            onClick={onRetry}
            className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-blue-600 hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-900/30 transition-colors"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            다시 요청
          </button>
        </div>
      )}
    </div>
  );
}
