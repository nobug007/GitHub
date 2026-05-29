import type { ModelResult } from '../types';
import { MODEL_OPTIONS } from '../utils/constants';

function getModelName(modelId: string): string {
  return MODEL_OPTIONS.find((m) => m.id === modelId)?.name ?? modelId;
}

function parseError(status: number, body: unknown): string {
  if (status === 401) return 'API Key가 올바르지 않습니다. 설정에서 확인하세요.';
  if (status === 429) return '요청이 너무 많습니다. 잠시 후 다시 시도하세요.';
  if (status === 404) return '선택한 모델을 사용할 수 없습니다. 다른 모델을 선택하세요.';
  if (status === 503) return '선택한 모델을 사용할 수 없습니다. 다른 모델을 선택하세요.';
  const msg = (body as { error?: { message?: string } })?.error?.message;
  if (msg?.toLowerCase().includes('model')) return '선택한 모델을 사용할 수 없습니다. 다른 모델을 선택하세요.';
  return `API 오류가 발생했습니다. (${status})`;
}

export async function callOpenRouter(
  apiKey: string,
  modelId: string,
  prompt: string,
): Promise<ModelResult> {
  const start = Date.now();
  const modelName = getModelName(modelId);

  try {
    const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
      method: 'POST',
      credentials: 'omit',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
        'HTTP-Referer': window.location.origin,
        'X-Title': '학습 일지 정리기',
      },
      body: JSON.stringify({
        model: modelId,
        messages: [{ role: 'user', content: prompt }],
        temperature: 0.3,
      }),
    });

    const latencyMs = Date.now() - start;

    if (!res.ok) {
      let body: unknown = {};
      try {
        body = await res.json();
      } catch {
        // ignore parse failure
      }
      return {
        modelId,
        modelName,
        content: '',
        latencyMs,
        status: 'error',
        errorMessage: parseError(res.status, body),
      };
    }

    const data = await res.json() as {
      choices: { message: { content: string } }[];
      usage?: { prompt_tokens: number; completion_tokens: number; total_tokens: number };
    };

    const content = data.choices?.[0]?.message?.content ?? '';
    const usage = data.usage
      ? {
          prompt: data.usage.prompt_tokens,
          completion: data.usage.completion_tokens,
          total: data.usage.total_tokens,
        }
      : undefined;

    return {
      modelId,
      modelName,
      content,
      latencyMs,
      tokenUsage: usage,
      status: 'success',
    };
  } catch (err) {
    const raw = err instanceof Error ? err.message : String(err);
    console.error(`[OpenRouter] ${modelId}:`, err);

    let errorMessage = `네트워크 오류: ${raw}`;
    if (raw.toLowerCase().includes('failed to fetch') || raw.toLowerCase().includes('networkerror')) {
      errorMessage = 'API 호출 실패. 브라우저 콘솔(F12 → Console)에서 실제 오류를 확인하세요.';
    }

    return {
      modelId,
      modelName,
      content: '',
      latencyMs: Date.now() - start,
      status: 'error',
      errorMessage,
    };
  }
}
