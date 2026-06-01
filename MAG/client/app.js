const app = document.querySelector("#app");

const state = {
  page: 1,
  idea: "",
  aiList: [],
  selectedAIIds: [],
  outputs: [],
  merged: "",
  personaAIList: [],
  personaSelectedAIIds: [],
  personaOutputs: [],
  personaPrompt: "",
  personaMerged: ""
};

async function boot() {
  const [problemResponse, personaResponse] = await Promise.all([
    fetch("/api/problem-definition/ais"),
    fetch("/api/persona/ais")
  ]);
  state.aiList = await problemResponse.json();
  state.personaAIList = await personaResponse.json();
  render();
}

function render() {
  if (state.page === 1) return renderIdeaPage();
  if (state.page === 2) return renderProblemOutputPage();
  if (state.page === 3) return renderPersonaSelectionPage();
  if (state.page === 4) return renderPersonaOutputPage();
  return renderPersonaMergedPage();
}

function renderIdeaPage() {
  app.innerHTML = `
    <section class="page intro">
      <h1>당신의 아이디어를 구체화 해 드립니다.</h1>
      <p>아이디어를 입력하고 문제 정의와 목표 구체화에 적합한 AI 3개를 선택해 주세요.</p>
      <div class="idea-box">
        <label for="ideaInput">아이디어를 입력해 주세요.</label>
        <textarea class="idea-input" id="ideaInput" placeholder="예: 지역 소상공인과 관광객을 연결하는 AI 큐레이션 서비스를 만들고 싶습니다.">${escapeHtml(state.idea)}</textarea>
      </div>
      ${renderAISelection({
        title: "문제정의 및 목표 구체화 AI",
        list: state.aiList,
        selectedIds: state.selectedAIIds,
        attribute: "data-problem-ai-id"
      })}
      <div id="errorBox"></div>
      <div class="action-row"><button class="primary large" id="executeButton" type="button">실행</button></div>
    </section>
  `;
  document.querySelector("#ideaInput").addEventListener("input", (event) => {
    state.idea = event.target.value;
  });
  bindAIButtons("[data-problem-ai-id]", "problem");
  document.querySelector("#executeButton").addEventListener("click", executeSelectedAIs);
}

function renderProblemOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("문제정의 및 목표 구체화", "선택한 3개의 AI 결과를 확인하고 필요한 경우 직접 편집해 주세요.")}
      ${renderSummary("입력 아이디어", state.idea)}
      ${renderOutputGrid(state.outputs, "data-problem-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToIdeaButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="mergeButton" type="button">Submit</button>
      </div>
    </section>
  `;
  bindOutputEditors("[data-problem-output-index]", state.outputs);
  document.querySelector("#backToIdeaButton").addEventListener("click", () => {
    syncOutputEditors("[data-problem-output-index]", state.outputs, "problemOutputIndex");
    state.page = 1;
    render();
  });
  document.querySelector("#mergeButton").addEventListener("click", mergeOutputs);
}

function renderPersonaSelectionPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("문제정의 및 목표 취합 결과", "3개의 AI 답변을 취합했습니다. 내용을 수정한 뒤 타겟 분석과 페르소나 작성에 사용할 AI 서버 3개를 선택해 주세요.")}
      <section class="merge-panel merge-panel-first">
        <div class="result-badge">Merge Server Output</div>
        <h2>취합된 최적 답변</h2>
        <p class="helper">이 내용은 선택한 AI 서버에 전달됩니다. 필요한 부분을 직접 편집할 수 있습니다.</p>
        <textarea id="mergedInput">${escapeHtml(state.merged)}</textarea>
      </section>
      ${renderAISelection({
        title: "타겟 분석 및 페르소나 작성 AI 서버",
        list: state.personaAIList,
        selectedIds: state.personaSelectedAIIds,
        attribute: "data-persona-ai-id"
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToProblemOutputButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="personaRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  document.querySelector("#mergedInput").addEventListener("input", (event) => {
    state.merged = event.target.value;
  });
  bindAIButtons("[data-persona-ai-id]", "persona");
  document.querySelector("#backToProblemOutputButton").addEventListener("click", () => {
    state.merged = document.querySelector("#mergedInput").value;
    state.page = 2;
    render();
  });
  document.querySelector("#personaRunButton").addEventListener("click", executePersonaAIs);
}

function renderPersonaOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("AI 서버별 타겟 / 페르소나 답변", "선택한 3개의 AI 서버에 앞 단계 취합문을 보내 타겟과 페르소나 작성을 요청했습니다. 각 서버의 답변을 확인하고 직접 편집할 수 있습니다.")}
      ${renderSummary("AI 서버에 보낸 취합문", state.personaPrompt)}
      ${renderOutputGrid(state.personaOutputs, "data-persona-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToPersonaSelectionButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="personaMergeButton" type="button">Submit</button>
      </div>
    </section>
  `;
  bindOutputEditors("[data-persona-output-index]", state.personaOutputs);
  document.querySelector("#backToPersonaSelectionButton").addEventListener("click", () => {
    syncOutputEditors("[data-persona-output-index]", state.personaOutputs, "personaOutputIndex");
    state.page = 3;
    render();
  });
  document.querySelector("#personaMergeButton").addEventListener("click", mergePersonaOutputs);
}

function renderPersonaMergedPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("타겟 / 페르소나 분석 완료", "Merge 서버가 세 AI의 답변을 취합하여 하나의 분석 결과를 만들었습니다.")}
      ${renderSummary("사업 아이디어", state.idea)}
      <section class="merge-panel result-panel">
        <div class="result-badge">Merge Server Output</div>
        <h2>취합된 타겟 / 페르소나 분석</h2>
        <p class="helper">최종 결과를 확인하고 필요한 경우 직접 편집할 수 있습니다.</p>
        <textarea id="personaMergedInput">${escapeHtml(state.personaMerged)}</textarea>
      </section>
      <div class="action-row"><button class="secondary" id="backToPersonaButton" type="button">이전 단계로 돌아가기</button></div>
    </section>
  `;
  document.querySelector("#personaMergedInput").addEventListener("input", (event) => {
    state.personaMerged = event.target.value;
  });
  document.querySelector("#backToPersonaButton").addEventListener("click", () => {
    state.page = 4;
    render();
  });
}

function renderHeading(title, description) {
  return `<div class="page-heading"><h1>${escapeHtml(title)}</h1><p>${escapeHtml(description)}</p></div>`;
}

function renderSummary(title, content) {
  return `<div class="idea-summary"><strong>${escapeHtml(title)}</strong><br>${escapeHtml(content)}</div>`;
}

function renderAISelection({ title, list, selectedIds, attribute }) {
  return `
    <section class="ai-selection">
      <div class="section-head">
        <div>
          <strong class="section-title">${escapeHtml(title)}</strong>
          <p class="helper">타겟 분석 및 페르소나 작성 적합도가 높은 순서입니다. 실행할 AI 서버를 3개 선택해 주세요.</p>
        </div>
        <span class="selected-count">${selectedIds.length} / 3 선택</span>
      </div>
      <div class="ai-list">${list.map((ai, index) => aiCard(ai, index, selectedIds, attribute)).join("")}</div>
    </section>
  `;
}

function aiCard(ai, index, selectedIds, attribute) {
  const selected = selectedIds.includes(ai.id);
  return `
    <article class="ai-card ${selected ? "selected" : ""}">
      <span class="rank">${index + 1}</span>
      <div>
        <h3>${escapeHtml(ai.name)}</h3>
        <p>${escapeHtml(ai.description)}</p>
        <span class="fit">적합도 ${ai.fit}%</span>
      </div>
      <button class="select-button" type="button" ${attribute}="${ai.id}">${selected ? "선택됨" : "선택"}</button>
    </article>
  `;
}

function renderOutputGrid(outputs, attribute) {
  return `
    <div class="output-grid">
      ${outputs.map((output, index) => `
        <article class="output-card">
          <header><h2>${escapeHtml(output.aiName)}</h2><span class="fit">적합도 ${output.fit}%</span></header>
          <textarea ${attribute}="${index}">${escapeHtml(output.content)}</textarea>
        </article>
      `).join("")}
    </div>
  `;
}

function renderMergePanel({ title, description, value, inputId, buttonId }) {
  return `
    <section class="merge-panel">
      <h2>${escapeHtml(title)}</h2>
      <p class="helper">${escapeHtml(description)}</p>
      <textarea id="${inputId}">${escapeHtml(value)}</textarea>
      <div class="action-row"><button class="primary large" id="${buttonId}" type="button">Submit</button></div>
    </section>
  `;
}

function bindAIButtons(selector, type) {
  document.querySelectorAll(selector).forEach((button) => {
    button.addEventListener("click", () => toggleAI(button.dataset[type === "problem" ? "problemAiId" : "personaAiId"], type));
  });
}

function toggleAI(id, type) {
  const key = type === "problem" ? "selectedAIIds" : "personaSelectedAIIds";
  if (state[key].includes(id)) {
    state[key] = state[key].filter((item) => item !== id);
  } else if (state[key].length < 3) {
    state[key] = [...state[key], id];
  }
  render();
}

function bindOutputEditors(selector, outputs) {
  document.querySelectorAll(selector).forEach((textarea) => {
    textarea.addEventListener("input", () => {
      outputs[Number(textarea.dataset[selector.includes("problem") ? "problemOutputIndex" : "personaOutputIndex"])].content = textarea.value;
    });
  });
}

async function executeSelectedAIs() {
  if (!state.idea.trim()) return showError("아이디어를 입력해 주세요.");
  if (state.selectedAIIds.length !== 3) return showError("AI를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "executeButton",
    loadingText: "AI 실행 중...",
    url: "/api/problem-definition/run",
    body: { idea: state.idea, selectedAIIds: state.selectedAIIds },
    onSuccess: (data) => {
      state.outputs = data.outputs;
      state.page = 2;
    }
  });
}

async function mergeOutputs() {
  syncOutputEditors("[data-problem-output-index]", state.outputs, "problemOutputIndex");
  await submitJSON({
    buttonId: "mergeButton",
    loadingText: "취합 중...",
    url: "/api/problem-definition/merge",
    body: { idea: state.idea, outputs: state.outputs },
    onSuccess: (data) => {
      state.merged = data.merged;
      state.page = 3;
    }
  });
}

async function executePersonaAIs() {
  const mergedInput = document.querySelector("#mergedInput");
  if (mergedInput) state.merged = mergedInput.value;
  if (!state.merged.trim()) return showError("취합된 문제 정의 및 목표를 입력해 주세요.");
  if (state.personaSelectedAIIds.length !== 3) return showError("AI를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "personaRunButton",
    loadingText: "타겟 분석 중...",
    url: "/api/persona/run",
    body: { idea: state.idea, problemDefinition: state.merged, selectedAIIds: state.personaSelectedAIIds },
    onSuccess: (data) => {
      state.personaOutputs = data.outputs;
      state.personaPrompt = data.prompt;
      state.page = 4;
    }
  });
}

async function mergePersonaOutputs() {
  syncOutputEditors("[data-persona-output-index]", state.personaOutputs, "personaOutputIndex");
  await submitJSON({
    buttonId: "personaMergeButton",
    loadingText: "Merge 서버 취합 중...",
    url: "/api/persona/merge",
    body: { idea: state.idea, problemDefinition: state.merged, outputs: state.personaOutputs },
    onSuccess: (data) => {
      state.personaMerged = data.merged;
      state.page = 5;
    }
  });
}

function syncOutputEditors(selector, outputs, datasetKey) {
  document.querySelectorAll(selector).forEach((textarea) => {
    outputs[Number(textarea.dataset[datasetKey])].content = textarea.value;
  });
}

async function submitJSON({ buttonId, loadingText, url, body, onSuccess }) {
  const button = document.querySelector(`#${buttonId}`);
  button.disabled = true;
  button.textContent = loadingText;
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error);
    onSuccess(data);
    render();
  } catch (error) {
    showError(error.message);
    button.disabled = false;
    button.textContent = "Submit";
  }
}

function showError(message) {
  const box = document.querySelector("#errorBox");
  if (box) box.innerHTML = `<div class="error">${escapeHtml(message)}</div>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

boot();
