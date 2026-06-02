'use client';

import { create } from 'zustand';
import type { ParsedData, MenuView } from '@/lib/types';

interface DataStore {
  data: ParsedData | null;
  fileName: string | null;
  activeView: MenuView;
  setData: (data: ParsedData, fileName: string) => void;
  setActiveView: (view: MenuView) => void;
  clearData: () => void;
}

export const useDataStore = create<DataStore>((set) => ({
  data: null,
  fileName: null,
  activeView: 'upload',
  setData: (data, fileName) => set({ data, fileName }),
  setActiveView: (view) => set({ activeView: view }),
  clearData: () => set({ data: null, fileName: null, activeView: 'upload' }),
}));
