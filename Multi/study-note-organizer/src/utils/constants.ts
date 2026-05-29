import type { ModelOption, AppSettings } from '../types';

export const MODEL_OPTIONS: ModelOption[] = [
  { id: 'deepseek/deepseek-v4-flash', name: 'DeepSeek V4 Flash', provider: 'DeepSeek', isFree: false },
  { id: 'tencent/hy3-preview', name: 'Tencent Hy3', provider: 'Tencent', isFree: false },
  { id: 'anthropic/claude-opus-4-7', name: 'Claude Opus 4.7', provider: 'Anthropic', isFree: false },
  { id: 'anthropic/claude-sonnet-4-6', name: 'Claude Sonnet 4.6', provider: 'Anthropic', isFree: false },
  { id: 'google/gemini-3-flash-preview', name: 'Gemini 3 Flash', provider: 'Google', isFree: false },
  { id: 'moonshotai/kimi-k2.6', name: 'Kimi K2.6', provider: 'Moonshot', isFree: false },
  { id: 'qwen/qwen3.6-plus', name: 'Qwen 3.6 Plus', provider: 'Qwen', isFree: false },
  { id: 'qwen/qwen3.5-flash-02-23', name: 'Qwen 3.5 Flash', provider: 'Qwen', isFree: false },
  { id: 'openai/gpt-5.5', name: 'GPT-5.5', provider: 'OpenAI', isFree: false },
  { id: 'openai/gpt-5-mini', name: 'GPT-5 Mini', provider: 'OpenAI', isFree: false },
  { id: 'openai/gpt-oss-120b:free', name: 'GPT-OSS 120B (Free)', provider: 'OpenAI', isFree: true },
  { id: 'z-ai/glm-4.5-air:free', name: 'GLM 4.5 Air (Free)', provider: 'Z-AI', isFree: true },
  { id: 'arcee-ai/trinity-large-thinking:free', name: 'Trinity Large (Free)', provider: 'Arcee', isFree: true },
];

export const DEFAULT_SETTINGS: AppSettings = {
  apiKey: '',
  baseModel1: 'anthropic/claude-sonnet-4-6',
  baseModel2: 'google/gemini-3-flash-preview',
  mergeModel: 'anthropic/claude-opus-4-7',
  theme: 'light',
};

export const PROVIDER_COLORS: Record<string, string> = {
  Anthropic: 'bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200',
  Google: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200',
  OpenAI: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
  DeepSeek: 'bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200',
  Tencent: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
  Moonshot: 'bg-cyan-100 text-cyan-800 dark:bg-cyan-900 dark:text-cyan-200',
  Qwen: 'bg-indigo-100 text-indigo-800 dark:bg-indigo-900 dark:text-indigo-200',
  'Z-AI': 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200',
  Arcee: 'bg-pink-100 text-pink-800 dark:bg-pink-900 dark:text-pink-200',
};

export const LS_SETTINGS_KEY = 'study-note-organizer-settings';
export const LS_HISTORY_KEY = 'study-note-organizer-history';
export const LS_DRAFT_KEY = 'study-note-organizer-draft';
export const MAX_HISTORY = 50;
export const MAX_INPUT_CHARS = 5000;
