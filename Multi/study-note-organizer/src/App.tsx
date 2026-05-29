import { useState, useCallback } from 'react';
import { useAppContext } from './context/AppContext';
import Header from './components/Header';
import InputSection from './components/InputSection';
import ResultSection from './components/ResultSection';
import SettingsPanel from './components/SettingsPanel';
import HistoryPanel from './components/HistoryPanel';
import Toast, { type ToastMessage } from './components/Toast';
import { callOpenRouter } from './hooks/useOpenRouter';
import { buildBasePrompt, buildMergePrompt } from './utils/prompts';
import { getDateParts, extractKeywords, generateId, formatDate } from './utils/formatters';
import { MODEL_OPTIONS } from './utils/constants';
import type { ModelResult, FinalResult } from './types';
import { RefreshCw } from 'lucide-react';

function makeLoading(modelId: string): ModelResult {
  const modelName = MODEL_OPTIONS.find((m) => m.id === modelId)?.name ?? modelId;
  return { modelId, modelName, content: '', latencyMs: 0, status: 'loading' };
}

export default function App() {
  const { state, dispatch } = useAppContext();
  const { settings, inputDate, inputText, baseResult1, baseResult2, finalResult } = state;
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [isRunning, setIsRunning] = useState(false);

  function addToast(text: string, type: ToastMessage['type'] = 'success') {
    const id = generateId();
    setToasts((prev) => [...prev, { id, text, type }]);
  }

  function removeToast(id: string) {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }

  function requireApiKey(): boolean {
    if (!settings.apiKey) {
      addToast('설정에서 API Key를 입력하세요', 'warning');
      dispatch({ type: 'TOGGLE_SETTINGS' });
      return false;
    }
    return true;
  }

  const runMerge = useCallback(
    async (r1: ModelResult, r2: ModelResult) => {
      if (r1.status !== 'success' && r2.status !== 'success') return;

      const mergeInput =
        r1.status === 'success' && r2.status === 'success'
          ? buildMergePrompt(r1.content, r2.content)
          : r1.status === 'success'
          ? r1.content
          : r2.content;

      const mergeModelName = MODEL_OPTIONS.find((m) => m.id === settings.mergeModel)?.name ?? settings.mergeModel;
      const loadingFinal: FinalResult = {
        modelId: settings.mergeModel,
        modelName: mergeModelName,
        content: '',
        latencyMs: 0,
        status: 'loading',
        keywords: ['정리', '수업'],
      };
      dispatch({ type: 'SET_FINAL_RESULT', payload: loadingFinal });

      const raw = await callOpenRouter(settings.apiKey, settings.mergeModel, mergeInput);
      const keywords = raw.status === 'success' ? extractKeywords(raw.content) : (['정리', '수업'] as [string, string]);
      const final: FinalResult = { ...raw, keywords };
      dispatch({ type: 'SET_FINAL_RESULT', payload: final });

      if (raw.status === 'success') {
        const histItem = {
          id: generateId(),
          date: inputDate,
          formattedDate: formatDate(inputDate),
          inputText,
          baseResult1: r1,
          baseResult2: r2,
          finalResult: final,
          timestamp: Date.now(),
        };
        dispatch({ type: 'ADD_HISTORY', payload: histItem });
      }
    },
    [settings.apiKey, settings.mergeModel, inputDate, inputText, dispatch],
  );

  async function handleSubmit() {
    if (!requireApiKey()) return;
    if (!inputText.trim()) return;
    setIsRunning(true);

    const { month, day, weekday } = getDateParts(inputDate);
    const prompt = buildBasePrompt(inputText, month, day, weekday);

    dispatch({ type: 'SET_BASE_RESULT1', payload: makeLoading(settings.baseModel1) });
    dispatch({ type: 'SET_BASE_RESULT2', payload: makeLoading(settings.baseModel2) });
    dispatch({ type: 'SET_FINAL_RESULT', payload: null });

    const hasFree = [settings.baseModel1, settings.baseModel2, settings.mergeModel].some(
      (id) => MODEL_OPTIONS.find((m) => m.id === id)?.isFree,
    );
    if (hasFree) addToast('무료 모델은 품질이 낮거나 응답이 느릴 수 있습니다.', 'warning');

    const [r1, r2] = await Promise.all([
      callOpenRouter(settings.apiKey, settings.baseModel1, prompt),
      callOpenRouter(settings.apiKey, settings.baseModel2, prompt),
    ]);

    dispatch({ type: 'SET_BASE_RESULT1', payload: r1 });
    dispatch({ type: 'SET_BASE_RESULT2', payload: r2 });

    await runMerge(r1, r2);
    setIsRunning(false);
  }

  async function retryModel1() {
    if (!requireApiKey()) return;
    const { month, day, weekday } = getDateParts(inputDate);
    dispatch({ type: 'SET_BASE_RESULT1', payload: makeLoading(settings.baseModel1) });
    const r1 = await callOpenRouter(settings.apiKey, settings.baseModel1, buildBasePrompt(inputText, month, day, weekday));
    dispatch({ type: 'SET_BASE_RESULT1', payload: r1 });
  }

  async function retryModel2() {
    if (!requireApiKey()) return;
    const { month, day, weekday } = getDateParts(inputDate);
    dispatch({ type: 'SET_BASE_RESULT2', payload: makeLoading(settings.baseModel2) });
    const r2 = await callOpenRouter(settings.apiKey, settings.baseModel2, buildBasePrompt(inputText, month, day, weekday));
    dispatch({ type: 'SET_BASE_RESULT2', payload: r2 });
  }

  async function retryMerge() {
    if (!requireApiKey()) return;
    await runMerge(baseResult1, baseResult2);
  }

  const hasAnyResult =
    baseResult1.status !== 'idle' ||
    baseResult2.status !== 'idle' ||
    finalResult !== null;

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950 transition-colors">
      <Header />

      <main className="mx-auto max-w-7xl px-4 pb-16 pt-6">
        <InputSection onSubmit={handleSubmit} isLoading={isRunning} />

        {hasAnyResult && (
          <div className="mt-5">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
                정리 결과
              </h2>
              <button
                onClick={handleSubmit}
                disabled={isRunning}
                className="flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-100 disabled:opacity-50 dark:border-gray-600 dark:text-gray-400 dark:hover:bg-gray-800 transition-colors"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${isRunning ? 'animate-spin' : ''}`} />
                전체 다시 생성
              </button>
            </div>
            <ResultSection
              baseResult1={baseResult1}
              baseResult2={baseResult2}
              finalResult={finalResult}
              inputDate={inputDate}
              onRetry1={retryModel1}
              onRetry2={retryModel2}
              onRetryMerge={retryMerge}
              onToast={addToast}
            />
          </div>
        )}
      </main>

      <SettingsPanel />
      <HistoryPanel />
      <Toast messages={toasts} onRemove={removeToast} />
    </div>
  );
}
