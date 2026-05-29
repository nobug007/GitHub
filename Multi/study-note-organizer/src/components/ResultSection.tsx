import type { ModelResult, FinalResult } from '../types';
import ResultCard from './ResultCard';

interface Props {
  baseResult1: ModelResult;
  baseResult2: ModelResult;
  finalResult: FinalResult | null;
  inputDate: string;
  onRetry1: () => void;
  onRetry2: () => void;
  onRetryMerge: () => void;
  onToast: (text: string, type?: 'success' | 'warning' | 'error') => void;
}

export default function ResultSection({
  baseResult1,
  baseResult2,
  finalResult,
  inputDate,
  onRetry1,
  onRetry2,
  onRetryMerge,
  onToast,
}: Props) {
  const idleFinal: FinalResult = {
    modelId: '',
    modelName: '통합 모델',
    content: '',
    latencyMs: 0,
    status: 'idle',
    keywords: ['정리', '수업'],
  };

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
      <ResultCard
        result={baseResult1}
        label="모델 A"
        inputDate={inputDate}
        onRetry={onRetry1}
        onToast={onToast}
        animDelay={0}
      />
      <ResultCard
        result={baseResult2}
        label="모델 B"
        inputDate={inputDate}
        onRetry={onRetry2}
        onToast={onToast}
        animDelay={100}
      />
      <div className="md:col-span-2">
        <ResultCard
          result={finalResult ?? idleFinal}
          label="통합 결과"
          isMerge
          inputDate={inputDate}
          onRetry={onRetryMerge}
          onToast={onToast}
          animDelay={200}
        />
      </div>
    </div>
  );
}
