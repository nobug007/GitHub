const suggestions = {
  "founder-ai": [
    "Lead with a concrete user pain and a visible before/after change.",
    "Keep the MVP narrow enough to explain in one sentence.",
    "Make the user scenario easy for a reviewer to understand."
  ],
  "market-ai": [
    "Tie the output to a measurable validation signal and a clear buyer.",
    "Separate initial package revenue from later B2B expansion.",
    "Compare substitutes and state why this package is easier to buy."
  ],
  "product-ai": [
    "Keep the first build template-driven and observable.",
    "Show inputs, outputs, merge status, and generated files in the UI.",
    "Split operational features from proposal-only MVP features."
  ]
};

export async function askMockProvider({ provider, stage, prompt, project, stageIndex }) {
  await sleep(15);
  const message = suggestions[provider.id][stageIndex % suggestions[provider.id].length];
  return [
    `Provider: ${provider.name}`,
    `Lens: ${provider.lens}`,
    `Stage: ${stage.title}`,
    `Goal: ${stage.goal}`,
    `Recommendation: ${message}`,
    `Project: ${project.idea}`,
    `Prompt excerpt: ${prompt.slice(0, 180)}`
  ].join("\n");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

