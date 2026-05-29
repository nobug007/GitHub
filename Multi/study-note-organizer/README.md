# 학습 일지 정리기

하루치 수업/회의 내용을 붙여넣으면, 선택한 2개 AI 모델이 각각 [학습 목표] / [학습 내용] 형식으로 정리하고, 3번째(통합) 모델이 두 결과의 장점만 모아 최종본을 만들어주는 OpenRouter 기반 멀티 에이전트 웹 툴입니다.

## 설치 및 실행

```bash
# 의존성 설치
npm install

# 개발 서버 실행 (http://localhost:5173)
npm run dev

# 프로덕션 빌드
npm run build

# 빌드 결과물 미리보기
npm run preview
```

## OpenRouter API Key 발급 및 입력

1. [openrouter.ai](https://openrouter.ai)에서 계정 생성
2. [openrouter.ai/keys](https://openrouter.ai/keys)에서 API Key 발급
3. 앱 우측 상단 **설정(⚙️)** 아이콘 클릭
4. **OpenRouter API Key** 필드에 발급받은 키 입력 후 **저장**

> API Key는 브라우저 localStorage에만 저장되며 외부로 전송되지 않습니다.

## 사용법

1. 날짜를 선택합니다 (기본값: 오늘)
2. 수업/회의 내용을 textarea에 붙여넣습니다
3. **정리하기** 버튼 클릭 (또는 `Ctrl+Enter`)
4. 모델 A, B가 병렬로 정리 → 통합 모델이 최종 결과 생성
5. 카드별 **복사** / **다운로드** / **다시 요청** 가능

## 파일 다운로드 규격

- 통합 결과: `{YYMMDD}_{키워드1}_{키워드2}.txt`
- 개별 모델: `{YYMMDD}_{모델명}.txt`
- 인코딩: UTF-8

## 보안 주의사항

- API Key는 브라우저 localStorage에 저장됩니다 — 공용 컴퓨터에서 사용 후 설정에서 키를 삭제하세요
- OpenRouter API 사용량은 [openrouter.ai/activity](https://openrouter.ai/activity)에서 확인할 수 있습니다

## 기술 스택

- React 19 + TypeScript
- Vite
- TailwindCSS v4
- React Context + useReducer
- OpenRouter API (fetch)
- lucide-react (아이콘)
