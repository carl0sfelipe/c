# Plano: fazer o bestmodel.run (site + API + MCP) responder qualquer pergunta em 1 chamada

Meta mensurável: **um agente sem contexto chega com uma pergunta arbitrária ("melhor X para fazer Y
no aparelho Z, nas línguas W") e recebe uma resposta útil e honesta em 1 chamada de MCP ou 1 GET.**
Hoje essa mesma pergunta exige ≥13 chamadas e termina sem resposta (ver `01-relatorio-qa.md`).

A ideia central é parar de modelar o produto como "LLM × GPU → tok/s" e passar a modelar
**pergunta → resposta**, com o pool medido como a melhor fonte e não como a única.

---

## 1. Modelo de dados: três eixos novos

### 1.1 Task (tarefa) — substitui `category`

Cada tarefa declara **suas** métricas e **sua** regra de ranking. Isso mata o bug B2 (regra de LLM
aplicada a ASR) na raiz.

```yaml
# data/tasks/asr.streaming.dictation.yaml
id: asr.streaming.dictation
aliases: [ditado, speech-to-text, stt, transcrição ao vivo, dictation, voice typing, dictado]
parent: asr
metrics:
  - {id: wer, per: language, better: lower, unit: "%"}
  - {id: final_latency_ms, better: lower}
  - {id: first_partial_ms, better: lower}
  - {id: partial_stability, better: higher}
  - {id: rtf, better: lower, gate: "< 0.5"}
  - {id: energy_mwh_per_min, better: lower}
ranking: pareto(wer.mean(langs), final_latency_ms) where rtf.gate
eligibility: {min_params_b: null}        # sem corte de 1B aqui
recipe: asr-stream-dictation-v1
```

Tarefas iniciais: `chat`, `code`, `asr.offline`, `asr.streaming.dictation`, `tts`, `ocr`,
`embedding`, `image.gen`, `video.gen`, `vision.qa`, `translation`. Uma pergunta que não casa com
nenhuma vira um **pedido de taxonomia** (ver §4), em vez de cair num 404.

### 1.2 Device class — de "rig com GPU" para "aparelho"

Adicione `MOBILE_SOC` (e `NPU_PC`, `SBC`) com os campos que importam no celular:

```json
{"key": "snapdragon-8-elite-galaxy-12gb", "hwClass": "MOBILE_SOC",
 "aliases": ["galaxy s25 ultra", "s25 ultra", "sm-s938b", "s25+", "s25"],
 "soc": "Snapdragon 8 Elite for Galaxy", "ramGb": 12, "appRamBudgetGb": 6,
 "backends": ["cpu", "gpu-adreno-830", "npu-hexagon-qnn"], "bandwidthGBs": 84.8,
 "thermal": "sustained"}
```

Com isso, um **resolver de aparelho** (`"S25 Ultra"` → key) passa a ser uma tabela de aliases.
Semeie com os ~40 SoCs/aparelhos mais vendidos (Snapdragon 8 Gen 2/3/Elite/Elite 2, Tensor G3–G5,
Dimensity 9300/9400, Apple A17 Pro/A18/A19, M-series) a partir de fontes públicas.

### 1.3 Language

`language` (BCP-47) vira dimensão de primeira classe na célula quando a tarefa é de fala/texto. Hoje
a célula de áudio não tem idioma, e foi isso que quase fez a L4 virar "resposta em português"
(L07 D2). Implemente o L07 D1/D2 como está escrito: **recipe é a unidade** e `wer + language` entram
no contrato.

## 2. Um degrau novo na honesty ladder: `external`

```
measured > reported > extrapolated > formula > external > no data yet
```

`external` = número publicado por terceiro (model card, paper, leaderboard), **com URL e data**,
nunca reproduzido aqui. Ele fica sempre abaixo de qualquer medição própria, aparece com outra cor e
**nunca entra no /wall**. É ele que evita a situação atual, em que "no data yet" significa
"vá procurar no Google". Assim o site continua honesto e passa a ser útil no dia 1 de qualquer
tarefa nova.

## 3. O "answer card": o formato único de resposta

Todo endpoint (web, REST, MCP) devolve o mesmo objeto:

```json
{
  "question": {"task": "asr.streaming.dictation", "device": "snapdragon-8-elite-galaxy-12gb",
               "languages": ["pt-BR", "en", "es"], "constraints": {"offline": true, "license": "open"}},
  "answer": {
    "pick": "nvidia/nemotron-3.5-asr-streaming-0.6b",
    "runtime": "sherpa-onnx int8 (cpu) | qnn (npu)",
    "why": ["native cache-aware streaming", "pt/en/es in one checkpoint + LangID", "0.6B fits app RAM budget"],
    "basis": "external",
    "evidence": [{"metric": "wer", "lang": "pt", "value": 5.48, "basis": "external",
                  "source": "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b", "as_of": "2026-06-04"}]
  },
  "alternatives": [{"pick": "nvidia/parakeet-tdt-0.6b-v3", "role": "second-pass refiner"},
                   {"pick": "mistralai/Voxtral-Mini-4B-Realtime-2602", "role": "quality ceiling, thermal risk"}],
  "rejected": [{"model": "moonshine-v2", "reason": "no Portuguese checkpoint"}],
  "gaps": ["no measured cell on any MOBILE_SOC", "code-switching WER unknown"],
  "next_action": {"type": "benchmark_request", "recipe": "asr-stream-dictation-v1", "id": "br_0193", "votes": 1},
  "card_url": "https://www.bestmodel.run/a/best-streaming-stt-galaxy-s25-ultra-pt-en-es",
  "as_of": "2026-10-03", "stale_after": "2026-10-10"
}
```

Regras:
- `basis` do card = o pior basis entre as evidências que sustentam o `pick`.
- `gaps` e `next_action` são **obrigatórios** quando o basis é pior que `reported`.
- Cada card tem URL permanente em `/a/{slug}` (HTML para humanos, `?as=agent` e `.json` para
  agentes), entra no sitemap e tem `stale_after`. **Isso também é o seu motor de SEO:** cada pergunta
  real vira uma página que responde exatamente aquela busca.

## 4. O motor de perguntas (`/ask`)

```
pergunta livre ─► parser (determinístico primeiro: aliases de task/device/lang; LLM local só como fallback)
              ─► card existente e fresco?  ── sim ─► devolve (cache)
              ─► não: monta o card a partir de
                    1. pool medido (tarefa + device + idioma)
                    2. extrapolação entre devices da mesma classe (por bandwidth/TOPS, rotulada)
                    3. índice `external` (scout diário, §6)
              ─► persiste o card, registra a pergunta em `demand_log`
              ─► se basis ≤ external: abre/vota um `benchmark_request`
```

O `demand_log` é o seu backlog de verdade: as perguntas mais repetidas sem medição são as próximas
receitas a rodar e os próximos "buracos" a gamificar (L07 D4 já propõe pontuar cobertura).

## 5. MCP: as ferramentas

Servidor MCP remoto (Streamable HTTP) em `https://mcp.bestmodel.run/mcp`, e o mesmo binário local
via `bestmodel mcp` (stdio) para quem quer rodar offline. As ferramentas são **finas**: todas chamam
o motor do §4, e nenhuma lógica fica duplicada.

| Tool | Entrada | Saída | Para quê |
|---|---|---|---|
| `ask` | `question: string` | answer card | **A única que um agente precisa.** Linguagem natural, em qualquer idioma |
| `find_best_model` | `task, device?, languages?, constraints?` | answer card | Versão estruturada, sem ambiguidade |
| `resolve_device` | `text` (ex.: "S25 Ultra", saída de `adb shell getprop`) | device key + specs | Tirar o atrito dos IDs |
| `list_tasks` | — | tarefas + métricas | Descoberta |
| `get_model` | `model` (HF id ou slug) | células por task/device/idioma + evidência externa | Drill-down |
| `compare` | `models[], task, device?` | tabela lado a lado com basis por célula | "A ou B?" |
| `get_recipe` | `task` | receita JSON + comando para rodar | Para o usuário medir e contribuir |
| `request_benchmark` | `model, task, device` | id + votos | Fecha o loop de demanda |

Recursos MCP: `bestmodel://llms.txt`, `bestmodel://tasks`, `bestmodel://recipes/{id}`.

Descrições das tools **curtas e diretivas**: "Call `ask` first with the user's question verbatim.
Every number carries `basis`; never present `external` as measured." É isso que faz o modelo do
outro lado escolher a ferramenta certa sem ler documentação.

## 6. Scout diário (descoberta de modelos novos)

`scout/asr_scout.py` (neste diretório, testado contra o HF hoje) é o protótipo para ASR. Ele já
coloca o Nemotron 3.5 em 1º sem nenhuma regra manual. Para generalizar:

1. **Um scout por task**, com a mesma interface: `pipeline_tag` do HF + filtros da tarefa
   (idiomas, tamanho máximo pela RAM do menor device-alvo, formatos mobile: onnx/gguf/tflite/qnn/coreml).
2. Fontes: HF API (`sort=createdAt|trendingScore|downloads`), Open ASR Leaderboard, Papers with Code,
   releases de runtimes (sherpa-onnx, llama.cpp, whisper.cpp, ExecuTorch, LiteRT, QNN/AI Hub).
3. Saída: candidatos com `basis: "no data yet"` e um `external` quando o model card tem números
   (o HF expõe `model-index`/`eval-results`).
4. Ação: abre `benchmark_request` (issue + linha no DB). Se o card de uma pergunta popular tiver
   um candidato novo com evidência externa melhor, o card ganha o aviso "candidato novo, não medido"
   **sem trocar o pick** até haver medição.
5. Agendamento: GitHub Actions cron diário (`scout/github-workflow.example.yml`).

O scout **descobre**. Quem **mede** é o runner de benchmark (no seu caso, o app Android do
`04-blueprint-app-android.md` em modo bench, com o celular ligado na tomada durante a noite).

## 7. Correções pontuais (já localizadas)

| ID | Onde | Correção |
|---|---|---|
| B1 | `apps/web-next/lib/wall-filter.ts:37` | `paramsB == null` → fora do ranking padrão; regex inclui `stories`; denylist (`ggml-org/models-moved`) |
| B2 | `apps/web-next/lib/wall-filter.ts:42-50` | Elegibilidade por task (`task.eligibility`), não global; com `category` explícita, nunca aplicar o corte de 1B |
| B3 | API vs snapshot | Uma fonte só. A API serve o mesmo `derived/*.json` do web até o DB ter paridade; `/v1/leaderboard` não pode ter menos dados que o `/wall` |
| B4 | `POST /v1/match/*` | Todos os campos opcionais; aceitar aliases; responder `why_empty` + `next_best` + `card_url` |
| C1 | DNS/sitemap | Sitemap, `llms.txt` e canonical apontando para `www` (ou servir o apex direto, sem 308) |
| C3 | `llms.txt` | Gerar o do site a partir do repo no build (uma fonte só) |
| C4 | agent twins | `Accept: application/json` / `.json` em toda rota; twins sem nav/rodapé |
| A4 | `/m/{slug}` not-found | Sugestões por task + embedding do nome/descrição, não edit distance; incluir "modelos externos conhecidos para essa task" |

## 8. Como saber que funciona "para tudo": golden questions no CI

Crie `tests/golden_questions.yaml` com ~50 perguntas variadas e reais (inclusive esta), em PT/EN/ES:

```yaml
- q: "melhor modelo de ditado em streaming offline pro Galaxy S25 Ultra, português inglês espanhol"
  expect: {task: asr.streaming.dictation, device: snapdragon-8-elite-galaxy-12gb,
           pick_in: [nvidia/nemotron-3.5-asr-streaming-0.6b], basis_at_least: external, max_calls: 1}
- q: "best local OCR for receipts on a Raspberry Pi 5"
- q: "qual LLM roda a 20 tok/s num MacBook Air M3 16GB pra programar"
- q: "TTS en español que corra en un iPhone 15 sin internet"
```

O gate de CI roda `ask` em cada uma e falha se: a task ou o device foram mal resolvidos; o card veio
sem `basis` ou sem `gaps`; precisou de mais de 1 chamada; ou o pick está fora de `pick_in`. Toda
pergunta que um usuário real fizer e der errado vira uma linha nova nesse arquivo. É assim que
"funciona para certas coisas" vira "funciona para tudo", e de um jeito medido.

## 9. Ordem sugerida (do maior impacto para o menor)

1. **B1 + B2 + C1** (horas): param de mostrar lixo e de esconder o que existe.
2. **Answer card + `/a/{slug}` + degrau `external`** (dias): a primeira resposta útil para qualquer pergunta.
3. **Task taxonomy + MOBILE_SOC + resolver de device** (dias).
4. **MCP com `ask` e `find_best_model`** (1–2 dias, depois do item 2; é uma casca fina).
5. **Golden questions no CI** (1 dia, mas comece com 5 perguntas já no item 2).
6. **Scout diário por task** (o de ASR já existe aqui).
7. **L07 (recipe + WER + language) + runner mobile**: transforma `external` em `measured`.
8. **Social**: cada card ganha thread de discussão; o `demand_log` alimenta "perguntas sem resposta"
   na home, e quem mede um buraco ganha reputação (A3/L07 D4).
