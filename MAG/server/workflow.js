export const providers = [
  { id: "founder-ai", name: "Founder AI", lens: "customer problem and founder clarity" },
  { id: "market-ai", name: "Market AI", lens: "market, business model, and feasibility" },
  { id: "product-ai", name: "Product AI", lens: "product, UX, and implementation" }
];

export const stages = [
  ["input-analysis", "Input analysis", "Extract the business domain, core problem, target, and MVP type.", true],
  ["problem-goal", "Problem definition and goals", "Write the current problem, limitations, goals, and expected effect.", false],
  ["persona", "Target and persona analysis", "Define primary targets, personas, needs, and pain points.", false],
  ["scenario", "User scenario", "Describe before, trigger, usage flow, result, and after state.", true],
  ["features", "Core feature definition", "Classify required, optional, later, and excluded features.", false],
  ["workflow", "Workflow composition", "Build user, admin, data, AI, and delivery flows.", true],
  ["ui-ux", "UI/UX screen composition", "Define screens, inputs, CTAs, and navigation.", false],
  ["code-deploy", "Code generation and deployment plan", "Define code modules, build path, and deployment target.", true],
  ["capture-video", "Working-image capture and video plan", "Define screenshots and a polished final demo-video flow.", true],
  ["business-report", "Business report", "Draft the final report structure and evidence.", false],
  ["bm-analysis", "BM and feasibility analysis", "Analyze revenue model, market, technology, profitability, and risks.", false],
  ["competitor-analysis", "Similar-service comparison", "Compare builders, chatbots, agencies, and the proposed product.", false],
  ["admin-review", "Admin review", "Check logic, scope, omissions, and delivery readiness.", true],
  ["delivery", "Delivery package", "List final files, output paths, and follow-up actions.", false]
].map(([id, title, goal, skippable]) => ({ id, title, goal, skippable }));

