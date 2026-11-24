# CamIP

Transforma o celular Android numa **câmera IP ONVIF/RTSP** para NVRs, VMS, VLC e sistemas próprios. É gratuito, não tem limite de tempo e todas as funções vêm liberadas.

## Instalar

Baixe o `CamIP.apk` mais recente na aba **Releases** e abra no celular. Cada envio para o branch `main` gera um APK novo pelo GitHub Actions.

## Funções

- **Vídeo**
  - câmera traseira ou frontal e resoluções até 4K, conforme o celular;
  - bitrate de 0,5 a 20 Mbps e de 5 a 60 fps;
  - **H.264 ou H.265**, rotação (Auto, 0°, 90°, 180° ou 270°) e espelho;
  - data e hora na imagem, nome da câmera, zoom e lanterna.
- **Substream** `/stream2`, com resolução, bitrate e FPS próprios.
- **Áudio** do microfone em AAC, nos dois streams.
- **RTSP**
  - por TCP (interleaved) e UDP, com vários clientes ao mesmo tempo;
  - autenticação Digest e Basic;
  - quadro-chave a cada 1 s e envio imediato quando um cliente conecta.
- **ONVIF**
  - Device, Media (ver10) e Media2 (ver20, informa o H.265 corretamente);
  - WS-Security (UsernameToken) e HTTP Digest;
  - o NVR consegue mudar resolução, FPS e bitrate;
  - SystemReboot.
- **Descoberta WS-Discovery:** o NVR encontra a câmera sozinho.
- **Snapshot** em `/snapshot.jpg` e **página web** da câmera, com a imagem atualizada a cada segundo.
- **Estabilidade**
  - serviço em primeiro plano que funciona com a tela desligada;
  - mantém o processador e o Wi-Fi acordados;
  - reinicia o vídeo sozinho se a câmera travar ou se outro app usar a câmera;
  - se o IP mudar, atualiza os endereços e a descoberta;
  - modo "tela preta" para economizar bateria.

## Endereços (padrão)

| | |
|---|---|
| RTSP principal | `rtsp://IP:8554/stream1` |
| RTSP extra | `rtsp://IP:8554/stream2` |
| ONVIF | `http://IP:8080/onvif/device_service` |
| Snapshot | `http://IP:8080/snapshot.jpg` |

O usuário é `admin`. A senha é gerada na primeira execução e aparece em **Ajustes → Acesso ONVIF**.

## Como funciona por dentro

- **Imagem:** câmera (Camera2) → OpenGL, que gira, recorta e grava data e hora → MediaCodec (encoder de hardware) → servidor RTSP próprio em Java.
- **Rede:** a parte de rede (`core/`: RTSP, RTP H.264/H.265/AAC, ONVIF, WS-Discovery, autenticação) é Java puro. Ela foi testada no computador com ffmpeg/ffprobe (TCP, UDP, H.264, H.265, áudio e senha) e com o cliente `onvif-zeep`.
