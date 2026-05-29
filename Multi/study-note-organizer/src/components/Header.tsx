import { Settings, History, Sun, Moon, BookOpen } from 'lucide-react';
import { useAppContext } from '../context/AppContext';

export default function Header() {
  const { state, dispatch } = useAppContext();
  const isDark = state.settings.theme === 'dark';

  function toggleTheme() {
    dispatch({ type: 'SET_SETTINGS', payload: { theme: isDark ? 'light' : 'dark' } });
  }

  return (
    <header className="sticky top-0 z-30 border-b border-gray-200 bg-white/80 backdrop-blur dark:border-gray-700 dark:bg-gray-900/80">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3">
        <div className="flex items-center gap-2">
          <BookOpen className="h-6 w-6 text-blue-600 dark:text-blue-400" />
          <h1 className="text-lg font-bold text-gray-900 dark:text-white">학습 일지 정리기</h1>
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={toggleTheme}
            className="rounded-lg p-2 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800 transition-colors"
            title={isDark ? '라이트 모드' : '다크 모드'}
          >
            {isDark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
          </button>
          <button
            onClick={() => dispatch({ type: 'TOGGLE_HISTORY' })}
            className="rounded-lg p-2 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800 transition-colors"
            title="히스토리"
          >
            <History className="h-5 w-5" />
          </button>
          <button
            onClick={() => dispatch({ type: 'TOGGLE_SETTINGS' })}
            className="rounded-lg p-2 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800 transition-colors"
            title="설정"
          >
            <Settings className="h-5 w-5" />
          </button>
        </div>
      </div>
    </header>
  );
}
