export interface ModelOption {
  id: string;
  name: string;
  provider: string;
  isFree: boolean;
}

export interface ModelResult {
  modelId: string;
  modelName: string;
  content: string;
  latencyMs: number;
  tokenUsage?: { prompt: number; completion: number; total: number };
  status: 'idle' | 'loading' | 'success' | 'error';
  errorMessage?: string;
}

export interface FinalResult extends ModelResult {
  keywords: [string, string];
}

export interface AppSettings {
  apiKey: string;
  baseModel1: string;
  baseModel2: string;
  mergeModel: string;
  theme: 'light' | 'dark';
}

export interface HistoryItem {
  id: string;
  date: string;
  formattedDate: string;
  inputText: string;
  baseResult1: ModelResult;
  baseResult2: ModelResult;
  finalResult: FinalResult;
  timestamp: number;
}

export interface AppState {
  inputDate: string;
  inputText: string;
  settings: AppSettings;
  baseResult1: ModelResult;
  baseResult2: ModelResult;
  finalResult: FinalResult | null;
  history: HistoryItem[];
  isSettingsOpen: boolean;
  isHistoryOpen: boolean;
}

export type AppAction =
  | { type: 'SET_INPUT_DATE'; payload: string }
  | { type: 'SET_INPUT_TEXT'; payload: string }
  | { type: 'SET_SETTINGS'; payload: Partial<AppSettings> }
  | { type: 'SET_BASE_RESULT1'; payload: ModelResult }
  | { type: 'SET_BASE_RESULT2'; payload: ModelResult }
  | { type: 'SET_FINAL_RESULT'; payload: FinalResult | null }
  | { type: 'ADD_HISTORY'; payload: HistoryItem }
  | { type: 'REMOVE_HISTORY'; payload: string }
  | { type: 'CLEAR_HISTORY' }
  | { type: 'RESTORE_HISTORY_ITEM'; payload: HistoryItem }
  | { type: 'TOGGLE_SETTINGS' }
  | { type: 'TOGGLE_HISTORY' }
  | { type: 'CLOSE_PANELS' };
