# Blueprint: app de ditado privado para Android (S25 Ultra primeiro)

Nome provisório: **bestmodel dictate**. É um teclado de voz + app de notas que transcreve PT/EN/ES
enquanto você fala, corrige sozinho a cada pausa e roda 100% no aparelho. Ele também é o **runner de
benchmark mobile** do bestmodel.run, e é isso que transforma a resposta `external` em `measured`.

## 1. Objetivos e não-objetivos

| Objetivo | Critério de aceite |
|---|---|
| Texto aparece enquanto fala | primeiro parcial ≤ 300 ms após começar a falar |
| Correção automática | frase final substitui a parcial ≤ 400 ms após a pausa |
| PT/EN/ES sem trocar configuração | LangID automático + travas manuais PT/EN/ES |
| Funciona em qualquer app | é um **IME** (teclado), não só um app de notas |
| Privado de verdade | flavor `airgap` **sem permissão INTERNET** no manifest; áudio nunca vai para disco |
| Aguenta uso real | 10 min contínuos sem throttling térmico que estoure o RTF; < 4% de bateria por 10 min |
| Sempre o melhor modelo | troca de modelo = trocar arquivo + manifest; recomendação vinda do bestmodel.run |

Fora de escopo no v1: diarização (quem falou), tradução, comandos de edição por voz complexos, iOS.

## 2. Arquitetura

```
┌──────────────────────────── processo do app (Kotlin) ────────────────────────────┐
│                                                                                   │
│  AudioRecord 16 kHz mono (VOICE_RECOGNITION)                                      │
│        │ ring buffer 20 ms                                                        │
│        ▼                                                                          │
│  Silero VAD ──── fala? ───► StreamingEngine (1º passo) ──► partial(text, segId)   │
│        │                     Nemotron 3.5, chunk 160 ms                │          │
│        │ endpoint (silêncio ≥ 600 ms ou 25 s de fala)                  ▼          │
│        └─────────────────► RefineEngine (2º passo) ─────────► final(text, segId)  │
│                              mesmo áudio do segmento,             │               │
│                              Parakeet v3 OU Nemotron chunk 1120   │               │
│                                                                   ▼               │
│                         Reconciler: committed[] + volatile ──► UI / IME           │
│                                                                   │               │
│                         (opcional) PolishEngine: LLM local ◄──────┘               │
│                                                                                   │
│  ModelManager (manifest + sha256)   BenchRunner (receita → relatório assinado)    │
└───────────────────────────────────────────────────────────────────────────────────┘
          JNI: sherpa-onnx (ONNX Runtime CPU int8 │ QNN EP p/ NPU Hexagon)
```

### Por que essa divisão

- **1º passo rápido + 2º passo com contexto completo** é o que dá a sensação de "ele vai corrigindo
  enquanto eu falo". O streaming tem pouco contexto à direita e erra mais; quando você pausa, a frase
  inteira é re-decodificada e o texto "assenta".
- **O IME casa perfeitamente com isso:** `InputConnection.setComposingText()` é exatamente o texto
  "volátil que ainda pode mudar" (sublinhado no campo), e `commitText()` é o final. Você não precisa
  inventar UI de correção, porque o Android já tem.

## 3. Componentes

### 3.1 Runtime de inferência

1. **sherpa-onnx** (Apache-2.0) como base: tem AAR Android, API Kotlin de `OnlineRecognizer` para
   transducers em streaming, VAD Silero, endpointing e hotwords. Há relato de suporte ao Nemotron 3.5
   (re-export ONNX + QNN). **Verifique na primeira semana** (M0).
2. Plano B, se o export do sherpa não estiver pronto: o ONNX int4 da `onnx-community` com
   onnxruntime-genai, ou NeMo-Speech.cpp via JNI.
3. Backend: comece em **CPU int8, 4 threads presas nos núcleos de performance**. Um modelo de 0.6B
   com chunk de 160 ms deve ter folga num Oryon. Teste o **QNN EP (NPU Hexagon)** como otimização de
   bateria no M3, e não como requisito, porque compatibilidade de operadores em NPU é onde os
   projetos travam.

Esqueleto (API Kotlin do sherpa-onnx):

```kotlin
class StreamingEngine(cfg: OnlineRecognizerConfig) {
    private val recognizer = OnlineRecognizer(config = cfg)
    private var stream = recognizer.createStream()

    fun feed(pcm: FloatArray): Partial? {
        stream.acceptWaveform(pcm, sampleRate = 16_000)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
        return if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream); Partial(text, endpoint = true)
        } else Partial(text, endpoint = false)
    }
}
```

### 3.2 Reconciler (o coração da "autocorreção")

```kotlin
data class Segment(val id: Long, var text: String, var state: State) // VOLATILE -> STREAM_FINAL -> REFINED
```

- O parcial de cada segmento **substitui** o anterior do mesmo `id` (nunca concatena).
- No endpoint, o segmento vira `STREAM_FINAL` e é enviado ao RefineEngine com o áudio guardado
  **em memória** (máx. 30 s por segmento; o buffer é zerado depois).
- Quando o refino volta, ele substitui o texto daquele segmento **se e somente se** o usuário não
  editou o segmento no meio do caminho (compare hash). No IME, enquanto o refino não volta, mantenha
  o segmento como composing text, e o `commitText()` só acontece depois do refino.
- Para evitar o texto "pulando" na tela, faça diff por palavra: só as palavras que mudaram piscam
  (e isso também alimenta a métrica `partial_stability`).

### 3.3 Idiomas

- **Auto** (padrão): LangID do próprio Nemotron por segmento.
- **Travas** PT / EN / ES num toque, para quando o auto errar em frases curtas.
- **Troca no meio da frase** é o caso difícil. Meça com as suas gravações antes de prometer.
  Mitigação: segmentos curtos (endpoint em 400 ms) deixam o LangID decidir por trecho.
- **Vocabulário pessoal** (hotwords do sherpa-onnx): nomes, "llama.cpp", "bestmodel", "GGUF", etc.,
  editável no app.

### 3.4 PolishEngine (opcional e desligado por padrão)

Um LLM local pequeno (Gemma 3n E2B ou Qwen3 1.7B via llama.cpp/LiteRT) só para: pontuação de
frases longas, comandos simples ("nova linha", "apaga a última frase") e remoção de "é… tipo…".
Regras: mostra o diff, um toque desfaz, **nunca reescreve conteúdo**. LLM em cima de transcrição
pode alucinar, e o produto inteiro depende de confiança.

### 3.5 ModelManager — "sempre o melhor modelo disponível"

Um `models.json` assinado define o que o app sabe rodar:

```json
{"version": 7, "signedBy": "ed25519:…", "models": [
  {"id": "nemotron-3.5-asr-streaming-0.6b-int8", "role": "stream", "runtime": "sherpa-onnx",
   "files": [{"name": "encoder.int8.onnx", "sha256": "…", "bytes": 0}],
   "chunkMs": [160, 560, 1120], "langs": ["pt", "en", "es"], "minRamGb": 6,
   "bestmodelCard": "https://www.bestmodel.run/a/best-streaming-stt-galaxy-s25-ultra-pt-en-es"},
  {"id": "parakeet-tdt-0.6b-v3-int8", "role": "refine", "runtime": "sherpa-onnx"}
]}
```

- Device → key: `Build.SOC_MODEL` + `Build.MODEL` (API 31+) → `resolve_device` do bestmodel.
- **Flavor `standard`**: um WorkManager diário (só em Wi-Fi e carregando) consulta o card do
  bestmodel.run. Se houver um pick novo com basis ≥ `reported` **neste device**, notifica. O usuário
  baixa, o app roda um mini-bench local (2 min) e só troca se ganhar em WER e latência. Nunca troca
  sozinho.
- **Flavor `airgap`**: nenhuma permissão de rede. O modelo entra pelo seletor de arquivos (SAF),
  depois de baixado no navegador, e o sha256 é conferido contra o manifest embutido no APK.

### 3.6 BenchRunner — o celular vira um "rig" do bestmodel

- Executa a receita `asr-stream-dictation-v1` (`scout/recipes/…json`): clipes FLEURS pt_br/en_us/es_419
  baixados uma vez, mais suas gravações com consentimento, alimentados **em ritmo de tempo real** pelo
  mesmo pipeline do ditado (não um caminho de bench separado, senão o número não vale).
- Mede WER/CER por idioma, `first_partial_ms`, `final_latency_ms`, `partial_stability`, RTF,
  RSS de pico, mWh/min (`BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER`) e
  `PowerManager.getThermalHeadroom()` ao longo de 10 min.
- Gera o relatório no **mesmo contrato do `benchmark-probe`** (assinado Ed25519, chave por usuário
  do S23/S42), com device = `MOBILE_SOC`, e o `contribute` vai para o bestmodel.run.
- Modo "deixa rodando à noite" (L07 D3): varre todos os modelos do manifest × chunks × backends,
  só na tomada.

## 4. Privacidade (o argumento de venda — tem que ser verificável)

- Flavor `airgap`: `<uses-permission android:name="android.permission.INTERNET"/>` **ausente**, com
  um teste de CI que falha se ela aparecer (`aapt dump permissions`).
- Áudio só em RAM, buffer zerado depois do refino. Nada de log de texto. Sem analytics e sem crash
  reporter de terceiros.
- Foreground service tipo `microphone` com notificação persistente enquanto ouve (exigência do
  Android 14+ e também sinal honesto para o usuário).
- Builds reproduzíveis e código aberto (o resto do ecossistema já é MIT/AGPL). Publicar no F-Droid.

## 5. Estrutura do projeto

```
dictate-android/
  app/                    # Compose UI: notas, configurações, modelos, bench
  ime/                    # InputMethodService + teclado mínimo + botão de mic
  engine/                 # StreamingEngine, RefineEngine, Reconciler, VAD (Kotlin)
  engine-native/          # sherpa-onnx AAR / JNI, configs QNN
  models/                 # ModelManager, manifest, verificação sha256
  bench/                  # BenchRunner, receitas, relatório assinado, contribute
  polish/                 # (opcional) LLM local
  flavors: standard | airgap
```

## 6. Marcos

| Marco | Entrega | Pronto quando |
|---|---|---|
| **M0** (2–3 dias) | Spike: Nemotron 3.5 int8 rodando via sherpa-onnx no S25, só log de texto | RTF < 0,3 com chunk 160 ms em CPU; se falhar, plano B do §3.1 |
| **M1** (1 sem) | App de notas: mic → parcial → endpoint → final (1 passo só) | parcial ≤ 300 ms; 10 min sem crash |
| **M2** (1 sem) | 2º passo (Parakeet v3) + Reconciler + travas de idioma + hotwords | WER final < WER de streaming nas suas 40 gravações |
| **M3** (1 sem) | IME (composing/commit) + flavor airgap + QNN experimental | Ditar no WhatsApp/Gmail/Obsidian; teste de permissão no CI |
| **M4** (1 sem) | BenchRunner + contribute → primeira célula **measured** de MOBILE_SOC no bestmodel | Card do S25 sobe de `external` para `reported` |
| **M5** | ModelManager com recomendação diária do bestmodel + mini-bench de troca | Um modelo novo do scout chega ao celular medido, sem você intervir |

## 7. Riscos e mitigação

| Risco | Mitigação |
|---|---|
| Export do Nemotron 3.5 para sherpa/QNN imaturo | Plano B (onnxruntime-genai / NeMo-Speech.cpp); Parakeet v3 em chunks como fallback de 1º passo |
| Code-switching PT↔EN fraco | Segmentos curtos + travas; medir antes de prometer; candidato Voxtral no bench |
| Throttling em uso longo | Adaptar chunk (160→560 ms) e mover para NPU quando o `thermalHeadroom` cair |
| Bateria | VAD gate: o modelo só roda com fala detectada |
| LLM de polish alucinando | Desligado por padrão, diff visível, nunca reescreve conteúdo |
| Licenças | Nemotron = OpenMDW-1.1, Parakeet = CC-BY-4.0 (atribuição na tela "Sobre"), Voxtral = Apache-2.0 |
