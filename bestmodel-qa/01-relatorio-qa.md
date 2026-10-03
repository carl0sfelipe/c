# QA do bestmodel.run — "qual o melhor modelo de ditado ao vivo para o meu S25 Ultra (PT/EN/ES)?"

Data: 2026-10-03 · Testado: `www.bestmodel.run` (web-next), `api.bestmodel.run` (git `c12709e6458e`, build 2026-09-30), repo `carl0sfelipe/bestmodel` (HEAD de `main`).

Persona: um agente (eu) que nunca viu o site e chega com a pergunta:

> "Qual é o melhor modelo open source que roda 100% local num Galaxy S25 Ultra
> (Snapdragon 8 Elite, 12 GB), transcrevendo PT/EN/ES em streaming enquanto falo?"

## Veredito

**O site não responde a pergunta.** Depois de 20+ requisições (llms.txt, home agent, /wall, /hardware,
/m/{slug}, REST API, OpenAPI), a melhor coisa que o site oferece é uma célula de Whisper Large v3 numa
**L4 de datacenter**, e o próprio `llms.txt` diz para *não* usá-la para português. A resposta correta
(ver `02-resposta-s25-ultra-stt.md`) saiu de ~6 buscas externas em ~5 minutos. Ou seja: hoje o
caminho mais rápido para essa resposta **não passa** pelo bestmodel.run.

O problema não está numa página isolada. O produto foi modelado em torno de uma pergunta só:
*"LLM de chat × GPU desktop → tok/s"*. Tudo que foge disso (outra tarefa, outro tipo de dispositivo,
outra métrica, qualidade por idioma) cai num buraco sem resposta e sem um caminho que leve à resposta.

## Percurso do agente (o que aconteceu de fato)

| # | Ação | Resultado | Custo |
|---|---|---|---|
| 1 | `GET bestmodel.run/llms.txt` | **308 → www.bestmodel.run**. Sem `-L`, o corpo é só `Redirecting...` | 1 req perdida |
| 2 | `GET www…/llms.txt` | Contrato bom, mas fala só de tok/s/GPU. Seção de áudio = só "3090 + Whisper PT" | ok |
| 3 | `GET /?as=agent` | Honesty ladder + contagens. Nenhum ponto de entrada por **tarefa** ou **dispositivo** | ok |
| 4 | `GET /search?q=…` | **404** — não existe busca | sem saída |
| 5 | `GET /hardware?as=agent` | 185 rigs, **nenhum celular** com GPU/NPU. Único ARM: `cpu-qualcomm-snapdragon-888-arm64` (1 run, CPU_ONLY) | sem saída |
| 6 | `GET /wall?category=audio&as=agent` | **1 linha**: Whisper v3 na L4, 4.8×real, sem idioma | resposta errada |
| 7 | `GET /m/whisper-large-v3-turbo?as=agent` | 39.2×real numa RTX 3090. Célula existe, mas **não apareceu no passo 6** (bug B2) | inconsistente |
| 8 | `GET /m/nvidia-parakeet-tdt-0-6b-v3`, `/m/moonshine-base` | "not in the catalog"; sugestões **lexicais** (`moonshotai-kimi`, `nvidia-qwen3…`) | ruído |
| 9 | `GET /wall?as=agent` (default) | Topo: `stories-llama2-50k` 36.715 tok/s e `ggml-org-models-moved` — **lixo no #1 e #2** apesar do texto "excludes toy/tinystories" (bug B1) | perda de confiança |
| 10 | `api.bestmodel.run/openapi.json` | 49 endpoints, nenhum de busca por tarefa; catálogo da API = **78 modelos, 0 de áudio** | sem saída |
| 11 | `POST /v1/match/hardware-to-models` | Exige `gpu_model_ids`, `ram_gib`, `os_name`, `target_model_family`, `target_context_tokens`. Com `whisper` → `{"matches":[]}`, sem explicar por quê | sem saída |
| 12 | `GET /v1/leaderboard` | **1 run** (Qwen3-8B, 3090). O web tem 8.230 runs — dois "mundos" de dados separados | confuso |
| 13 | `/mcp`, `/.well-known/mcp.json` | 404 (MCP ainda não existe) | — |

## Achados, por severidade

### P0 — impedem a resposta

**A1. Não existe eixo "tarefa".** `category` tem 5 valores (`chat 652, code 34, image 3, audio 2, video 1`)
e o motor só ranqueia tok/s. "Ditado em streaming" é uma tarefa com métricas próprias (WER por idioma,
latência até o texto final, estabilidade dos parciais, RTF, bateria) que o schema não representa.
`specs/en/L07-native-audio-bench.md` já reconhece isso ("Contract 0.9.0 stores `audioXReal`. It has no
`wer`, no `language`"), mas está como épico não aberto.

**A2. Não existe classe de hardware "celular/SoC".** `hwClass` ∈ {DISCRETE_GPU, UNIFIED, CPU_ONLY}.
Um S25 Ultra não é nenhum desses de forma útil: o que importa é o SoC (Snapdragon 8 Elite), o backend
(CPU / GPU Adreno / NPU Hexagon via QNN), a RAM disponível para apps (~5–7 GB de 12 GB) e o
throttling térmico. O `benchmark-probe` também não roda em Android.

**A3. Quando não há dado, o site não dá caminho nenhum.** A honesty ladder termina em "no data yet",
o que está certo para *números*, mas o agente fica sem saída. Falta um degrau de **evidência externa**
("o estado da arte publicado é X, segundo o model card/leaderboard Y, ainda não medido aqui") e um
**pedido de benchmark** que se resolve sozinho. Hoje o agente vai embora e acha a resposta em outro lugar.

**A4. Sem busca e sem roteamento por intenção.** Não há `/search`, nem `/ask`, nem endpoint que aceite
linguagem natural ou pelo menos `task + device + langs`. O "not found" de `/m/{slug}` sugere vizinhos
por **edit distance** (`moonshine` → `moonshotai-kimi-k2-5`), não por tarefa.

### P1 — respostas erradas/inconsistentes

**B1. Lixo no topo do /wall padrão.** `lib/wall-filter.ts:37` só exclui modelos com `paramsB < 1`
**quando `paramsB` é conhecido**. `delphi-suite-stories-llama2-50k` e `ggml-org-models-moved` têm
`paramsB = null` e o regex de toy procura `tinystories`/`toy`, não `stories`. Resultado: 36.715 tok/s
no #1. Correção: `paramsB == null` → fora do ranking padrão (ou resolver o tamanho pelo HF), e
uma denylist curada.

**B2. A regra "<1B fora do ranking" vale para todas as categorias.** `filterWallRows` aplica
`isDefaultRankingExcluded` mesmo com `category=audio`. O Whisper v3 Turbo (0.81B) some de
`/wall?category=audio`, sobrando só a célula da L4, justamente a que o `llms.txt` proíbe usar.
Pior: **todo modelo de ASR on-device bom tem <1B** (Nemotron 3.5 0.6B, Parakeet 0.6B, Moonshine 58M),
assim como os LLMs para celular. Essa regra é de LLM de chat e tem que ser por tarefa.

**B3. Dois planos de dados que não conversam.** O web (snapshot congelado de 2026-09-18) tem
692 modelos e 8.230 runs. A API pública tem 78 modelos, 0 de áudio e 1 run no leaderboard. Para um
agente, `api.bestmodel.run` parece a fonte "oficial" e está quase vazia.

**B4. API de match hostil a agente.** São 6 campos obrigatórios, IDs opacos (`gpu-rtx-3090`, que não
batem com os keys do web, `rtx-3090-24gb`) e `{"matches":[]}` sem motivo. Deveria aceitar campos
parciais, resolver aliases ("S25 Ultra" → `snapdragon-8-elite-for-galaxy`) e devolver `why_empty`
mais `next_best`.

### P2 — atrito

- **C1.** Apex `bestmodel.run` → 308 → `www`. O sitemap e o próprio `llms.txt` listam URLs do apex,
  então todo fetch custa um redirect e quebra clientes sem follow-redirect.
- **C2.** Falhas TLS intermitentes (`SSL_ERROR_SYSCALL`) em ~1 de cada 4 requisições a partir deste
  ambiente. Pode ser o proxy daqui, mas vale olhar o edge.
- **C3.** O `llms.txt` do repo e o do site divergem (o do repo fala de `/v1` no mesmo host; no site,
  `/v1/*` em `www` dá 404).
- **C4.** As agent twins repetem ~1 KB de chrome (nav + rodapé) em toda página. Para um agente,
  JSON (`?as=json` ou `Accept: application/json`) seria mais barato e sem ambiguidade.
- **C5.** O blog tem só 3 posts, todos de LLM/GPU. Para o objetivo de SEO ("alguém que quer isso
  acha meu site"), cada pergunta respondida deveria virar uma página permanente (ver plano).

## O que já está muito bom (manter)

- A **honesty ladder** é o diferencial real. Nenhum concorrente separa measured/reported/extrapolated/formula.
- A resposta de áudio da 3090 (`docs/measurements/2026-09-23-audio-pt-br-3090.md`) é o modelo certo:
  pergunta concreta → célula medida + WER rotulado como *reported* → aviso explícito do que não usar.
  O problema é que ela foi feita **à mão**, uma vez. O plano abaixo transforma esse processo em produto.
- As agent twins (`?as=agent`) e o "not found" estruturado são boas ideias. Só precisam de JSON e de
  sugestões semânticas.

Próximo: `03-plano-site-mcp.md` (o que mudar) · `02-resposta-s25-ultra-stt.md` (a resposta que o site deveria ter dado).
