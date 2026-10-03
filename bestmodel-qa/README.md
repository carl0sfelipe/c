# bestmodel.run — QA + plano + blueprint (2026-10-03)

Pergunta de teste: *"melhor modelo para ditado ao vivo, 100% local, no Galaxy S25 Ultra, PT/EN/ES"*.

| Arquivo | Conteúdo |
|---|---|
| [01-relatorio-qa.md](01-relatorio-qa.md) | QA do site/API como agente: 13 passos, nenhuma resposta; 4 bugs P0, 4 P1, 5 P2 com arquivo e linha |
| [02-resposta-s25-ultra-stt.md](02-resposta-s25-ultra-stt.md) | A resposta que o site deveria ter dado: **Nemotron 3.5 ASR Streaming 0.6B** + Parakeet v3 como refino |
| [03-plano-site-mcp.md](03-plano-site-mcp.md) | Como mudar site/API/MCP para responder qualquer pergunta em 1 chamada (task, MOBILE_SOC, `external`, answer card, `ask`, golden questions) |
| [04-blueprint-app-android.md](04-blueprint-app-android.md) | App de ditado: IME + streaming de 2 passos + airgap + bench runner que alimenta o bestmodel |
| [scout/asr_scout.py](scout/asr_scout.py) | Scout diário de modelos ASR no Hugging Face (stdlib, testado em 2026-10-03) |
| [scout/recipes/asr-stream-dictation-pt-en-es.json](scout/recipes/asr-stream-dictation-pt-en-es.json) | Receita do benchmark PT/EN/ES (métricas, datasets, regra de ranking) |
| [scout/github-workflow.example.yml](scout/github-workflow.example.yml) | Cron diário para o repo `bestmodel` (exemplo, inativo aqui) |

Rodar o scout:

```bash
python3 scout/asr_scout.py --days 30 --top 20 --out candidates.json --md candidates.md
```

Saída em 2026-10-03 (top 3): `nvidia/nemotron-3.5-asr-streaming-0.6b`, `nvidia/parakeet-tdt-0.6b-v3`,
`mistralai/Voxtral-Mini-4B-Realtime-2602`, que é a mesma ordem da análise manual.
