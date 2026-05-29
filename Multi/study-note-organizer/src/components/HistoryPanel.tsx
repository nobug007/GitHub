import { X, Trash2, RotateCcw } from 'lucide-react';
import { useAppContext } from '../context/AppContext';
import { formatDate } from '../utils/formatters';

export default function HistoryPanel() {
  const { state, dispatch } = useAppContext();
  const { history, isHistoryOpen } = state;

  if (!isHistoryOpen) return null;

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-black/30 backdrop-blur-sm"
        onClick={() => dispatch({ type: 'CLOSE_PANELS' })}
      />
      <aside className="fixed left-0 top-0 z-50 flex h-full w-full max-w-sm flex-col bg-white shadow-2xl dark:bg-gray-900 animate-slideInLeft">
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-4 dark:border-gray-700">
          <h2 className="text-base font-semibold text-gray-900 dark:text-white">
            히스토리 ({history.length})
          </h2>
          <div className="flex items-center gap-1">
            {history.length > 0 && (
              <button
                onClick={() => dispatch({ type: 'CLEAR_HISTORY' })}
                className="rounded-lg px-2 py-1.5 text-xs text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 transition-colors"
              >
                전체 삭제
              </button>
            )}
            <button
              onClick={() => dispatch({ type: 'CLOSE_PANELS' })}
              className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto">
          {history.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-48 text-gray-400 dark:text-gray-600 text-sm">
              저장된 히스토리가 없습니다
            </div>
          ) : (
            <ul className="divide-y divide-gray-100 dark:divide-gray-700">
              {history.map((item) => (
                <li key={item.id} className="group flex items-start gap-3 px-5 py-3 hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors">
                  <button
                    onClick={() => dispatch({ type: 'RESTORE_HISTORY_ITEM', payload: item })}
                    className="flex-1 text-left"
                  >
                    <p className="text-sm font-medium text-gray-800 dark:text-gray-200">
                      {formatDate(item.date)}
                    </p>
                    {item.finalResult.keywords && (
                      <p className="mt-0.5 text-xs text-blue-500 dark:text-blue-400">
                        #{item.finalResult.keywords[0]} #{item.finalResult.keywords[1]}
                      </p>
                    )}
                    <p className="mt-1 text-xs text-gray-400 truncate">
                      {item.inputText.slice(0, 50)}
                      {item.inputText.length > 50 ? '...' : ''}
                    </p>
                  </button>
                  <div className="flex shrink-0 items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      onClick={() => dispatch({ type: 'RESTORE_HISTORY_ITEM', payload: item })}
                      className="rounded p-1 text-gray-400 hover:text-blue-500 transition-colors"
                      title="복원"
                    >
                      <RotateCcw className="h-3.5 w-3.5" />
                    </button>
                    <button
                      onClick={() => dispatch({ type: 'REMOVE_HISTORY', payload: item.id })}
                      className="rounded p-1 text-gray-400 hover:text-red-500 transition-colors"
                      title="삭제"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>
    </>
  );
}
