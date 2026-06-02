'use client';

import { useDataStore } from '@/store/dataStore';
import { FileUpload } from '@/components/upload/FileUpload';
import { BasicAnalysis } from '@/components/analysis/BasicAnalysis';

export function MainContent() {
  const { activeView } = useDataStore();

  return (
    <main className="flex-1 overflow-auto p-6">
      {activeView === 'upload' && <FileUpload />}
      {activeView === 'analysis' && <BasicAnalysis />}
    </main>
  );
}
