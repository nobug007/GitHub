import { useState } from 'react';
import { X, Eye, EyeOff, AlertTriangle } from 'lucide-react';
import { useAppContext } from '../context/AppContext';
import { MODEL_OPTIONS } from '../utils/constants';

export default function SettingsPanel() {
  const { state, dispatch } = useAppContext();
  const { settings, isSettingsOpen } = state;
  const [localKey, setLocalKey] = useState(settings.apiKey);
  const [showKey, setShowKey] = useState(false);
  const [localB1, setLocalB1] = useState(settings.baseModel1);
  const [localB2, setLocalB2] = useState(settings.baseModel2);
  const [localMerge, setLocalMerge] = useState(settings.mergeModel);
  const [saved, setSaved] = useState(false);

  function save() {
    dispatch({
      type: 'SET_SETTINGS',
      payload: { apiKey: localKey.trim(), baseModel1: localB1, baseModel2: localB2, mergeModel: localMerge },
    });
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  const hasFreePick = [localB1, localB2, localMerge].some(
    (id) => MODEL_OPTIONS.find((m) => m.id === id)?.isFree,
  );

  const providers = Array.from(new Set(MODEL_OPTIONS.map((m) => m.provider)));

  function ModelSelect({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
    return (
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">{label}</label>
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-800 dark:border-gray-600 dark:bg-gray-700 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          {providers.map((prov) => (
            <optgroup key={prov} label={prov}>
              {MODEL_OPTIONS.filter((m) => m.provider === prov).map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}{m.isFree ? ' ⚠️' : ''}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      </div>
    );
  }

  if (!isSettingsOpen) return null;

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-black/30 backdrop-blur-sm"
        onClick={() => dispatch({ type: 'CLOSE_PANELS' })}
      />
      <aside className="fixed right-0 top-0 z-50 flex h-full w-full max-w-sm flex-col bg-white shadow-2xl dark:bg-gray-900 animate-slideIn">
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-4 dark:border-gray-700">
          <h2 className="text-base font-semibold text-gray-900 dark:text-white">설정</h2>
          <button
            onClick={() => dispatch({ type: 'CLOSE_PANELS' })}
            className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-5">
          {hasFreePick && (
            <div className="flex items-start gap-2 rounded-lg bg-yellow-50 px-3 py-2.5 text-xs text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              무료 모델은 품질이 낮거나 응답이 느릴 수 있습니다.
            </div>
          )}

          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">
              OpenRouter API Key
            </label>
            <div className="relative">
              <input
                type={showKey ? 'text' : 'password'}
                value={localKey}
                onChange={(e) => setLocalKey(e.target.value)}
                placeholder="sk-or-..."
                className="w-full rounded-lg border border-gray-300 bg-white py-2 pl-3 pr-10 text-sm text-gray-800 dark:border-gray-600 dark:bg-gray-700 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <button
                type="button"
                onClick={() => setShowKey(!showKey)}
                className="absolute right-2 top-2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
              >
                {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <p className="mt-1 text-xs text-gray-400">
              <a
                href="https://openrouter.ai/keys"
                target="_blank"
                rel="noopener noreferrer"
                className="underline hover:text-blue-500"
              >
                openrouter.ai/keys
              </a>에서 발급받으세요
            </p>
          </div>

          <ModelSelect value={localB1} onChange={setLocalB1} label="기본 모델 A" />
          <ModelSelect value={localB2} onChange={setLocalB2} label="기본 모델 B" />
          <ModelSelect value={localMerge} onChange={setLocalMerge} label="통합 모델" />
        </div>

        <div className="border-t border-gray-200 px-5 py-4 dark:border-gray-700">
          <button
            onClick={save}
            className="w-full rounded-xl bg-blue-600 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 transition-colors"
          >
            {saved ? '저장됨 ✓' : '저장'}
          </button>
        </div>
      </aside>
    </>
  );
}
