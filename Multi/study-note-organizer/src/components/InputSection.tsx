import { useRef, useEffect } from 'react';
import { Send } from 'lucide-react';
import { useAppContext } from '../context/AppContext';
import { todayISO, formatDate } from '../utils/formatters';
import { MAX_INPUT_CHARS } from '../utils/constants';
import LoadingSpinner from './LoadingSpinner';

interface Props {
  onSubmit: () => void;
  isLoading: boolean;
}

export default function InputSection({ onSubmit, isLoading }: Props) {
  const { state, dispatch } = useAppContext();
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  function autoResize() {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${ta.scrollHeight}px`;
  }

  useEffect(() => {
    autoResize();
  }, [state.inputText]);

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      if (!isLoading) onSubmit();
    }
  }

  const charCount = state.inputText.length;
  const isOverLimit = charCount > MAX_INPUT_CHARS;
  const canSubmit = !isLoading && charCount > 0 && !isOverLimit;

  return (
    <div className="rounded-2xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800">
      <div className="mb-4 flex items-center gap-4">
        <div>
          <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">날짜</label>
          <input
            type="date"
            value={state.inputDate}
            max={todayISO()}
            onChange={(e) => dispatch({ type: 'SET_INPUT_DATE', payload: e.target.value })}
            className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-800 dark:border-gray-600 dark:bg-gray-700 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
        </div>
        {state.inputDate && (
          <div className="mt-5 text-sm font-medium text-blue-600 dark:text-blue-400">
            {formatDate(state.inputDate)}
          </div>
        )}
      </div>

      <div className="relative">
        <textarea
          ref={textareaRef}
          value={state.inputText}
          onChange={(e) => dispatch({ type: 'SET_INPUT_TEXT', payload: e.target.value })}
          onKeyDown={handleKeyDown}
          placeholder="오늘 수업/회의 내용을 붙여넣으세요"
          rows={6}
          className="w-full resize-none rounded-xl border border-gray-300 bg-gray-50 px-4 py-3 text-sm text-gray-800 placeholder-gray-400 focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-blue-500/20 dark:border-gray-600 dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 dark:focus:bg-gray-700 transition-colors"
          style={{ minHeight: '120px' }}
        />
        <span
          className={`absolute bottom-2 right-3 text-xs ${isOverLimit ? 'text-red-500' : 'text-gray-400'}`}
        >
          {charCount.toLocaleString()}/{MAX_INPUT_CHARS.toLocaleString()}자
        </span>
      </div>

      <div className="mt-3 flex items-center justify-between">
        <p className="text-xs text-gray-400 dark:text-gray-500">
          Ctrl+Enter로 빠르게 정리
        </p>
        <button
          onClick={onSubmit}
          disabled={!canSubmit}
          className="flex items-center gap-2 rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50 transition-all"
        >
          {isLoading ? (
            <>
              <LoadingSpinner size="sm" />
              정리 중...
            </>
          ) : (
            <>
              <Send className="h-4 w-4" />
              정리하기
            </>
          )}
        </button>
      </div>
    </div>
  );
}
