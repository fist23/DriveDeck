# DriveDeck

Primeiro protótipo Android do conceito **Car Mode + Driving Overlay**.

## Visão do produto

Uma camada de condução modular que acompanha o utilizador enquanto ele usa Waze,
Google Maps, Spotify, YouTube Music ou outras apps Android. A aplicação não tenta
incorporar a interface dessas apps: abre-as e disponibiliza controlos próprios por
cima delas.

## MVP 0.1

- ecrã inicial de configuração;
- permissão para desenhar sobre outras aplicações;
- serviço foreground para manter o overlay ativo;
- overlay compacto inferior com atalhos de música, play, telefone e dashboard;
- atalho de abertura da app de navegação escolhida;
- controlo play/pause, anterior e seguinte da sessão multimédia ativa.
- título e artista da música ativa, atualizados automaticamente.
- onboarding inicial para escolher apps e permissões;
- arranque automático do overlay ao ligar um dispositivo Bluetooth de áudio automóvel.
- seleção explícita do dispositivo Bluetooth emparelhado, evitando fones e outros dispositivos;
- opção de fechar o overlay quando o dispositivo Bluetooth selecionado se desliga;
- opção para iniciar a reprodução automaticamente ao abrir o Car Mode;
- overlay arrastável, com posição guardada entre sessões;
- modo compacto/expandido com atalhos para navegação, música e fechar.

## Próximos incrementos

1. seleção real de apps instaladas por categoria;
2. arrastar/reordenar módulos e modos vertical/horizontal;
3. identificação do título/artista da MediaSession no overlay;
4. NotificationListenerService para cartões de mensagens;
5. deep links para destinos no Waze e Google Maps;
6. perfis de condução.

O projeto assume distribuição direta por APK nesta fase.

## Assinatura de atualizações

Todas as versões distribuídas devem usar a mesma keystore de release. A pipeline
usa estes GitHub Actions secrets:

- `DRIVEDECK_KEYSTORE_BASE64`: keystore `.jks` codificada em Base64;
- `DRIVEDECK_KEY_ALIAS`: alias da chave;
- `DRIVEDECK_STORE_PASSWORD`: password da keystore;
- `DRIVEDECK_KEY_PASSWORD`: password da chave.

Nunca substituir a keystore depois de publicar uma versão. Sem a chave privada
usada na versão instalada, o Android não permite atualizar por cima dela.

O arranque Bluetooth depende da permissão de overlay e da permissão Bluetooth do
Android. Em versões recentes, o sistema pode bloquear o arranque de serviços em
segundo plano; nesse caso o utilizador pode iniciar o overlay manualmente.

## Gestão de permissões

- Overlay: acesso especial em Definições do Android.
- Bluetooth: `BLUETOOTH_CONNECT` pedido quando o arranque automático é ativado.
- Notificações: pedido no onboarding para manter visível o serviço foreground.
- Controlos de música: acesso especial ao `NotificationListenerService`.
- Foreground service: declarado como `specialUse` e iniciado apenas depois de
  validar o overlay.
