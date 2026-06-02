'use client';

import { TooltipProvider } from '@/components/ui/tooltip';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import { MainContent } from './MainContent';

export function AppLayout() {
  return (
    <TooltipProvider>
      <div className="flex flex-col h-screen bg-white">
        <Header />
        <div className="flex flex-1 overflow-hidden">
          <Sidebar />
          <MainContent />
        </div>
      </div>
    </TooltipProvider>
  );
}
