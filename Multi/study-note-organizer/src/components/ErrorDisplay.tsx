import { AlertCircle, RefreshCw } from 'lucide-react';

interface Props {
  message: string;
  onRetry: () => void;
}

export default function ErrorDisplay({ message, onRetry }: Props) {
  return (
    <div className="flex flex-col items-center gap-3 py-6 text-center">
      <AlertCircle className="h-8 w-8 text-red-500" />
      <p className="text-sm text-red-600 dark:text-red-400">{message}</p>
      <button
        onClick={onRetry}
        className="flex items-center gap-1.5 rounded-lg bg-red-50 px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-100 dark:bg-red-900/30 dark:text-red-400 dark:hover:bg-red-900/50 transition-colors"
      >
        <RefreshCw className="h-4 w-4" />
        다시 시도
      </button>
    </div>
  );
}
