import React, { createContext, useContext, useReducer, useEffect, type ReactNode } from 'react';
import type { AppState, AppAction, ModelResult, FinalResult } from '../types';
import { DEFAULT_SETTINGS, LS_SETTINGS_KEY, LS_HISTORY_KEY, LS_DRAFT_KEY, MAX_HISTORY } from '../utils/constants';
import { loadFromStorage, saveToStorage } from '../hooks/useLocalStorage';
import { applyTheme, getSystemTheme } from '../hooks/useTheme';
import { todayISO } from '../utils/formatters';

function makeIdleResult(modelId: string, modelName: string): ModelResult {
  return { modelId, modelName, content: '', latencyMs: 0, status: 'idle' };
}

function makeIdleFinalResult(): FinalResult {
  return {
    modelId: '',
    modelName: '',
    content: '',
    latencyMs: 0,
    status: 'idle',
    keywords: ['정리', '수업'],
  };
}

const savedSettings = loadFromStorage(LS_SETTINGS_KEY, DEFAULT_SETTINGS);
const mergedSettings = { ...DEFAULT_SETTINGS, ...savedSettings };
if (!mergedSettings.theme) mergedSettings.theme = getSystemTheme();

const savedDraft = loadFromStorage(LS_DRAFT_KEY, { inputDate: todayISO(), inputText: '' });
const savedHistory = loadFromStorage(LS_HISTORY_KEY, []);

const initialState: AppState = {
  inputDate: savedDraft.inputDate || todayISO(),
  inputText: savedDraft.inputText || '',
  settings: mergedSettings,
  baseResult1: makeIdleResult(mergedSettings.baseModel1, ''),
  baseResult2: makeIdleResult(mergedSettings.baseModel2, ''),
  finalResult: null,
  history: savedHistory,
  isSettingsOpen: false,
  isHistoryOpen: false,
};

applyTheme(initialState.settings.theme);

function reducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'SET_INPUT_DATE':
      return { ...state, inputDate: action.payload };
    case 'SET_INPUT_TEXT':
      return { ...state, inputText: action.payload };
    case 'SET_SETTINGS':
      return { ...state, settings: { ...state.settings, ...action.payload } };
    case 'SET_BASE_RESULT1':
      return { ...state, baseResult1: action.payload };
    case 'SET_BASE_RESULT2':
      return { ...state, baseResult2: action.payload };
    case 'SET_FINAL_RESULT':
      return { ...state, finalResult: action.payload };
    case 'ADD_HISTORY': {
      const next = [action.payload, ...state.history].slice(0, MAX_HISTORY);
      return { ...state, history: next };
    }
    case 'REMOVE_HISTORY':
      return { ...state, history: state.history.filter((h) => h.id !== action.payload) };
    case 'CLEAR_HISTORY':
      return { ...state, history: [] };
    case 'RESTORE_HISTORY_ITEM': {
      const item = action.payload;
      return {
        ...state,
        inputDate: item.date,
        inputText: item.inputText,
        baseResult1: item.baseResult1,
        baseResult2: item.baseResult2,
        finalResult: item.finalResult,
        isHistoryOpen: false,
      };
    }
    case 'TOGGLE_SETTINGS':
      return { ...state, isSettingsOpen: !state.isSettingsOpen, isHistoryOpen: false };
    case 'TOGGLE_HISTORY':
      return { ...state, isHistoryOpen: !state.isHistoryOpen, isSettingsOpen: false };
    case 'CLOSE_PANELS':
      return { ...state, isSettingsOpen: false, isHistoryOpen: false };
    default:
      return state;
  }
}

interface AppContextValue {
  state: AppState;
  dispatch: React.Dispatch<AppAction>;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  useEffect(() => {
    saveToStorage(LS_SETTINGS_KEY, state.settings);
    applyTheme(state.settings.theme);
  }, [state.settings]);

  useEffect(() => {
    saveToStorage(LS_HISTORY_KEY, state.history);
  }, [state.history]);

  useEffect(() => {
    saveToStorage(LS_DRAFT_KEY, { inputDate: state.inputDate, inputText: state.inputText });
  }, [state.inputDate, state.inputText]);

  return <AppContext.Provider value={{ state, dispatch }}>{children}</AppContext.Provider>;
}

export function useAppContext(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useAppContext must be used inside AppProvider');
  return ctx;
}

export { makeIdleResult, makeIdleFinalResult };
