# Ditado — bolha flutuante de transcrição 100% local (Android, S25 Ultra)

Uma bolha que fica por cima de qualquer app. Você toca, fala em português, inglês ou espanhol
(detectados sozinhos), toca de novo e o texto já pontuado é digitado no campo em foco.
Nada sai do celular.

- **Modelo:** NVIDIA Parakeet TDT 0.6B v3 int8 (via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8) + Silero VAD
- **Texto ao vivo:** cada pausa vira uma frase transcrita no painel do topo, enquanto você ainda fala
- **"Inteligente":**
  - pontuação e maiúsculas do próprio modelo
  - junta frases cortadas por respiração ("tosca, com teto…")
  - margem de áudio para não perder palavras nas bordas
  - tira hesitações (hum, ã, uh)
  - comandos "nova linha" / "novo parágrafo" (também em EN/ES)
- **Saída:** digita no campo em foco (serviço de acessibilidade opcional) ou copia para a área de transferência

## Instalar no S25 Ultra

1. Copie `Ditado-0.1.0-arm64.apk` para o celular e abra (permita "instalar apps desconhecidos").
2. No app:
   1. **Baixar modelo** (~670 MB, uma vez, use Wi-Fi). Cada arquivo é conferido por SHA-256.
      Sem internet: baixe os arquivos listados em `ModelStore.kt` no PC, copie para uma pasta e use **Importar de uma pasta**.
   2. Permita **Microfone** e **Mostrar sobre outros apps**.
   3. Opcional: **Digitar no campo**. Isso abre Acessibilidade › Apps instalados › Ditado. Como o app foi
      instalado por APK, o Android bloqueia de início: vá em Configurações › Apps › Ditado › ⋮ ›
      *Permitir configurações restritas* e volte.
3. **Abrir bolha**.

Uso: toque = começa (vermelho) · toque = termina (laranja enquanto finaliza) · arrastar = mover · segurar = fechar.

## Compilar

```bash
export ANDROID_HOME=/caminho/do/android-sdk   # platform 35, build-tools 35
./gradlew :app:assembleRelease                # baixa a AAR do sherpa-onnx sozinho
./gradlew :app:testDebugUnitTest              # testes do pós-processamento de texto
```

Teste ponta a ponta do motor no PC (o mesmo `SpeechEngine.kt` do app, com o JNI Linux do sherpa-onnx):

```bash
./gradlew :app:testDebugUnitTest --tests '*SpeechEngineJvmTest*' \
  -Pdictate.modelDir=/pasta/do/modelo -Pdictate.audio=audio_16k_f32le.raw \
  -Pdictate.jni=/pasta/sherpa-onnx-v1.13.8-linux-x64-jni/lib
```

Resultado em 2026-10-03, com 24,6 s de fala (PT do MLS + EN + ES, com pausas):

- 4 frases ao vivo
- texto final correto nas 3 línguas
- RTF 0,078 num servidor x86 com 4 threads

**Ainda não medido no S25 Ultra:** a expectativa é RTF bem abaixo de 0,3 no Oryon, mas isso precisa ser confirmado no aparelho.

## Limites conhecidos (v0.1)

- O texto aparece **por frase** (a cada pausa), não palavra por palavra. Streaming palavra a palavra
  vem com o Nemotron 3.5 streaming quando o sherpa-onnx expuser o prompt de idioma dele na API Android.
- Trocar de língua **no meio da mesma frase** pode errar. Uma pausa curta entre as línguas resolve.
- O modelo ocupa ~700 MB de RAM enquanto a bolha está aberta. Fechar a bolha libera a memória.
- APK assinado com chave de debug (sideload). Para publicar, troque a assinatura.
