// Script to generate high quality Excalidraw diagrams for ajentic-sre
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const CLI = '/home/santiago/mcp/bin/excalidraw-mcp';

function runCli(cmd, input) {
    const opts = { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] };
    if (input) {
        return execSync(`${CLI} ${cmd}`, { ...opts, input });
    }
    return execSync(`${CLI} ${cmd}`, opts);
}

function clear() {
    runCli('clear --yes');
}

function add(elements) {
    runCli('add -', JSON.stringify(elements));
}

function exportDiagram(name) {
    const dir = path.join(__dirname);
    const excalidrawPath = path.join(dir, `${name}.excalidraw`);
    const svgPath = path.join(dir, `${name}.svg`);
    const pngPath = path.join(dir, `${name}.png`);

    runCli(`export --out ${excalidrawPath}`);
    runCli(`screenshot --out ${svgPath} --format svg`);
    runCli(`screenshot --out ${pngPath} --format png --scale 2`);
    console.log(`Exported ${name}: .excalidraw, .svg, .png`);
}

function connectBoxes(id, fromBox, toBox, options = {}) {
    // Determine attachment edges
    let fromX, fromY, toX, toY;
    if (toBox.x > fromBox.x + fromBox.width) {
        // Horizontal left-to-right
        fromX = fromBox.x + fromBox.width;
        fromY = fromBox.y + fromBox.height / 2;
        toX = toBox.x;
        toY = toBox.y + toBox.height / 2;
    } else if (toBox.y > fromBox.y + fromBox.height) {
        // Vertical top-to-bottom
        fromX = fromBox.x + fromBox.width / 2;
        fromY = fromBox.y + fromBox.height;
        toX = toBox.x + toBox.width / 2;
        toY = toBox.y;
    } else if (toBox.x + toBox.width < fromBox.x) {
        // Horizontal right-to-left
        fromX = fromBox.x;
        fromY = fromBox.y + fromBox.height / 2;
        toX = toBox.x + toBox.width;
        toY = toBox.y + toBox.height / 2;
    } else {
        // Vertical bottom-to-top
        fromX = fromBox.x + fromBox.width / 2;
        fromY = fromBox.y;
        toX = toBox.x + toBox.width / 2;
        toY = toBox.y + toBox.height;
    }

    const dx = toX - fromX;
    const dy = toY - fromY;

    return {
        id,
        type: 'arrow',
        x: fromX,
        y: fromY,
        width: Math.abs(dx) || 1,
        height: Math.abs(dy) || 1,
        points: [[0, 0], [dx, dy]],
        startElementId: fromBox.id,
        endElementId: toBox.id,
        strokeColor: options.strokeColor || '#868e96',
        strokeWidth: options.strokeWidth || 2,
        strokeStyle: options.strokeStyle || 'solid',
        roundness: { type: 2 },
        ...options
    };
}

// ==========================================
// 1. SYSTEM OVERVIEW
// ==========================================
function buildSystemOverview() {
    clear();

    const boxes = {
        inWebhooks: {
            id: 'in-webhooks',
            type: 'rectangle',
            x: 65,
            y: 190,
            width: 210,
            height: 75,
            backgroundColor: '#ffc9c9',
            strokeColor: '#e03131',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Alert Webhooks\nCloudWatch, Azure,\nAlertmanager (/alerts)'
        },
        inMcp: {
            id: 'in-mcp',
            type: 'rectangle',
            x: 65,
            y: 290,
            width: 210,
            height: 75,
            backgroundColor: '#eebefa',
            strokeColor: '#9c36b5',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'MCP Protocol Server\nstdio & SSE (/mcp)\nClaude / Cursor Agents'
        },
        inRest: {
            id: 'in-rest',
            type: 'rectangle',
            x: 65,
            y: 390,
            width: 210,
            height: 75,
            backgroundColor: '#a5d8ff',
            strokeColor: '#1971c2',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'JAX-RS REST API\n/scenarios, /simulate,\n/agent/ask, /schedules'
        },
        inCron: {
            id: 'in-cron',
            type: 'rectangle',
            x: 65,
            y: 490,
            width: 210,
            height: 75,
            backgroundColor: '#d0ebff',
            strokeColor: '#339af0',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Cron Drill Engine\nDrillRunner / Schedules\nRecurring Chaos Tests'
        },

        coreNormalizer: {
            id: 'core-normalizer',
            type: 'rectangle',
            x: 370,
            y: 190,
            width: 220,
            height: 75,
            backgroundColor: '#ffd8a8',
            strokeColor: '#e8590c',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Alert Normalizer\nSeverity Normalization\nINFO < WARN < ERR < CRIT'
        },
        coreAgent: {
            id: 'core-agent',
            type: 'rectangle',
            x: 640,
            y: 190,
            width: 220,
            height: 75,
            backgroundColor: '#eebefa',
            strokeColor: '#9c36b5',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'ReAct Agent Loop\nThought → Action → Obs\nMax 5 Bounded Turns'
        },
        coreGuardrails: {
            id: 'core-guardrails',
            type: 'rectangle',
            x: 370,
            y: 290,
            width: 220,
            height: 85,
            backgroundColor: '#ffe066',
            strokeColor: '#f59f00',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Safety & Masking\n• SecretScrubber (Tokens)\n• PIIMasker (IPs/Emails)\n• Context Budgeting'
        },
        coreTools: {
            id: 'core-tools',
            type: 'rectangle',
            x: 640,
            y: 290,
            width: 220,
            height: 85,
            backgroundColor: '#b2f2bb',
            strokeColor: '#2f9e44',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Tool Registry & Gate\nMutation Policies:\nallow | deny | require-approval\nOperator Approval Gate'
        },
        coreState: {
            id: 'core-state',
            type: 'rectangle',
            x: 370,
            y: 400,
            width: 490,
            height: 65,
            backgroundColor: '#99e9f2',
            strokeColor: '#0c8599',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Durable State Store & Incident Memory\nFile-backed store (.ajentic/state.json) | Incident Context | Locking'
        },
        coreHmac: {
            id: 'core-hmac',
            type: 'rectangle',
            x: 370,
            y: 490,
            width: 490,
            height: 65,
            backgroundColor: '#ffd8a8',
            strokeColor: '#e8590c',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'HMAC-SHA256 Connector Client\nTimestamped + Nonce Signed Wire Protocol (Zero Agent Credentials)'
        },

        extLlm: {
            id: 'ext-llm',
            type: 'rectangle',
            x: 950,
            y: 190,
            width: 260,
            height: 75,
            backgroundColor: '#eebefa',
            strokeColor: '#9c36b5',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'LLM Provider / Router\nLocal router (127.0.0.1:9931)\nor Cloud OpenAI Endpoint'
        },
        extSidecar: {
            id: 'ext-sidecar',
            type: 'rectangle',
            x: 950,
            y: 290,
            width: 260,
            height: 85,
            backgroundColor: '#ffd8a8',
            strokeColor: '#e8590c',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Credentialed Sidecar Proxy\nValidates HMAC + Replay Nonce\nHolds Kube/AWS/GH credentials\nEmits Structured Audit Logs'
        },
        extInfra: {
            id: 'ext-infra',
            type: 'rectangle',
            x: 950,
            y: 400,
            width: 260,
            height: 65,
            backgroundColor: '#b2f2bb',
            strokeColor: '#2f9e44',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Target Infrastructure\nKubernetes, Pods, Logs, Metrics'
        },
        extGithub: {
            id: 'ext-github',
            type: 'rectangle',
            x: 950,
            y: 490,
            width: 260,
            height: 65,
            backgroundColor: '#a5d8ff',
            strokeColor: '#1971c2',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'GitHub API Service\nAutomated Git Branch & PR\nRoot-cause remediation diffs'
        }
    };

    const elements = [
        // Title
        {
            id: 'title',
            type: 'text',
            x: 380,
            y: 40,
            text: 'ajentic-sre — Architecture & System Overview',
            fontSize: 22,
            fontFamily: 'cascadia',
            strokeColor: '#1e1e1e'
        },
        {
            id: 'subtitle',
            type: 'text',
            x: 410,
            y: 75,
            text: 'Quarkus 3.40 LTS | Java 25 | MCP Protocol | Zero-Credential Sidecar Security',
            fontSize: 14,
            fontFamily: 'cascadia',
            strokeColor: '#495057'
        },

        // --- ZONE 1: INGRESS ---
        {
            id: 'zone-ingress',
            type: 'rectangle',
            x: 40,
            y: 120,
            width: 260,
            height: 480,
            backgroundColor: '#f8f9fa',
            strokeColor: '#ced4da',
            strokeStyle: 'dashed',
            fillStyle: 'solid',
            roundness: { type: 3 }
        },
        {
            id: 'zone-ingress-label',
            type: 'text',
            x: 60,
            y: 135,
            text: 'INGRESS & CONTROL',
            fontSize: 16,
            strokeColor: '#495057',
            fontFamily: 'cascadia'
        },
        boxes.inWebhooks,
        boxes.inMcp,
        boxes.inRest,
        boxes.inCron,

        // --- ZONE 2: CORE PLATFORM ---
        {
            id: 'zone-core',
            type: 'rectangle',
            x: 340,
            y: 120,
            width: 550,
            height: 480,
            backgroundColor: '#f1f3f5',
            strokeColor: '#adb5bd',
            strokeStyle: 'solid',
            fillStyle: 'solid',
            roundness: { type: 3 }
        },
        {
            id: 'zone-core-label',
            type: 'text',
            x: 360,
            y: 135,
            text: 'AJENTIC-SRE CORE RUNTIME (JVM)',
            fontSize: 16,
            strokeColor: '#212529',
            fontFamily: 'cascadia'
        },
        boxes.coreNormalizer,
        boxes.coreAgent,
        boxes.coreGuardrails,
        boxes.coreTools,
        boxes.coreState,
        boxes.coreHmac,

        // --- ZONE 3: EXTERNAL & INFRA ---
        {
            id: 'zone-ext',
            type: 'rectangle',
            x: 920,
            y: 120,
            width: 320,
            height: 480,
            backgroundColor: '#f8f9fa',
            strokeColor: '#ced4da',
            strokeStyle: 'dashed',
            fillStyle: 'solid',
            roundness: { type: 3 }
        },
        {
            id: 'zone-ext-label',
            type: 'text',
            x: 940,
            y: 135,
            text: 'EXTERNAL INFRASTRUCTURE',
            fontSize: 16,
            strokeColor: '#495057',
            fontFamily: 'cascadia'
        },
        boxes.extLlm,
        boxes.extSidecar,
        boxes.extInfra,
        boxes.extGithub,

        // --- ARROWS ---
        connectBoxes('a1', boxes.inWebhooks, boxes.coreNormalizer, { strokeColor: '#e03131', strokeWidth: 2 }),
        connectBoxes('a2', boxes.coreNormalizer, boxes.coreAgent, { strokeColor: '#e8590c', strokeWidth: 2 }),
        connectBoxes('a3', boxes.coreAgent, boxes.extLlm, { strokeColor: '#9c36b5', strokeWidth: 2 }),
        connectBoxes('a4', boxes.coreAgent, boxes.coreTools, { strokeColor: '#2f9e44', strokeWidth: 2 }),
        connectBoxes('a5', boxes.coreTools, boxes.coreHmac, { strokeColor: '#e8590c', strokeWidth: 2 }),
        connectBoxes('a6', boxes.coreHmac, boxes.extSidecar, { strokeColor: '#e8590c', strokeWidth: 2 }),
        connectBoxes('a7', boxes.extSidecar, boxes.extInfra, { strokeColor: '#2f9e44', strokeWidth: 2 }),
        connectBoxes('a8', boxes.extSidecar, boxes.extGithub, { strokeColor: '#1971c2', strokeWidth: 2 }),
        connectBoxes('a9', boxes.inMcp, boxes.coreTools, { strokeColor: '#9c36b5', strokeWidth: 2, strokeStyle: 'dashed' })
    ];

    add(elements);
    exportDiagram('system-overview');
}

// ==========================================
// 2. INCIDENT REACT REMEDIATION LOOP
// ==========================================
function buildIncidentReactLoop() {
    clear();

    const boxes = {
        s1: {
            id: 's1',
            type: 'rectangle',
            x: 50,
            y: 200,
            width: 170,
            height: 95,
            backgroundColor: '#ffc9c9',
            strokeColor: '#e03131',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: '1. Inbound Alert\nCloudWatch, Azure,\nAlertmanager webhook\nPOST /webhooks/alerts'
        },
        s2: {
            id: 's2',
            type: 'rectangle',
            x: 270,
            y: 200,
            width: 190,
            height: 95,
            backgroundColor: '#ffd8a8',
            strokeColor: '#e8590c',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: '2. Triage & Normalize\nMap to standard severity:\nINFO < WARN < ERR < CRIT\nFilter low severity (202)'
        },
        s3: {
            id: 's3',
            type: 'rectangle',
            x: 510,
            y: 200,
            width: 190,
            height: 95,
            backgroundColor: '#ffe066',
            strokeColor: '#f59f00',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: '3. Redaction & Context\nSecretScrubber (Tokens)\nPIIMasker (IPs/Emails)\nContextBudgetManager'
        },
        s4: {
            id: 's4',
            type: 'rectangle',
            x: 750,
            y: 200,
            width: 190,
            height: 95,
            backgroundColor: '#eebefa',
            strokeColor: '#9c36b5',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: '4. ReAct Reasoning\nLLM Thought + Tool Call\nFetch logs, describe pods,\nanalyze root cause'
        },
        s5: {
            id: 's5',
            type: 'rectangle',
            x: 990,
            y: 200,
            width: 200,
            height: 95,
            backgroundColor: '#b2f2bb',
            strokeColor: '#2f9e44',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: '5. Policy & Gate\nInspect reads: allowed\nMutating writes: gate\nallow | deny | approval\nApprovalGate Registry'
        },
        s6: {
            id: 's6',
            type: 'rectangle',
            x: 1240,
            y: 200,
            width: 200,
            height: 95,
            backgroundColor: '#a5d8ff',
            strokeColor: '#1971c2',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: '6. Remediate & PR\nExecute via HMAC sidecar\nCreate git branch & PR\nUpdate durable state'
        },

        // Feedback / Loop node
        feedback: {
            id: 'loop-feedback',
            type: 'rectangle',
            x: 750,
            y: 360,
            width: 440,
            height: 60,
            backgroundColor: '#f1f3f5',
            strokeColor: '#868e96',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Observation Feedback Loop (Bounded to 5 turns)\nAgent evaluates tool execution output, refines hypothesis, or finishes'
        }
    };

    const elements = [
        // Title
        {
            id: 'title',
            type: 'text',
            x: 480,
            y: 50,
            text: 'Autonomous Incident Remediation — ReAct Decision Cycle',
            fontSize: 22,
            fontFamily: 'cascadia',
            strokeColor: '#1e1e1e'
        },
        {
            id: 'subtitle',
            type: 'text',
            x: 470,
            y: 85,
            text: 'Zero Human Intervention on Safe Fixes | Bounded Turns | Operator Approval Gates',
            fontSize: 14,
            fontFamily: 'cascadia',
            strokeColor: '#495057'
        },

        boxes.s1,
        boxes.s2,
        boxes.s3,
        boxes.s4,
        boxes.s5,
        boxes.s6,
        boxes.feedback,

        // Step-to-step arrows
        connectBoxes('a1', boxes.s1, boxes.s2, { strokeColor: '#e03131', strokeWidth: 2 }),
        connectBoxes('a2', boxes.s2, boxes.s3, { strokeColor: '#e8590c', strokeWidth: 2 }),
        connectBoxes('a3', boxes.s3, boxes.s4, { strokeColor: '#f59f00', strokeWidth: 2 }),
        connectBoxes('a4', boxes.s4, boxes.s5, { strokeColor: '#9c36b5', strokeWidth: 2 }),
        connectBoxes('a5', boxes.s5, boxes.s6, { strokeColor: '#2f9e44', strokeWidth: 2 }),

        // Loop arrow from s5 to feedback
        connectBoxes('loop1', boxes.s5, boxes.feedback, { strokeColor: '#868e96', strokeWidth: 2, strokeStyle: 'dashed' }),
        // Loop arrow from feedback back to s4
        connectBoxes('loop2', boxes.feedback, boxes.s4, { strokeColor: '#868e96', strokeWidth: 2, strokeStyle: 'dashed' })
    ];

    add(elements);
    exportDiagram('incident-react-loop');
}

// ==========================================
// 3. SIDECAR SECURITY MODEL
// ==========================================
function buildSidecarSecurityModel() {
    clear();

    const boxes = {
        agentBox: {
            id: 'agent-box',
            type: 'rectangle',
            x: 60,
            y: 190,
            width: 300,
            height: 290,
            backgroundColor: '#ffc9c9',
            strokeColor: '#e03131',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Ajentic-SRE Agent Container\n(Zero-Trust Application Tier)\n\n❌ ZERO Cloud Credentials (AWS/Azure)\n❌ ZERO Kubeconfig / Cluster Keys\n❌ ZERO GitHub Write Tokens\n\n🔑 Holds ONLY: Shared Secret Key\n(ajentic.sidecar.hmac-secret)\n\nComputes SHA-256 HMAC over\nPayload + Timestamp + Nonce'
        },

        wireBox: {
            id: 'wire-box',
            type: 'rectangle',
            x: 430,
            y: 190,
            width: 320,
            height: 290,
            backgroundColor: '#ffd8a8',
            strokeColor: '#e8590c',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Cryptographic Wire Boundary\n(Anti-Replay Verification)\n\nHeaders on HTTP Request:\n• X-Ajentic-Signature: <hex-mac>\n• X-Ajentic-Timestamp: <unix-ms>\n• X-Ajentic-Nonce: <uuid>\n\n🛡️ Replay Protection Rules:\n• Timestamp drift ≤ 30 seconds\n• Nonce cache rejects duplicates\n• Constant-time HMAC comparison'
        },

        sidecarBox: {
            id: 'sidecar-box',
            type: 'rectangle',
            x: 820,
            y: 190,
            width: 320,
            height: 290,
            backgroundColor: '#b2f2bb',
            strokeColor: '#2f9e44',
            fillStyle: 'solid',
            roundness: { type: 3 },
            text: 'Credentialed Sidecar Daemon\n(Isolated Execution Proxy)\n\n✅ Validates HMAC + Freshness\n✅ Injects Target Credentials:\n   - AWS_SECRET_ACCESS_KEY\n   - KUBECONFIG Cluster Context\n   - GITHUB_TOKEN for PR creation\n\n📝 Emits Tamper-Evident Audit Log:\n{timestamp, caller, action, result, traceId}'
        }
    };

    const elements = [
        // Title
        {
            id: 'title',
            type: 'text',
            x: 350,
            y: 50,
            text: 'Credential-Isolated Sidecar Architecture',
            fontSize: 22,
            fontFamily: 'cascadia',
            strokeColor: '#1e1e1e'
        },
        {
            id: 'subtitle',
            type: 'text',
            x: 340,
            y: 85,
            text: 'Zero-Credential Agent Isolation | HMAC-SHA256 Wire Protocol | Strict Anti-Replay Guardrails',
            fontSize: 14,
            fontFamily: 'cascadia',
            strokeColor: '#495057'
        },

        // Panels
        boxes.agentBox,
        boxes.wireBox,
        boxes.sidecarBox,

        // Connectors
        connectBoxes('wire1', boxes.agentBox, boxes.wireBox, { strokeColor: '#e03131', strokeWidth: 3 }),
        connectBoxes('wire2', boxes.wireBox, boxes.sidecarBox, { strokeColor: '#2f9e44', strokeWidth: 3 })
    ];

    add(elements);
    exportDiagram('sidecar-security-model');
}

// Run all
console.log('Generating diagrams...');
buildSystemOverview();
buildIncidentReactLoop();
buildSidecarSecurityModel();
console.log('All diagrams generated and exported successfully!');
