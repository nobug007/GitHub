const app = document.querySelector("#app");

function relativeUrl(path) {
  return String(path).replace(/^\/+/, "");
}

function deliverableUrl(file) {
  return relativeUrl(`/deliverables/${encodeURIComponent(file)}`);
}

const state = {
  page: 1,
  startedAt: Date.now(),
  plan: "premium",
  premiumSkipSteps: [],
  useLiveAI: false,
  idea: "",
  aiList: [],
  selectedAIIds: [],
  outputs: [],
  merged: "",
  personaAIList: [],
  personaSelectedAIIds: [],
  personaOutputs: [],
  personaPrompt: "",
  personaMerged: "",
  scenarioAIList: [],
  scenarioSelectedAIIds: [],
  scenarioOutputs: [],
  scenarioPrompt: "",
  scenarioMerged: "",
  featureAIList: [],
  featureSelectedAIIds: [],
  featureOutputs: [],
  featurePrompt: "",
  featureMerged: "",
  workflowAIList: [],
  workflowSelectedAIIds: [],
  workflowOutputs: [],
  workflowPrompt: "",
  workflowMerged: "",
  uiuxAIList: [],
  uiuxSelectedAIIds: [],
  uiuxOutputs: [],
  uiuxPrompt: "",
  uiuxMerged: "",
  uiuxPreviewIndex: 0,
  codeAIList: [],
  codeSelectedAIIds: [],
  indexPageOutputs: [],
  indexPreviewIndex: 0,
  finalIndexPageIndex: 0,
  mediaAIList: [],
  mediaSelectedAIIds: [],
  mediaOutputs: [],
  mediaPreviewIndex: 0,
  finalMediaIndex: 0,
  businessAIList: [],
  businessSelectedAIIds: [],
  businessOutputs: [],
  businessPrompt: "",
  businessMerged: "",
  presentationAIList: [],
  presentationSelectedAIIds: [],
  presentationOutputs: [],
  presentationPrompt: "",
  presentationFiles: []
};

async function boot() {
  const [problemResponse, personaResponse, scenarioResponse, featureResponse, workflowResponse, uiuxResponse, codeResponse, mediaResponse, businessResponse, presentationResponse] = await Promise.all([
    fetch(relativeUrl("/api/problem-definition/ais")),
    fetch(relativeUrl("/api/persona/ais")),
    fetch(relativeUrl("/api/scenario/ais")),
    fetch(relativeUrl("/api/features/ais")),
    fetch(relativeUrl("/api/workflow-design/ais")),
    fetch(relativeUrl("/api/uiux-design/ais")),
    fetch(relativeUrl("/api/code-generation/ais")),
    fetch(relativeUrl("/api/media-generation/ais")),
    fetch(relativeUrl("/api/business-analysis/ais")),
    fetch(relativeUrl("/api/presentation-generation/ais"))
  ]);
  state.aiList = await problemResponse.json();
  state.personaAIList = await personaResponse.json();
  state.scenarioAIList = await scenarioResponse.json();
  state.featureAIList = await featureResponse.json();
  state.workflowAIList = await workflowResponse.json();
  state.uiuxAIList = await uiuxResponse.json();
  state.codeAIList = await codeResponse.json();
  state.mediaAIList = await mediaResponse.json();
  state.businessAIList = await businessResponse.json();
  state.presentationAIList = await presentationResponse.json();
  render();
}

function render() {
  if (state.page === 1) return renderIdeaPage();
  if (state.page === 2) return renderProblemOutputPage();
  if (state.page === 3) return renderPersonaSelectionPage();
  if (state.page === 4) return renderPersonaOutputPage();
  if (state.page === 5) return renderPersonaMergedPage();
  if (state.page === 6) return renderScenarioOutputPage();
  if (state.page === 7) return renderScenarioMergedPage();
  if (state.page === 8) return renderFeatureOutputPage();
  if (state.page === 9) return renderFeatureMergedPage();
  if (state.page === 10) return renderWorkflowOutputPage();
  if (state.page === 11) return renderWorkflowMergedPage();
  if (state.page === 12) return renderUIUXOutputPage();
  if (state.page === 13) return renderUIUXPreviewPage();
  if (state.page === 14) return renderCodeSelectionPage();
  if (state.page === 15) return renderIndexPageLinks();
  if (state.page === 16) return renderIndexPagePreview();
  if (state.page === 17) return renderFinalIndexPage();
  if (state.page === 18) return renderMediaGalleryPage();
  if (state.page === 19) return renderMediaPreviewPage();
  if (state.page === 20) return renderBusinessOutputPage();
  if (state.page === 21) return renderBusinessMergedPage();
  if (state.page === 22) return renderPresentationOutputPage();
  return renderPresentationFilesPage();
}

function renderIdeaPage() {
  const isPremium = state.plan === "premium";
  app.innerHTML = `
    <section class="page intro">
      <h1>당신의 아이디어를 구체화 해 드립니다.</h1>
      <p>진행 방식을 선택하고 아이디어를 입력해 주세요. 필요한 검토 수준에 맞춰 AI 제작 흐름을 구성합니다.</p>
      ${renderPlanSelector()}
      <div class="idea-box">
        <label for="ideaInput">아이디어를 입력해 주세요.</label>
        <textarea class="idea-input" id="ideaInput" placeholder="예: 지역 소상공인과 관광객을 연결하는 AI 큐레이션 서비스를 만들고 싶습니다.">${escapeHtml(state.idea)}</textarea>
      </div>
      ${isPremium ? renderAISelection({
        title: "문제정의 및 목표 구체화 AI",
        list: state.aiList,
        selectedIds: state.selectedAIIds,
        attribute: "data-problem-ai-id",
        helper: "문제 정의 및 목표 구체화 적합도가 높은 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      }) : `<div class="plan-note">${state.plan === "free" ? "FREE는 입력 후 10단계를 자동으로 진행하여 제안서 다운로드 화면까지 이동합니다." : "STANDARD는 2~4단계, 5~6단계, 7~8단계, 9~10단계를 묶어서 진행합니다. 각 묶음의 마지막 화면에서 결과를 검토할 수 있습니다."}</div>`}
      <div id="errorBox"></div>
      <div class="action-row"><button class="primary large" id="executeButton" type="button">${isPremium ? "실행" : "자동 제작 시작"}</button></div>
    </section>
  `;
  mountSidebar(1);
  document.querySelector("#ideaInput").addEventListener("input", (event) => {
    state.idea = event.target.value;
  });
  document.querySelectorAll("[data-plan]").forEach((button) => {
    button.addEventListener("click", () => {
      state.plan = button.dataset.plan;
      render();
    });
  });
  if (isPremium) bindAIButtons("[data-problem-ai-id]", "problem");
  document.querySelector("#executeButton").addEventListener("click", executeSelectedAIs);
}

function renderPlanSelector() {
  const plans = [
    ["free", "FREE", "아이디어 입력 후 결과물까지 자동 완주"],
    ["standard", "STANDARD", "묶음별 핵심 결과만 검토하고 수정"],
    ["premium", "PREMIUM", "AI 선택과 수정 단계를 세밀하게 제어"]
  ];
  return `
    <section class="plan-selector" aria-label="제작 플랜 선택">
      ${plans.map(([id, name, description]) => `
        <button class="plan-card ${state.plan === id ? "selected" : ""}" data-plan="${id}" type="button">
          <strong>${name}</strong>
          <span>${description}</span>
        </button>
      `).join("")}
    </section>
  `;
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
  mountSidebar(1);
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
        attribute: "data-persona-ai-id",
        helper: "타겟 분석 및 페르소나 작성 적합도가 높은 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToProblemOutputButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="personaRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(2);
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
  mountSidebar(2);
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
      ${renderAISelection({
        title: "사용자 시나리오 작성 AI 서버",
        list: state.scenarioAIList,
        selectedIds: state.scenarioSelectedAIIds,
        attribute: "data-scenario-ai-id",
        helper: "사용자 시나리오 작성 적합도가 높은 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToPersonaButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="scenarioRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(2);
  document.querySelector("#personaMergedInput").addEventListener("input", (event) => {
    state.personaMerged = event.target.value;
  });
  document.querySelector("#backToPersonaButton").addEventListener("click", () => {
    state.page = 4;
    render();
  });
  bindAIButtons("[data-scenario-ai-id]", "scenario");
  document.querySelector("#scenarioRunButton").addEventListener("click", executeScenarioAIs);
}

function renderScenarioOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("AI 서버별 사용자 시나리오", "선택한 3개의 AI 서버가 작성한 사용자 시나리오입니다. 각 답변을 확인하고 직접 편집할 수 있습니다.")}
      ${renderSummary("AI 서버에 보낸 타겟 / 페르소나 정의", state.scenarioPrompt)}
      ${renderOutputGrid(state.scenarioOutputs, "data-scenario-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToPersonaMergedButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="scenarioMergeButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(3);
  bindOutputEditors("[data-scenario-output-index]", state.scenarioOutputs);
  document.querySelector("#backToPersonaMergedButton").addEventListener("click", () => {
    syncOutputEditors("[data-scenario-output-index]", state.scenarioOutputs, "scenarioOutputIndex");
    state.page = 5;
    render();
  });
  document.querySelector("#scenarioMergeButton").addEventListener("click", mergeScenarioOutputs);
}

function renderScenarioMergedPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("사용자 시나리오 취합 완료", "Merge 서버가 세 AI의 시나리오를 하나로 취합했습니다. 내용을 수정한 뒤 핵심 기능 정의에 사용할 AI 서버 3개를 선택해 주세요.")}
      <section class="merge-panel result-panel">
        <div class="result-badge">Merge Server Output</div>
        <h2>취합된 사용자 시나리오</h2>
        <p class="helper">이 내용은 선택한 핵심 기능 정의 AI 서버에 전달됩니다. 필요한 부분을 직접 편집할 수 있습니다.</p>
        <textarea id="scenarioMergedInput">${escapeHtml(state.scenarioMerged)}</textarea>
      </section>
      ${renderAISelection({
        title: "핵심 기능 정의 AI 서버",
        list: state.featureAIList,
        selectedIds: state.featureSelectedAIIds,
        attribute: "data-feature-ai-id",
        helper: "핵심 기능 정의와 MVP 범위 판단에 적합한 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToScenarioOutputsButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="featureRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(3);
  document.querySelector("#scenarioMergedInput").addEventListener("input", (event) => {
    state.scenarioMerged = event.target.value;
  });
  bindAIButtons("[data-feature-ai-id]", "feature");
  document.querySelector("#backToScenarioOutputsButton").addEventListener("click", () => {
    state.scenarioMerged = document.querySelector("#scenarioMergedInput").value;
    state.page = 6;
    render();
  });
  document.querySelector("#featureRunButton").addEventListener("click", executeFeatureAIs);
}

function renderFeatureOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("AI 서버별 핵심 기능 정의", "선택한 3개의 AI 서버가 작성한 핵심 기능 정의입니다. 각 답변을 확인하고 직접 편집할 수 있습니다.")}
      ${renderSummary("AI 서버에 보낸 사용자 시나리오", state.featurePrompt)}
      ${renderOutputGrid(state.featureOutputs, "data-feature-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToScenarioMergedButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="featureMergeButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(4);
  bindOutputEditors("[data-feature-output-index]", state.featureOutputs);
  document.querySelector("#backToScenarioMergedButton").addEventListener("click", () => {
    syncOutputEditors("[data-feature-output-index]", state.featureOutputs, "featureOutputIndex");
    state.page = 7;
    render();
  });
  document.querySelector("#featureMergeButton").addEventListener("click", mergeFeatureOutputs);
}

function renderFeatureMergedPage() {
  const isStandard = state.plan === "standard";
  app.innerHTML = `
    <section class="page">
      ${renderHeading("핵심 기능 정의 취합 완료", "Merge 서버가 세 AI의 핵심 기능 정의를 하나로 취합했습니다. 내용을 수정한 뒤 WorkFlow 설계에 사용할 AI 서버 3개를 선택해 주세요.")}
      <section class="merge-panel result-panel">
        <div class="result-badge">Merge Server Output</div>
        <h2>취합된 핵심 기능 정의</h2>
        <p class="helper">이 내용은 선택한 WorkFlow 설계 AI 서버에 전달됩니다. 필요한 부분을 직접 편집할 수 있습니다.</p>
        <textarea id="featureMergedInput">${escapeHtml(state.featureMerged)}</textarea>
      </section>
      ${isStandard ? `<div class="plan-note">STANDARD 체크포인트: 핵심 기능을 수정한 뒤 5~6단계 WorkFlow와 UI / UX 구성을 한 번에 진행합니다.</div>` : renderAISelection({
        title: "WorkFlow 설계 AI 서버",
        list: state.workflowAIList,
        selectedIds: state.workflowSelectedAIIds,
        attribute: "data-workflow-ai-id",
        helper: "사용자 흐름, AI 처리, 데이터 흐름을 설계하기 적합한 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToFeatureOutputsButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="${isStandard ? "standardUIUXButton" : "workflowRunButton"}" type="button">${isStandard ? "5~6단계 자동 실행" : "Submit"}</button>
      </div>
    </section>
  `;
  mountSidebar(4);
  document.querySelector("#featureMergedInput").addEventListener("input", (event) => {
    state.featureMerged = event.target.value;
  });
  if (!isStandard) bindAIButtons("[data-workflow-ai-id]", "workflow");
  document.querySelector("#backToFeatureOutputsButton").addEventListener("click", () => {
    state.featureMerged = document.querySelector("#featureMergedInput").value;
    state.page = 8;
    render();
  });
  document.querySelector(isStandard ? "#standardUIUXButton" : "#workflowRunButton").addEventListener("click", isStandard ? () => {
    state.featureMerged = document.querySelector("#featureMergedInput").value;
    runAutomation("standardUIUXButton", "uiux", 5);
  } : executeWorkflowAIs);
}

function renderWorkflowOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("AI 서버별 WorkFlow 설계", "선택한 3개의 AI 서버가 작성한 WorkFlow입니다. 각 답변을 확인하고 직접 편집할 수 있습니다.")}
      ${renderSummary("AI 서버에 보낸 핵심 기능 정의", state.workflowPrompt)}
      ${renderOutputGrid(state.workflowOutputs, "data-workflow-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToFeatureMergedButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="workflowMergeButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(5);
  bindOutputEditors("[data-workflow-output-index]", state.workflowOutputs);
  document.querySelector("#backToFeatureMergedButton").addEventListener("click", () => {
    syncOutputEditors("[data-workflow-output-index]", state.workflowOutputs, "workflowOutputIndex");
    state.page = 9;
    render();
  });
  document.querySelector("#workflowMergeButton").addEventListener("click", mergeWorkflowOutputs);
}

function renderWorkflowMergedPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("WorkFlow 구성 취합 완료", "Merge 서버가 세 AI의 WorkFlow를 하나로 취합했습니다. 내용을 수정한 뒤 UI/UX 화면 구성에 사용할 AI 엔진 3개를 선택해 주세요.")}
      <section class="merge-panel result-panel">
        <div class="result-badge">Merge Server Output</div>
        <h2>취합된 WorkFlow 구성</h2>
        <p class="helper">이 내용은 선택한 UI/UX 구성 AI 엔진에 전달됩니다. 필요한 부분을 직접 편집할 수 있습니다.</p>
        <textarea id="workflowMergedInput">${escapeHtml(state.workflowMerged)}</textarea>
      </section>
      ${renderAISelection({
        title: "UI / UX 화면 구성 AI 엔진",
        list: state.uiuxAIList,
        selectedIds: state.uiuxSelectedAIIds,
        attribute: "data-uiux-ai-id",
        helper: "화면 목록, UI 요소, CTA, 이동 경로를 설계하기 적합한 순서입니다. 실행할 AI 엔진을 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToWorkflowOutputsButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="uiuxRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(5);
  document.querySelector("#workflowMergedInput").addEventListener("input", (event) => {
    state.workflowMerged = event.target.value;
  });
  bindAIButtons("[data-uiux-ai-id]", "uiux");
  document.querySelector("#backToWorkflowOutputsButton").addEventListener("click", () => {
    state.workflowMerged = document.querySelector("#workflowMergedInput").value;
    state.page = 10;
    render();
  });
  document.querySelector("#uiuxRunButton").addEventListener("click", executeUIUXAIs);
}

function renderUIUXOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("통합 UI / UX 화면 구성", "세 AI 엔진의 제안을 하나로 통합했습니다. 통합안을 편집하고 엔진별 와이어프레임 미리보기를 비교해 주세요.")}
      <section class="merge-panel result-panel">
        <div class="result-badge">Merge Server Output</div>
        <h2>통합된 UI / UX 구성안</h2>
        <p class="helper">최종 화면 구성을 확정하기 전에 통합 내용을 직접 편집할 수 있습니다.</p>
        <textarea id="uiuxMergedInput">${escapeHtml(state.uiuxMerged)}</textarea>
      </section>
      <section class="preview-section">
        <div class="section-head">
          <div>
            <strong class="section-title">AI 엔진별 화면 미리보기</strong>
            <p class="helper">각 엔진의 설계 관점을 요약했습니다. 링크를 열어 화면 구성을 그림으로 확인해 주세요.</p>
          </div>
        </div>
        <div class="preview-link-grid">
          ${state.uiuxOutputs.map((output, index) => `
            <article class="preview-link-card">
              <div>
                <span class="preview-style">${escapeHtml(output.style)}</span>
                <h2>${escapeHtml(output.aiName)}</h2>
                <p>${escapeHtml(output.summary)}</p>
              </div>
              <button class="preview-link" type="button" data-uiux-preview-index="${index}">미리보기 열기 →</button>
            </article>
          `).join("")}
        </div>
      </section>
      <div class="action-row">
        <button class="secondary" id="backToWorkflowMergedButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="confirmUIUXButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(6);
  document.querySelector("#uiuxMergedInput").addEventListener("input", (event) => {
    state.uiuxMerged = event.target.value;
  });
  document.querySelectorAll("[data-uiux-preview-index]").forEach((button) => {
    button.addEventListener("click", () => {
      state.uiuxMerged = document.querySelector("#uiuxMergedInput").value;
      state.uiuxPreviewIndex = Number(button.dataset.uiuxPreviewIndex);
      state.page = 13;
      render();
    });
  });
  document.querySelector("#backToWorkflowMergedButton").addEventListener("click", () => {
    state.uiuxMerged = document.querySelector("#uiuxMergedInput").value;
    state.page = 11;
    render();
  });
  document.querySelector("#confirmUIUXButton").addEventListener("click", () => {
    state.uiuxMerged = document.querySelector("#uiuxMergedInput").value;
    if (state.plan === "standard") {
      runAutomation("confirmUIUXButton", "media", 7);
    } else if (state.plan === "premium" && consecutivePremiumAutoTarget(7)) {
      runAutomation("confirmUIUXButton", consecutivePremiumAutoTarget(7), 7);
    } else {
      state.page = 14;
      render();
    }
  });
}

function renderCodeSelectionPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("통합 UI / UX 화면 구성 확정", "세 AI 엔진의 내용을 취합한 최종 화면 구성입니다. 이 구성을 기준으로 Index Page 하나만 생성할 AI 서버 3개를 선택해 주세요.")}
      <section class="merge-panel result-panel">
        <div class="result-badge">Final UI / UX Composition</div>
        <h2>확정된 통합 화면 구성</h2>
        <p class="helper">코드 생성 AI 서버는 이 내용을 기준으로 첫 화면인 Index Page만 작성합니다.</p>
        <textarea id="finalUIUXInput">${escapeHtml(state.uiuxMerged)}</textarea>
      </section>
      ${renderAISelection({
        title: "Index Page 코드 생성 AI 서버",
        list: state.codeAIList,
        selectedIds: state.codeSelectedAIIds,
        attribute: "data-code-ai-id",
        helper: "React 기반 첫 화면 코드 생성에 적합한 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToUIUXOutputButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="codeRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(7);
  document.querySelector("#finalUIUXInput").addEventListener("input", (event) => {
    state.uiuxMerged = event.target.value;
  });
  bindAIButtons("[data-code-ai-id]", "code");
  document.querySelector("#backToUIUXOutputButton").addEventListener("click", () => {
    state.uiuxMerged = document.querySelector("#finalUIUXInput").value;
    state.page = 12;
    render();
  });
  document.querySelector("#codeRunButton").addEventListener("click", executeIndexPageAIs);
}

function renderIndexPageLinks() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("Index Page 생성 완료", "선택한 3개의 AI 서버가 첫 화면만 생성했습니다. 각 링크를 열어 Index Page를 비교해 주세요.")}
      <div class="preview-link-grid">
        ${state.indexPageOutputs.map((output, index) => `
          <article class="preview-link-card">
            <div>
              <span class="preview-style">${escapeHtml(output.style)}</span>
              <h2>${escapeHtml(output.aiName)}</h2>
              <p>${escapeHtml(output.summary)}</p>
            </div>
            <div class="index-card-actions">
              <button class="preview-link" type="button" data-index-preview-index="${index}">Index Page 열기 →</button>
              <button class="index-select-button ${state.finalIndexPageIndex === index ? "selected" : ""}" type="button" data-final-index-page-index="${index}">${state.finalIndexPageIndex === index ? "최종안 선택됨" : "최종안 선택"}</button>
            </div>
          </article>
        `).join("")}
      </div>
      <div class="action-row">
        <button class="secondary" id="backToCodeSelectionButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="confirmIndexPageButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(7);
  document.querySelectorAll("[data-index-preview-index]").forEach((button) => {
    button.addEventListener("click", () => {
      state.indexPreviewIndex = Number(button.dataset.indexPreviewIndex);
      state.page = 16;
      render();
    });
  });
  document.querySelectorAll("[data-final-index-page-index]").forEach((button) => {
    button.addEventListener("click", () => {
      state.finalIndexPageIndex = Number(button.dataset.finalIndexPageIndex);
      render();
    });
  });
  document.querySelector("#backToCodeSelectionButton").addEventListener("click", () => {
    state.page = 14;
    render();
  });
  document.querySelector("#confirmIndexPageButton").addEventListener("click", () => {
    state.page = 17;
    render();
  });
}

function renderIndexPagePreview() {
  const output = state.indexPageOutputs[state.indexPreviewIndex];
  const preview = output.preview;
  app.innerHTML = `
    <section class="page index-preview-page">
      ${renderHeading(`${output.aiName} Index Page`, `${output.summary} 생성 범위는 Index Page 하나입니다.`)}
      <section class="index-browser">
        <div class="wireframe-window-bar"><span></span><span></span><span></span></div>
        <div class="index-demo">
          <aside class="index-demo-sidebar">
            <strong>MVP 제작 단계</strong>
            ${preview.steps.map((step, index) => `<div class="${index === 0 ? "active" : ""}"><b>${index + 1}</b><span>${escapeHtml(step)}</span></div>`).join("")}
          </aside>
          <main class="index-demo-main">
            <div class="index-demo-brand"><b>${escapeHtml(preview.brand)}</b><span>${escapeHtml(preview.product)}</span></div>
            <section class="index-demo-hero">
              <h2>${escapeHtml(preview.headline)}</h2>
              <p>${escapeHtml(preview.description)}</p>
              <label>${escapeHtml(preview.inputLabel)}</label>
              <div class="index-demo-input"></div>
              <div class="index-demo-options">
                ${preview.aiOptions.map((option, index) => `<div class="${index < 3 ? "selected" : ""}"><b>${index + 1}</b><span>${escapeHtml(option)}</span><i>${index < 3 ? "선택됨" : "선택"}</i></div>`).join("")}
              </div>
              <button type="button">${escapeHtml(preview.cta)}</button>
            </section>
          </main>
        </div>
      </section>
      <div class="action-row"><button class="secondary" id="backToIndexLinksButton" type="button">Index Page 목록으로 돌아가기</button></div>
    </section>
  `;
  mountSidebar(7);
  document.querySelector("#backToIndexLinksButton").addEventListener("click", () => {
    state.page = 15;
    render();
  });
}

function renderFinalIndexPage() {
  const output = state.indexPageOutputs[state.finalIndexPageIndex];
  app.innerHTML = `
    <section class="page">
      ${renderHeading("최종 Index Page 확정", "선택한 Index Page를 최종안으로 확정했습니다. 아래 미리보기를 확인하고 작동 이미지와 영상 제작에 사용할 AI 엔진 3개를 선택해 주세요.")}
      ${renderCompactIndexPreview(output)}
      ${renderAISelection({
        title: "작동 이미지 및 영상 제작 AI 엔진",
        list: state.mediaAIList,
        selectedIds: state.mediaSelectedAIIds,
        attribute: "data-media-ai-id",
        helper: "제품 작동 이미지 5개와 데모 영상을 제작하기 적합한 순서입니다. 실행할 AI 엔진을 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToIndexPageListButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="mediaRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(8);
  bindAIButtons("[data-media-ai-id]", "media");
  document.querySelector("#backToIndexPageListButton").addEventListener("click", () => {
    state.page = 15;
    render();
  });
  document.querySelector("#mediaRunButton").addEventListener("click", executeMediaAIs);
}

function renderMediaGalleryPage() {
  const isStandard = state.plan === "standard";
  app.innerHTML = `
    <section class="page">
      ${renderHeading("미래 완성형 MVP 영상 제작 완료", "선택한 AI 엔진이 완성된 서비스의 작동 모습을 상상하여 데모 영상 3개를 구성했습니다. 썸네일을 눌러 약 10초 프리뷰를 확인해 주세요.")}
      <div class="media-gallery">
        ${state.mediaOutputs.map((output, index) => `
          <article class="media-card">
            <button class="media-thumbnail theme-${index}" type="button" data-media-preview-index="${index}" aria-label="${escapeHtml(output.aiName)} 영상 재생">
              <span class="media-kicker">FUTURE MVP DEMO</span>
              <strong>${escapeHtml(output.aiName)}</strong>
              <i class="media-play">▶</i>
              <small>00:10</small>
            </button>
            <div class="media-card-body">
              <h2>${escapeHtml(output.aiName)}</h2>
              <p>${escapeHtml(output.summary)}</p>
              ${output.taskId ? `<div class="media-live-status"><b>${escapeHtml(output.taskStatus || "PENDING")}</b><span>실제 AI 영상 작업</span></div>` : `<div class="media-live-status preview"><b>PREVIEW</b><span>로컬 모션 프리뷰</span></div>`}
              ${output.taskId && !output.videoUrl ? `<button class="preview-link" type="button" data-media-refresh-index="${index}">생성 상태 확인 →</button>` : ""}
              ${output.videoUrl ? `<a class="download-link" href="${escapeHtml(output.videoUrl)}" target="_blank" rel="noreferrer">실제 영상 다운로드</a>` : ""}
              <span class="fit">적합도 ${output.fit}%</span>
              <button class="index-select-button ${state.finalMediaIndex === index ? "selected" : ""}" type="button" data-final-media-index="${index}">${state.finalMediaIndex === index ? "최종 영상 선택됨" : "최종 영상 선택"}</button>
            </div>
          </article>
        `).join("")}
      </div>
      ${isStandard ? `<div class="plan-note">STANDARD 체크포인트: 최종 영상을 선택한 뒤 9~10단계 사업성 분석과 제안서 초안을 한 번에 생성합니다.</div>` : renderAISelection({
        title: "사업성 분석 AI 서버",
        list: state.businessAIList,
        selectedIds: state.businessSelectedAIIds,
        attribute: "data-business-ai-id",
        helper: "BM, 시장성, 기술성, 수익성, 리스크 분석에 적합한 순서입니다. 실행할 AI 서버를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToFinalIndexButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="${isStandard ? "standardFilesButton" : "businessRunButton"}" type="button">${isStandard ? "9~10단계 자동 실행" : "Submit"}</button>
      </div>
    </section>
  `;
  mountSidebar(8);
  document.querySelectorAll("[data-media-preview-index]").forEach((button) => {
    button.addEventListener("click", () => {
      state.mediaPreviewIndex = Number(button.dataset.mediaPreviewIndex);
      state.page = 19;
      render();
    });
  });
  document.querySelectorAll("[data-final-media-index]").forEach((button) => {
    button.addEventListener("click", () => {
      state.finalMediaIndex = Number(button.dataset.finalMediaIndex);
      render();
    });
  });
  document.querySelectorAll("[data-media-refresh-index]").forEach((button) => {
    button.addEventListener("click", () => refreshMediaTask(Number(button.dataset.mediaRefreshIndex)));
  });
  if (!isStandard) bindAIButtons("[data-business-ai-id]", "business");
  document.querySelector("#backToFinalIndexButton").addEventListener("click", () => {
    state.page = 17;
    render();
  });
  document.querySelector(isStandard ? "#standardFilesButton" : "#businessRunButton").addEventListener("click", isStandard ? () => runAutomation("standardFilesButton", "files", 9) : executeBusinessAIs);
}

function renderBusinessOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("AI 서버별 사업성 분석", "선택한 최종 영상과 프로젝트 정보를 기준으로 세 AI 서버가 작성한 사업성 분석입니다. 각 답변을 확인하고 직접 편집할 수 있습니다.")}
      ${renderSummary("AI 서버에 보낸 사업성 분석 요청", state.businessPrompt)}
      ${renderOutputGrid(state.businessOutputs, "data-business-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToMediaGalleryButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="businessMergeButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(9);
  bindOutputEditors("[data-business-output-index]", state.businessOutputs);
  document.querySelector("#backToMediaGalleryButton").addEventListener("click", () => {
    syncOutputEditors("[data-business-output-index]", state.businessOutputs, "businessOutputIndex");
    state.page = 18;
    render();
  });
  document.querySelector("#businessMergeButton").addEventListener("click", mergeBusinessOutputs);
}

function renderBusinessMergedPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("최종 사업성 분석 리포트", "Merge 서버가 세 AI의 분석을 하나의 리포트로 취합했습니다. 내용을 수정한 뒤 PPT 제작에 사용할 AI 3개를 선택해 주세요.")}
      <section class="merge-panel result-panel">
        <div class="result-badge">Merge Server Output</div>
        <h2>통합된 사업성 분석</h2>
        <p class="helper">이 내용은 선택한 PPT 제작 AI에 전달됩니다. 필요한 부분을 직접 편집할 수 있습니다.</p>
        <textarea id="businessMergedInput">${escapeHtml(state.businessMerged)}</textarea>
      </section>
      ${renderAISelection({
        title: "PPT 제작 AI",
        list: state.presentationAIList,
        selectedIds: state.presentationSelectedAIIds,
        attribute: "data-presentation-ai-id",
        helper: "사업 제안서 PPT 구성과 시각적 전달에 적합한 순서입니다. 실행할 PPT AI를 3개 선택해 주세요."
      })}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToBusinessOutputsButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="presentationRunButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(9);
  document.querySelector("#businessMergedInput").addEventListener("input", (event) => {
    state.businessMerged = event.target.value;
  });
  bindAIButtons("[data-presentation-ai-id]", "presentation");
  document.querySelector("#backToBusinessOutputsButton").addEventListener("click", () => {
    state.businessMerged = document.querySelector("#businessMergedInput").value;
    state.page = 20;
    render();
  });
  document.querySelector("#presentationRunButton").addEventListener("click", executePresentationAIs);
}

function renderPresentationOutputPage() {
  app.innerHTML = `
    <section class="page">
      ${renderHeading("AI별 PPT 제안서 초안", "선택한 3개의 PPT AI가 최종 사업성 리포트를 바탕으로 작성한 발표자료 구조입니다. 각 답변을 확인하고 직접 편집할 수 있습니다.")}
      ${renderSummary("PPT AI에 보낸 최종 리포트", state.presentationPrompt)}
      ${renderOutputGrid(state.presentationOutputs, "data-presentation-output-index")}
      <div id="errorBox"></div>
      <div class="action-row">
        <button class="secondary" id="backToBusinessMergedButton" type="button">이전 단계로 돌아가기</button>
        <button class="primary large" id="presentationFilesButton" type="button">Submit</button>
      </div>
    </section>
  `;
  mountSidebar(10);
  bindOutputEditors("[data-presentation-output-index]", state.presentationOutputs);
  document.querySelector("#backToBusinessMergedButton").addEventListener("click", () => {
    syncOutputEditors("[data-presentation-output-index]", state.presentationOutputs, "presentationOutputIndex");
    state.page = 21;
    render();
  });
  document.querySelector("#presentationFilesButton").addEventListener("click", loadPresentationFiles);
}

function renderPresentationFilesPage() {
  const selectedVideo = state.mediaOutputs[state.finalMediaIndex];
  const selectedVideoUrl = selectedVideo?.videoUrl || deliverableUrl("MAG-Future-MVP-Demo.mp4");
  const selectedVideoTarget = selectedVideo?.videoUrl ? ` target="_blank" rel="noreferrer"` : "";
  app.innerHTML = `
    <section class="page">
      ${renderHeading("제안서 초안 생성 완료", "앞서 진행한 모든 단계가 반영된 PPTX 제안서 3종과 최종 MVP 데모 영상을 내려받을 수 있습니다.")}
      <section class="delivery-panel">
        <div class="result-badge">PPTX Deliverables</div>
        <h2>최종 제안서 다운로드</h2>
        <p class="helper">버튼을 누르면 PPTX 파일이 바로 다운로드됩니다.</p>
        <div class="delivery-list">
          ${state.presentationFiles.map((file) => `
            <article class="delivery-item">
              <div>
                <h3>${escapeHtml(file.name)}</h3>
                <p>${escapeHtml(file.description)}</p>
              </div>
              <a class="download-link" href="${escapeHtml(deliverableUrl(file.file))}">PPTX 다운로드</a>
            </article>
          `).join("")}
        </div>
      </section>
      <section class="delivery-panel">
        <div class="result-badge">MP4 Deliverable</div>
        <h2>최종 영상 다운로드</h2>
        <p class="helper">${selectedVideo ? `${escapeHtml(selectedVideo.aiName)} 기반으로 구성한 미래 완성형 MVP 작동 영상입니다.` : "미래 완성형 MVP 작동 영상입니다."}</p>
        <div class="delivery-list">
          <article class="delivery-item">
            <div>
              <h3>MAG Future MVP Demo</h3>
              <p>최종 선택 영상 기반 10초 MVP 작동 데모 · MP4</p>
            </div>
            <a class="download-link" href="${escapeHtml(selectedVideoUrl)}"${selectedVideoTarget}>영상 다운로드</a>
          </article>
        </div>
      </section>
      <div class="action-row"><button class="secondary" id="backToPresentationDraftsButton" type="button">이전 단계로 돌아가기</button></div>
    </section>
  `;
  mountSidebar(10);
  document.querySelector("#backToPresentationDraftsButton").addEventListener("click", () => {
    state.page = 22;
    render();
  });
}

function renderMediaPreviewPage() {
  const output = state.mediaOutputs[state.mediaPreviewIndex];
  app.innerHTML = `
    <section class="page media-preview-page">
      ${renderHeading(`${output.aiName} 미래 MVP 데모`, output.videoUrl ? "실제 AI가 생성한 영상을 재생합니다." : "모든 기능이 완성되었을 때의 작동 모습을 약 10초 모션 프리뷰로 표현했습니다.")}
      ${output.videoUrl ? `<video class="generated-video" src="${escapeHtml(output.videoUrl)}" controls autoplay playsinline></video>` : `<section class="demo-video theme-${state.mediaPreviewIndex}">
        <div class="demo-video-top">
          <span>MAG FUTURE PRODUCT FILM</span>
          <b>00:10</b>
        </div>
        <div class="demo-stage">
          <div class="demo-scene scene-one">
            <span class="demo-eyebrow">IDEA TO PRODUCT</span>
            <h2>당신의 아이디어가<br>제품이 되는 순간</h2>
            <p>하나의 문장에서 시작되는 AI MVP 제작</p>
          </div>
          <div class="demo-scene scene-two">
            <span class="demo-eyebrow">MULTI AI ORCHESTRATION</span>
            <h2>최적의 AI가 동시에 분석합니다</h2>
            <div class="demo-ai-grid"><i></i><i></i><i></i><i></i><i></i><i></i></div>
          </div>
          <div class="demo-scene scene-three">
            <span class="demo-eyebrow">LIVE MVP GENERATION</span>
            <h2>기획부터 화면, 코드까지<br>하나의 흐름으로</h2>
            <div class="demo-product-window"><b></b><span></span><span></span><span></span></div>
          </div>
          <div class="demo-scene scene-four">
            <span class="demo-eyebrow">READY TO LAUNCH</span>
            <h2>MAG</h2>
            <p>MVP Auto Generator</p>
            <strong>Build what matters.</strong>
          </div>
        </div>
        <div class="demo-progress"><span></span></div>
      </section>`}
      <div class="action-row">
        <button class="secondary" id="backToMediaGalleryButton" type="button">영상 목록으로 돌아가기</button>
        ${output.videoUrl ? `<a class="download-link" href="${escapeHtml(output.videoUrl)}" target="_blank" rel="noreferrer">실제 영상 다운로드</a>` : `<button class="primary" id="replayMediaButton" type="button">다시 재생</button>`}
      </div>
    </section>
  `;
  mountSidebar(8);
  document.querySelector("#backToMediaGalleryButton").addEventListener("click", () => {
    state.page = 18;
    render();
  });
  const replayButton = document.querySelector("#replayMediaButton");
  if (replayButton) {
    replayButton.addEventListener("click", () => {
      const video = document.querySelector(".demo-video");
      video.classList.remove("playing");
      void video.offsetWidth;
      video.classList.add("playing");
    });
    document.querySelector(".demo-video").classList.add("playing");
  }
}

async function refreshMediaTask(index) {
  const output = state.mediaOutputs[index];
  if (!output?.taskId) return;
  const response = await fetch(relativeUrl(`/api/media-generation/tasks/${encodeURIComponent(output.taskId)}`));
  const task = await response.json();
  if (!response.ok) return showError(task.error || "영상 상태를 확인하지 못했습니다.");
  output.taskStatus = task.status || "UNKNOWN";
  output.videoUrl = task.output?.[0] || "";
  render();
}

function renderCompactIndexPreview(output) {
  const preview = output.preview;
  return `
    <section class="final-index-preview">
      <div class="final-index-preview-head">
        <div>
          <span class="preview-style">${escapeHtml(output.style)}</span>
          <h2>${escapeHtml(output.title)}</h2>
        </div>
        <span class="result-badge">Final Index Page</span>
      </div>
      <div class="final-index-canvas">
        <aside>
          ${preview.steps.map((step, index) => `<span class="${index === 0 ? "active" : ""}">${index + 1}. ${escapeHtml(step)}</span>`).join("")}
        </aside>
        <main>
          <strong>${escapeHtml(preview.brand)} <i>${escapeHtml(preview.product)}</i></strong>
          <h3>${escapeHtml(preview.headline)}</h3>
          <p>${escapeHtml(preview.description)}</p>
          <div class="final-index-input"></div>
          <div class="final-index-list">${preview.aiOptions.slice(0, 3).map((option) => `<span>${escapeHtml(option)}</span>`).join("")}</div>
          <button type="button">${escapeHtml(preview.cta)}</button>
        </main>
      </div>
    </section>
  `;
}

function renderUIUXPreviewPage() {
  const output = state.uiuxOutputs[state.uiuxPreviewIndex];
  app.innerHTML = `
    <section class="page">
      ${renderHeading(`${output.aiName} UI / UX 미리보기`, `${output.summary} 전체 화면이 어떤 구조로 이어지는지 와이어프레임으로 확인할 수 있습니다.`)}
      <section class="preview-overview">
        <div class="preview-overview-head">
          <div>
            <span class="preview-style">${escapeHtml(output.style)}</span>
            <h2>전체 화면 구성</h2>
          </div>
          <span class="fit">적합도 ${output.fit}%</span>
        </div>
        <div class="wireframe-grid">
          ${output.screens.map(renderWireframe).join("")}
        </div>
      </section>
      <div class="action-row"><button class="secondary" id="backToUIUXSummaryButton" type="button">UI / UX 요약으로 돌아가기</button></div>
    </section>
  `;
  mountSidebar(6);
  document.querySelector("#backToUIUXSummaryButton").addEventListener("click", () => {
    state.page = 12;
    render();
  });
}

function renderWireframe(screen) {
  return `
    <article class="wireframe-card">
      <div class="wireframe-window-bar"><span></span><span></span><span></span></div>
      <div class="wireframe-app">
        <aside class="wireframe-sidebar">
          ${Array.from({ length: 6 }, (_, index) => `<i class="${index < 3 ? "filled" : ""}"></i>`).join("")}
        </aside>
        <div class="wireframe-main">
          <span class="wireframe-eyebrow">${escapeHtml(screen.eyebrow)}</span>
          <h3>${escapeHtml(screen.title)}</h3>
          <p>${escapeHtml(screen.description)}</p>
          <div class="wireframe-blocks">
            ${screen.blocks.map((block, index) => `<div class="wireframe-block ${index === screen.blocks.length - 1 ? "accent" : ""}"><span></span><b>${escapeHtml(block)}</b></div>`).join("")}
          </div>
        </div>
      </div>
    </article>
  `;
}

function mountSidebar(activeStep) {
  const steps = [
    "문제 정의 및 목표",
    "타겟 / 페르소나",
    "사용자 시나리오",
    "핵심 기능 정의",
    "WorkFlow 구성",
    "UI / UX 화면 구성",
    "코드 생성 및 배포",
    "작동 이미지 및 영상",
    "사업성 리포트",
    "제안서 초안"
  ];
  const sidebar = document.createElement("aside");
  sidebar.className = "workflow-sidebar";
  sidebar.innerHTML = `
    <div class="workflow-sidebar-title">MVP 제작 단계</div>
    ${state.plan === "premium" ? `<p class="workflow-sidebar-helper">체크한 단계는 자동 진행합니다.</p>` : `<p class="workflow-sidebar-helper">${state.plan.toUpperCase()} 진행 모드</p>`}
    <ol class="workflow-steps">
      ${steps.map((label, index) => `
        <li class="workflow-step ${index + 1 === activeStep ? "active" : ""}">
          <span class="workflow-step-number">${index + 1}</span>
          <span>${escapeHtml(label)}</span>
          ${state.plan === "premium" ? `<input class="workflow-skip-checkbox" type="checkbox" data-premium-skip-step="${index + 1}" aria-label="${index + 1}단계 자동 진행" ${state.premiumSkipSteps.includes(index + 1) ? "checked" : ""}>` : ""}
        </li>
      `).join("")}
    </ol>
    <div class="workflow-elapsed">
      <span>전체 소요 시간</span>
      <strong data-elapsed-time>0초</strong>
    </div>
    <button class="ai-connection-toggle ${state.useLiveAI ? "live" : ""}" id="aiConnectionToggle" type="button" aria-pressed="${state.useLiveAI}">
      <span class="ai-connection-dot"></span>
      <span>
        <strong>${state.useLiveAI ? "실제 AI 연결 ON" : "가상 AI 연결 OFF"}</strong>
        <small>${state.useLiveAI ? "외부 API를 호출합니다." : "테스트 응답을 사용합니다."}</small>
      </span>
    </button>
  `;
  app.prepend(sidebar);
  updateElapsedTime();
  if (!window.magElapsedTimer) {
    window.magElapsedTimer = window.setInterval(updateElapsedTime, 1000);
  }
  document.querySelector("#aiConnectionToggle").addEventListener("click", () => {
    state.useLiveAI = !state.useLiveAI;
    render();
  });
  sidebar.querySelectorAll("[data-premium-skip-step]").forEach((checkbox) => {
    checkbox.addEventListener("change", () => {
      const step = Number(checkbox.dataset.premiumSkipStep);
      state.premiumSkipSteps = checkbox.checked
        ? [...new Set([...state.premiumSkipSteps, step])].sort((a, b) => a - b)
        : state.premiumSkipSteps.filter((value) => value !== step);
    });
  });
}

function formatElapsed(ms) {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 1) return `${seconds}초`;
  return `${minutes}분 ${String(seconds).padStart(2, "0")}초`;
}

function updateElapsedTime() {
  document.querySelectorAll("[data-elapsed-time]").forEach((element) => {
    element.textContent = formatElapsed(Date.now() - state.startedAt);
  });
}

function renderHeading(title, description) {
  return `<div class="page-heading"><h1>${escapeHtml(title)}</h1><p>${escapeHtml(description)}</p></div>`;
}

function renderSummary(title, content) {
  return `<div class="idea-summary"><strong>${escapeHtml(title)}</strong><br>${escapeHtml(content)}</div>`;
}

function renderAISelection({ title, list, selectedIds, attribute, helper }) {
  return `
    <section class="ai-selection">
      <div class="section-head">
        <div>
          <strong class="section-title">${escapeHtml(title)}</strong>
          <p class="helper">${escapeHtml(helper)}</p>
        </div>
        <span class="selected-count">${selectedIds.length} / 3 선택</span>
      </div>
      <div class="ai-list">${list.map((ai, index) => aiCard(ai, index, selectedIds, attribute)).join("")}</div>
    </section>
  `;
}

function aiCard(ai, index, selectedIds, attribute) {
  const selected = selectedIds.includes(ai.id);
  const connectionLabel = ai.connection
    ? ai.connection.configured ? "API 키 설정됨" : "API 키 필요"
    : "";
  return `
    <article class="ai-card ${selected ? "selected" : ""}">
      <span class="rank">${index + 1}</span>
      <div>
        <h3>${escapeHtml(ai.name)}</h3>
        <p>${escapeHtml(ai.description)}</p>
        ${ai.provider || ai.model ? `<div class="ai-meta">${ai.provider ? `<span>${escapeHtml(ai.provider)}</span>` : ""}${ai.model ? `<code>${escapeHtml(ai.model)}</code>` : ""}${connectionLabel ? `<b class="${ai.connection.configured ? "connected" : "needs-key"}">${connectionLabel}</b>` : ""}</div>` : ""}
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
    const keys = { problem: "problemAiId", persona: "personaAiId", scenario: "scenarioAiId", feature: "featureAiId", workflow: "workflowAiId", uiux: "uiuxAiId", code: "codeAiId", media: "mediaAiId", business: "businessAiId", presentation: "presentationAiId" };
    button.addEventListener("click", () => toggleAI(button.dataset[keys[type]], type));
  });
}

function toggleAI(id, type) {
  const keys = { problem: "selectedAIIds", persona: "personaSelectedAIIds", scenario: "scenarioSelectedAIIds", feature: "featureSelectedAIIds", workflow: "workflowSelectedAIIds", uiux: "uiuxSelectedAIIds", code: "codeSelectedAIIds", media: "mediaSelectedAIIds", business: "businessSelectedAIIds", presentation: "presentationSelectedAIIds" };
  const key = keys[type];
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
      const key = selector.includes("problem") ? "problemOutputIndex" : selector.includes("persona") ? "personaOutputIndex" : selector.includes("scenario") ? "scenarioOutputIndex" : selector.includes("feature") ? "featureOutputIndex" : selector.includes("workflow") ? "workflowOutputIndex" : selector.includes("business") ? "businessOutputIndex" : selector.includes("presentation") ? "presentationOutputIndex" : "uiuxOutputIndex";
      outputs[Number(textarea.dataset[key])].content = textarea.value;
    });
  });
}

async function executeSelectedAIs() {
  if (!state.idea.trim()) return showError("아이디어를 입력해 주세요.");
  if (state.plan === "free") return runAutomation("executeButton", "files", 1);
  if (state.plan === "standard") return runAutomation("executeButton", "feature", 1);
  const premiumAutoTarget = consecutivePremiumAutoTarget(1);
  if (premiumAutoTarget) return runAutomation("executeButton", premiumAutoTarget, 1);
  if (state.selectedAIIds.length !== 3) return showError("AI를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "executeButton",
    loadingText: "AI 실행 중...",
    url: "/api/problem-definition/run",
    body: { idea: state.idea, selectedAIIds: state.selectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: (data) => {
      state.outputs = data.outputs;
      state.page = 2;
    }
  });
}

function consecutivePremiumAutoTarget(startStep) {
  const targets = ["problem", "persona", "scenario", "feature", "workflow", "uiux", "code", "media", "business", "files"];
  let lastChecked = 0;
  for (let step = startStep; step <= 10 && state.premiumSkipSteps.includes(step); step += 1) lastChecked = step;
  return lastChecked ? targets[lastChecked - 1] : "";
}

async function advancePremium(nextStep, defaultPage) {
  const target = state.plan === "premium" ? consecutivePremiumAutoTarget(nextStep) : "";
  if (target) {
    await runAutomatedPipeline(target, nextStep);
  } else {
    state.page = defaultPage;
  }
}

function firstThree(list) {
  return list.slice(0, 3).map((ai) => ai.id);
}

async function postJSON(url, body) {
  const response = await fetch(relativeUrl(url), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error);
  return data;
}

async function runAutomation(buttonId, stopAt, startStage) {
  const button = document.querySelector(`#${buttonId}`);
  if (button) {
    button.disabled = true;
    button.textContent = "자동 제작 진행 중...";
  }
  try {
    await runAutomatedPipeline(stopAt, startStage);
    render();
  } catch (error) {
    showError(error.message);
    if (button) {
      button.disabled = false;
      button.textContent = "다시 실행";
    }
  }
}

async function runAutomatedPipeline(stopAt = "files", startStage = 1) {
  if (startStage <= 1) {
    state.selectedAIIds = firstThree(state.aiList);
    const problem = await postJSON("/api/problem-definition/run", { idea: state.idea, selectedAIIds: state.selectedAIIds, useLiveAI: state.useLiveAI });
    state.outputs = problem.outputs;
    state.merged = (await postJSON("/api/problem-definition/merge", { idea: state.idea, outputs: state.outputs })).merged;
    if (stopAt === "problem") return void (state.page = 3);
  }
  if (startStage <= 2) {
    state.personaSelectedAIIds = firstThree(state.personaAIList);
    const persona = await postJSON("/api/persona/run", { idea: state.idea, problemDefinition: state.merged, selectedAIIds: state.personaSelectedAIIds, useLiveAI: state.useLiveAI });
    state.personaOutputs = persona.outputs;
    state.personaPrompt = persona.prompt;
    state.personaMerged = (await postJSON("/api/persona/merge", { idea: state.idea, problemDefinition: state.merged, outputs: state.personaOutputs })).merged;
    if (stopAt === "persona") return void (state.page = 5);
  }
  if (startStage <= 3) {
    state.scenarioSelectedAIIds = firstThree(state.scenarioAIList);
    const scenario = await postJSON("/api/scenario/run", { idea: state.idea, personaDefinition: state.personaMerged, selectedAIIds: state.scenarioSelectedAIIds, useLiveAI: state.useLiveAI });
    state.scenarioOutputs = scenario.outputs;
    state.scenarioPrompt = scenario.prompt;
    state.scenarioMerged = (await postJSON("/api/scenario/merge", { idea: state.idea, personaDefinition: state.personaMerged, outputs: state.scenarioOutputs })).merged;
    if (stopAt === "scenario") return void (state.page = 7);
  }
  if (startStage <= 4) {
    state.featureSelectedAIIds = firstThree(state.featureAIList);
    const feature = await postJSON("/api/features/run", { idea: state.idea, scenarioDefinition: state.scenarioMerged, selectedAIIds: state.featureSelectedAIIds, useLiveAI: state.useLiveAI });
    state.featureOutputs = feature.outputs;
    state.featurePrompt = feature.prompt;
    state.featureMerged = (await postJSON("/api/features/merge", { idea: state.idea, scenarioDefinition: state.scenarioMerged, outputs: state.featureOutputs })).merged;
    if (stopAt === "feature") return void (state.page = 9);
  }
  if (startStage <= 5) {
    state.workflowSelectedAIIds = firstThree(state.workflowAIList);
    const workflow = await postJSON("/api/workflow-design/run", { idea: state.idea, featureDefinition: state.featureMerged, selectedAIIds: state.workflowSelectedAIIds, useLiveAI: state.useLiveAI });
    state.workflowOutputs = workflow.outputs;
    state.workflowPrompt = workflow.prompt;
    state.workflowMerged = (await postJSON("/api/workflow-design/merge", { idea: state.idea, featureDefinition: state.featureMerged, outputs: state.workflowOutputs })).merged;
    if (stopAt === "workflow") return void (state.page = 11);
  }
  if (startStage <= 6) {
    state.uiuxSelectedAIIds = firstThree(state.uiuxAIList);
    const uiux = await postJSON("/api/uiux-design/run", { idea: state.idea, workflowDefinition: state.workflowMerged, selectedAIIds: state.uiuxSelectedAIIds, useLiveAI: state.useLiveAI });
    state.uiuxOutputs = uiux.outputs;
    state.uiuxPrompt = uiux.prompt;
    state.uiuxMerged = (await postJSON("/api/uiux-design/merge", { idea: state.idea, workflowDefinition: state.workflowMerged, outputs: state.uiuxOutputs })).merged;
    if (stopAt === "uiux") return void (state.page = 12);
  }
  if (startStage <= 7) {
    state.codeSelectedAIIds = firstThree(state.codeAIList);
    state.indexPageOutputs = (await postJSON("/api/code-generation/index-page", { idea: state.idea, uiuxDefinition: state.uiuxMerged, selectedAIIds: state.codeSelectedAIIds, useLiveAI: state.useLiveAI })).outputs;
    state.finalIndexPageIndex = 0;
    if (stopAt === "code") return void (state.page = 15);
  }
  if (startStage <= 8) {
    state.mediaSelectedAIIds = firstThree(state.mediaAIList);
    state.mediaOutputs = (await postJSON("/api/media-generation/run", {
      idea: state.idea,
      indexPage: state.indexPageOutputs[state.finalIndexPageIndex]?.preview,
      selectedAIIds: state.mediaSelectedAIIds,
      useLiveAI: state.useLiveAI
    })).outputs;
    state.finalMediaIndex = 0;
    if (stopAt === "media") return void (state.page = 18);
  }
  if (startStage <= 9) {
    state.businessSelectedAIIds = firstThree(state.businessAIList);
    const business = await postJSON("/api/business-analysis/run", {
      idea: state.idea,
      indexPage: state.indexPageOutputs[state.finalIndexPageIndex]?.preview,
      selectedVideo: state.mediaOutputs[state.finalMediaIndex],
      selectedAIIds: state.businessSelectedAIIds,
      useLiveAI: state.useLiveAI
    });
    state.businessOutputs = business.outputs;
    state.businessPrompt = business.prompt;
    state.businessMerged = (await postJSON("/api/business-analysis/merge", { idea: state.idea, outputs: state.businessOutputs })).merged;
    if (stopAt === "business") return void (state.page = 21);
  }
  state.presentationSelectedAIIds = firstThree(state.presentationAIList);
  const presentation = await postJSON("/api/presentation-generation/run", {
    idea: state.idea,
    businessReport: state.businessMerged,
    selectedAIIds: state.presentationSelectedAIIds,
    useLiveAI: state.useLiveAI
  });
  state.presentationOutputs = presentation.outputs;
  state.presentationPrompt = presentation.prompt;
  const filesResponse = await fetch(relativeUrl("/api/presentation-generation/files"));
  const files = await filesResponse.json();
  if (!filesResponse.ok) throw new Error(files.error);
  state.presentationFiles = files;
  state.page = 23;
}

async function mergeOutputs() {
  syncOutputEditors("[data-problem-output-index]", state.outputs, "problemOutputIndex");
  await submitJSON({
    buttonId: "mergeButton",
    loadingText: "취합 중...",
    url: "/api/problem-definition/merge",
    body: { idea: state.idea, outputs: state.outputs },
    onSuccess: async (data) => {
      state.merged = data.merged;
      await advancePremium(2, 3);
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
    body: { idea: state.idea, problemDefinition: state.merged, selectedAIIds: state.personaSelectedAIIds, useLiveAI: state.useLiveAI },
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
    onSuccess: async (data) => {
      state.personaMerged = data.merged;
      await advancePremium(3, 5);
    }
  });
}

async function executeScenarioAIs() {
  const personaMergedInput = document.querySelector("#personaMergedInput");
  if (personaMergedInput) state.personaMerged = personaMergedInput.value;
  if (!state.personaMerged.trim()) return showError("타겟 및 페르소나 정의를 입력해 주세요.");
  if (state.scenarioSelectedAIIds.length !== 3) return showError("AI 서버를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "scenarioRunButton",
    loadingText: "시나리오 작성 중...",
    url: "/api/scenario/run",
    body: { idea: state.idea, personaDefinition: state.personaMerged, selectedAIIds: state.scenarioSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: (data) => {
      state.scenarioOutputs = data.outputs;
      state.scenarioPrompt = data.prompt;
      state.page = 6;
    }
  });
}

async function mergeScenarioOutputs() {
  syncOutputEditors("[data-scenario-output-index]", state.scenarioOutputs, "scenarioOutputIndex");
  await submitJSON({
    buttonId: "scenarioMergeButton",
    loadingText: "Merge 서버 취합 중...",
    url: "/api/scenario/merge",
    body: { idea: state.idea, personaDefinition: state.personaMerged, outputs: state.scenarioOutputs },
    onSuccess: async (data) => {
      state.scenarioMerged = data.merged;
      await advancePremium(4, 7);
    }
  });
}

async function executeFeatureAIs() {
  const scenarioMergedInput = document.querySelector("#scenarioMergedInput");
  if (scenarioMergedInput) state.scenarioMerged = scenarioMergedInput.value;
  if (!state.scenarioMerged.trim()) return showError("사용자 시나리오를 입력해 주세요.");
  if (state.featureSelectedAIIds.length !== 3) return showError("AI 서버를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "featureRunButton",
    loadingText: "핵심 기능 정의 중...",
    url: "/api/features/run",
    body: { idea: state.idea, scenarioDefinition: state.scenarioMerged, selectedAIIds: state.featureSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: (data) => {
      state.featureOutputs = data.outputs;
      state.featurePrompt = data.prompt;
      state.page = 8;
    }
  });
}

async function mergeFeatureOutputs() {
  syncOutputEditors("[data-feature-output-index]", state.featureOutputs, "featureOutputIndex");
  await submitJSON({
    buttonId: "featureMergeButton",
    loadingText: "Merge 서버 취합 중...",
    url: "/api/features/merge",
    body: { idea: state.idea, scenarioDefinition: state.scenarioMerged, outputs: state.featureOutputs },
    onSuccess: async (data) => {
      state.featureMerged = data.merged;
      await advancePremium(5, 9);
    }
  });
}

async function executeWorkflowAIs() {
  const featureMergedInput = document.querySelector("#featureMergedInput");
  if (featureMergedInput) state.featureMerged = featureMergedInput.value;
  if (!state.featureMerged.trim()) return showError("핵심 기능 정의를 입력해 주세요.");
  if (state.workflowSelectedAIIds.length !== 3) return showError("AI 서버를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "workflowRunButton",
    loadingText: "WorkFlow 설계 중...",
    url: "/api/workflow-design/run",
    body: { idea: state.idea, featureDefinition: state.featureMerged, selectedAIIds: state.workflowSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: (data) => {
      state.workflowOutputs = data.outputs;
      state.workflowPrompt = data.prompt;
      state.page = 10;
    }
  });
}

async function mergeWorkflowOutputs() {
  syncOutputEditors("[data-workflow-output-index]", state.workflowOutputs, "workflowOutputIndex");
  await submitJSON({
    buttonId: "workflowMergeButton",
    loadingText: "Merge 서버 취합 중...",
    url: "/api/workflow-design/merge",
    body: { idea: state.idea, featureDefinition: state.featureMerged, outputs: state.workflowOutputs },
    onSuccess: async (data) => {
      state.workflowMerged = data.merged;
      await advancePremium(6, 11);
    }
  });
}

async function executeUIUXAIs() {
  const workflowMergedInput = document.querySelector("#workflowMergedInput");
  if (workflowMergedInput) state.workflowMerged = workflowMergedInput.value;
  if (!state.workflowMerged.trim()) return showError("WorkFlow 구성을 입력해 주세요.");
  if (state.uiuxSelectedAIIds.length !== 3) return showError("AI 엔진을 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "uiuxRunButton",
    loadingText: "UI / UX 화면 구성 중...",
    url: "/api/uiux-design/run",
    body: { idea: state.idea, workflowDefinition: state.workflowMerged, selectedAIIds: state.uiuxSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: async (data) => {
      state.uiuxOutputs = data.outputs;
      state.uiuxPrompt = data.prompt;
      await mergeUIUXOutputs();
    }
  });
}

async function mergeUIUXOutputs() {
  await submitJSON({
    buttonId: "uiuxRunButton",
    loadingText: "UI / UX 구성 취합 중...",
    url: "/api/uiux-design/merge",
    body: { idea: state.idea, workflowDefinition: state.workflowMerged, outputs: state.uiuxOutputs },
    onSuccess: (data) => {
      state.uiuxMerged = data.merged;
      state.page = 12;
    }
  });
}

async function executeIndexPageAIs() {
  const finalUIUXInput = document.querySelector("#finalUIUXInput");
  if (finalUIUXInput) state.uiuxMerged = finalUIUXInput.value;
  if (!state.uiuxMerged.trim()) return showError("확정된 UI / UX 화면 구성을 입력해 주세요.");
  if (state.codeSelectedAIIds.length !== 3) return showError("AI 서버를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "codeRunButton",
    loadingText: "Index Page 생성 중...",
    url: "/api/code-generation/index-page",
    body: { idea: state.idea, uiuxDefinition: state.uiuxMerged, selectedAIIds: state.codeSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: async (data) => {
      state.indexPageOutputs = data.outputs;
      await advancePremium(8, 15);
    }
  });
}

async function executeMediaAIs() {
  if (state.mediaSelectedAIIds.length !== 3) return showError("AI 엔진을 정확히 3개 선택해 주세요.");
  const finalIndexPage = state.indexPageOutputs[state.finalIndexPageIndex]?.preview;
  await submitJSON({
    buttonId: "mediaRunButton",
    loadingText: "이미지 및 영상 기획 중...",
    url: "/api/media-generation/run",
    body: { idea: state.idea, indexPage: finalIndexPage, selectedAIIds: state.mediaSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: async (data) => {
      state.mediaOutputs = data.outputs;
      await advancePremium(9, 18);
    }
  });
}

async function executeBusinessAIs() {
  if (state.businessSelectedAIIds.length !== 3) return showError("AI 서버를 정확히 3개 선택해 주세요.");
  const finalIndexPage = state.indexPageOutputs[state.finalIndexPageIndex]?.preview;
  const selectedVideo = state.mediaOutputs[state.finalMediaIndex];
  await submitJSON({
    buttonId: "businessRunButton",
    loadingText: "사업성 분석 중...",
    url: "/api/business-analysis/run",
    body: { idea: state.idea, indexPage: finalIndexPage, selectedVideo, selectedAIIds: state.businessSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: (data) => {
      state.businessOutputs = data.outputs;
      state.businessPrompt = data.prompt;
      state.page = 20;
    }
  });
}

async function mergeBusinessOutputs() {
  syncOutputEditors("[data-business-output-index]", state.businessOutputs, "businessOutputIndex");
  await submitJSON({
    buttonId: "businessMergeButton",
    loadingText: "Merge 서버 취합 중...",
    url: "/api/business-analysis/merge",
    body: { idea: state.idea, outputs: state.businessOutputs },
    onSuccess: async (data) => {
      state.businessMerged = data.merged;
      await advancePremium(10, 21);
    }
  });
}

async function executePresentationAIs() {
  const businessMergedInput = document.querySelector("#businessMergedInput");
  if (businessMergedInput) state.businessMerged = businessMergedInput.value;
  if (!state.businessMerged.trim()) return showError("최종 사업성 리포트를 입력해 주세요.");
  if (state.presentationSelectedAIIds.length !== 3) return showError("PPT AI를 정확히 3개 선택해 주세요.");
  await submitJSON({
    buttonId: "presentationRunButton",
    loadingText: "PPT 제안서 구성 중...",
    url: "/api/presentation-generation/run",
    body: { idea: state.idea, businessReport: state.businessMerged, selectedAIIds: state.presentationSelectedAIIds, useLiveAI: state.useLiveAI },
    onSuccess: (data) => {
      state.presentationOutputs = data.outputs;
      state.presentationPrompt = data.prompt;
      state.page = 22;
    }
  });
}

async function loadPresentationFiles() {
  syncOutputEditors("[data-presentation-output-index]", state.presentationOutputs, "presentationOutputIndex");
  const button = document.querySelector("#presentationFilesButton");
  button.disabled = true;
  button.textContent = "파일 준비 중...";
  try {
    const response = await fetch(relativeUrl("/api/presentation-generation/files"));
    const data = await response.json();
    if (!response.ok) throw new Error(data.error);
    state.presentationFiles = data;
    state.page = 23;
    render();
  } catch (error) {
    showError(error.message);
    button.disabled = false;
    button.textContent = "Submit";
  }
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
    const response = await fetch(relativeUrl(url), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error);
    await onSuccess(data);
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
