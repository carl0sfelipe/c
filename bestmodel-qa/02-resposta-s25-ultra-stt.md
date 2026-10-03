# Resposta: ditado ao vivo, 100% local, no Galaxy S25 Ultra (PT/EN/ES)

> Este é o "answer card" que o bestmodel.run deveria ter devolvido. Segue a honesty ladder:
> **nenhum número abaixo foi medido num S25 Ultra.** Os números são do vendor (model card) ou de
> terceiros, e estão rotulados assim. Basis para S25 Ultra: **no data yet → external evidence**.

Hardware: Galaxy S25 Ultra · Snapdragon 8 Elite for Galaxy (CPU Oryon 2+6, GPU Adreno 830, NPU Hexagon) ·
12 GB LPDDR5X (o Android costuma deixar ~5–7 GB livres para um app em primeiro plano).

## Recomendação (outubro de 2026)

### 1º — NVIDIA Nemotron 3.5 ASR Streaming 0.6B  ← escolha principal

`nvidia/nemotron-3.5-asr-streaming-0.6b` · lançado em jun/2026 · 600M parâmetros · licença OpenMDW-1.1

Por que ele é o melhor para o seu caso:

- **Streaming de verdade** (FastConformer *cache-aware* + RNNT): processa só o áudio novo e reaproveita
  o contexto em cache. Não é Whisper picotado em janelas.
- **Latência configurável em tempo de execução**: chunks de 80 / 160 / 320 / 560 / 1120 ms. Dá para usar
  160 ms para o texto aparecer enquanto você fala e 1120 ms quando a qualidade importa mais.
- **PT, EN e ES no mesmo checkpoint**, com locales pt-BR, pt-PT, es-US, es-ES, en-US e en-GB no tier
  "transcription-ready". Tem **detecção automática de idioma** (prompt de LangID).
- **Pontuação e maiúsculas nativas**, então não precisa de um segundo modelo só para isso.
- WER do model card (chunk 1.12 s, modo LangID; *vendor-reported*): **ES 4,11% · PT 5,48% · EN 7,91%**.
- Tamanho: ~640M parâmetros → ~0,65 GB em int8, ~0,35 GB em int4. Sobra muita RAM no S25.
- Caminhos para Android que já existem: **sherpa-onnx** (re-export ONNX + QNN, relatado), export
  ONNX int4 da `onnx-community` (onnxruntime-genai), GGUF no repo oficial e NeMo-Speech.cpp (C++).
- 1,25 M downloads no HF: tem comunidade, então bugs aparecem e são corrigidos rápido.

Ressalva: o RNNT em streaming **não reescreve** o que já emitiu. A "autocorreção enquanto fala" que você
quer vem da arquitetura de dois passes descrita abaixo, e não do modelo sozinho. A troca de idioma
**no meio da frase** (code-switching) é o ponto fraco provável: o LangID é por segmento. Isso precisa
ser medido com suas próprias gravações (já está na receita do benchmark).

### 2º — NVIDIA Parakeet TDT 0.6B v3 (como segundo passo / refino)

`nvidia/parakeet-tdt-0.6b-v3` · 600M · CC-BY-4.0 · 25 línguas europeias (inclui PT, ES, EN) ·
WER médio de 6,34% no Open ASR Leaderboard (*reported*). Não é streaming nativo, mas é muito rápido
(terceiros relatam RTF ~0,12 no Android com int8). É ideal para **re-decodificar cada frase quando você
faz uma pausa** e trocar o texto parcial pelo final. Já roda em sherpa-onnx no Android.

### 3º — Mistral Voxtral Mini 4B Realtime (para testar; o teto de qualidade)

`mistralai/Voxtral-Mini-4B-Realtime-2602` · Apache-2.0 · streaming nativo treinado ponta a ponta,
atraso configurável até sub-200 ms, e com 480 ms empata com o Whisper offline (*vendor-reported*).
13 línguas, incluindo PT/ES/EN. GGUF Q4_K_M = **2,83 GB**, então cabe na RAM. **Risco**: não se sabe
se 4B sustenta tempo real no Snapdragon 8 Elite sem esquentar e drenar bateria, e os runtimes
(transcribe.cpp, Vokra) são jovens. Vale entrar no benchmark, não como padrão.

### Baseline obrigatório (para comparar, não para usar)

- **Reconhecedor on-device do próprio Android** (`SpeechRecognizer.createOnDeviceSpeechRecognizer`,
  API 31+, com pacotes offline pt-BR/es/en). É grátis e já está no aparelho, mas é fechado e a
  privacidade depende de confiar no Google/Samsung. Se o Nemotron não ganhar dele em WER, a recomendação
  muda.
- **Whisper Large v3 Turbo / whisper.cpp**: referência histórica. Não é streaming, alucina em silêncio
  e é mais pesado. Fica como controle.

### Descartados (e por quê)

| Modelo | Motivo |
|---|---|
| Moonshine v2 | Ótimo streaming, mas os modelos são por idioma (tem ES, **sem PT** publicado) |
| Kyutai STT | Só EN/FR |
| Qwen3-ASR 0.6B / 1.7B | Bom em qualidade e tem modo streaming, mas o decoder é LLM: mais lento no celular e propenso a alucinar em silêncio. Entra só como candidato de benchmark |
| Canary 1B v2, Granite Speech, Cohere Transcribe | Topo do Open ASR Leaderboard, mas offline e/ou >1B com decoder LLM: refino em servidor, não ditado em celular |
| Vosk / Picovoice Cheetah | Rodam em qualquer coisa, mas a qualidade fica bem abaixo da geração 2026 (Cheetah ainda é proprietário) |

## Como fica a "autocorreção enquanto fala" (o comportamento que você descreveu)

```
mic 16 kHz ─► VAD (Silero) ─► Nemotron 3.5 streaming, chunk 160 ms ─► texto PARCIAL (cinza, muda a cada 160 ms)
                                    │
                         pausa ≥ 600 ms (endpoint)
                                    ▼
                 re-decode da frase inteira (Parakeet v3 ou Nemotron chunk 1120 ms)
                                    ▼
                    texto FINAL (preto) substitui o parcial daquela frase
                                    ▼
          (opcional) LLM local pequeno só para pontuação/limpeza, com diff visível e desfazer
```

É o mesmo padrão "two-pass" que os teclados de voz usam: o primeiro passo é rápido e instável, e o
segundo passo, com contexto completo, corrige. O detalhe completo está em `04-blueprint-app-android.md`.

## O que falta medir (vira o benchmark do site)

Receita `scout/recipes/asr-stream-dictation-pt-en-es.json`: FLEURS pt_br / en_us / es_419 + gravações
suas com troca de idioma e ruído, medindo WER por idioma, latência do primeiro parcial, latência até o
final, estabilidade dos parciais, RTF, mWh/min e tempo até o throttling térmico. Enquanto isso não for
medido no S25, a resposta acima fica como **external evidence** e não deve virar número no /wall.

## Fontes

- Model card Nemotron 3.5 ASR Streaming: https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
- Export ONNX int4: https://huggingface.co/onnx-community/nemotron-3.5-asr-streaming-0.6b-onnx-int4
- Lançamento (40 locales, 80 ms–1,12 s): https://www.marktechpost.com/2026/06/06/nvidia-releases-nemotron-3-5-asr-a-600m-parameter-cache-aware-streaming-model-transcribing-40-language-locales-in-real-time/
- Parakeet TDT v3 no Android: https://soniqo.audio/guides/parakeet/android · https://spokenly.app/blog/parakeet-models
- Voxtral Realtime (paper): https://arxiv.org/abs/2602.11298 · GGUF: https://huggingface.co/handy-computer/Voxtral-Mini-4B-Realtime-2602-gguf
- Qwen3-ASR: https://huggingface.co/Qwen/Qwen3-ASR-0.6B · https://arxiv.org/html/2601.21337
- Open ASR Leaderboard: https://github.com/huggingface/open_asr_leaderboard · https://arxiv.org/html/2510.06961v3
- Moonshine: https://arxiv.org/html/2509.02523v1
