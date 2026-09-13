# Protopanda Controller

<p align="center">
  <img src="https://github.com/mockthebear/proto-panda/raw/main/doc/logoprotopanda.png" alt="Logo do Protopanda" width="330">
</p>

[English](README.md) | Português

Controlador Android que funciona como um periférico Bluetooth Low Energy (BLE)
para plataformas Protopanda. Ele envia os dados de movimento do telefone e
o estado dos botões na tela pelo protocolo esperado pelo firmware do receptor.

## Releases

[Baixar a versão mais recente](https://github.com/junglivre/ProtopandaController/releases/latest).

As versões são publicadas no GitHub, mas você também pode [compilar o app](#compilar-e-executar).

## Recursos

- Periférico BLE para a plataforma Protopanda.
- Controles de movimento com acelerômetro e giroscópio.
- Controles por toque em estilo D-pad.
- UUIDs GATT configuráveis.
- Encerramento automático do app quando o Bluetooth é desligado.

## Requisitos

- Android 5.0 (API nível 21) ou superior. O app tem como alvo o Android 17 (API nível 37).
- Um telefone cujo chipset suporte periférico BLE e modo de anúncio.
- Acelerômetro e giroscópio para os controles de movimento.
- Android Studio ou JDK 17 para compilar a partir do código-fonte.

O suporte a BLE não basta: o telefone precisa suportar anúncios em modo
periférico. Sem os sensores de movimento, o controlador abre, mas não fornece
os controles de movimento.

## Compilar e executar

```bash
./gradlew assembleDebug
```

O APK de depuração é gerado em `app/build/outputs/apk/debug/app-debug.apk`.
Abra o projeto no Android Studio para executar em um dispositivo físico e aceite
as permissões Bluetooth no primeiro uso.

Para uma compilação release otimizada localmente, execute `./gradlew
assembleRelease`. A variante release usa R8 para reduzir código e recursos.
Crie `keystore.properties` localmente apenas se precisar de uma compilação
assinada; o Git ignora esse arquivo.

## Comportamento do Bluetooth

O app inicia o periférico BLE depois que o Bluetooth está ligado e as permissões
foram concedidas. Desligar o Bluetooth encerra o serviço e o app. O botão de
fechar na barra superior faz o mesmo. O Android não permite que apps comuns
desliguem o Bluetooth, então esse botão não altera o estado do rádio.

## Identidade e protocolo BLE

A identidade padrão é compatível com plataformas Protopanda. Em
**Configurações**, é possível personalizar o UUID do serviço, o UUID da
característica de leitura/escrita e o UUID da característica de notificações.
Ao salvar, o receptor conectado é desconectado e o periférico BLE é reiniciado.
O app não anuncia nem altera o nome Bluetooth do telefone.

| Item | Padrão |
|---|---|
| Nome do dispositivo anunciado | Não incluído |
| UUID do serviço | `d4d31337-c4c1-c2c3-b4b3-b2b1a4a3a2a1` |
| UUID leitura/escrita | `d4d3fafb-c4c1-c2c3-b4b3-b2b1a4a3a2a1` |
| UUID notificações | `d4d3afaf-c4c1-c2c3-b4b3-b2b1a4a3a2a1` |
| Intervalo de notificação | 50 ms |

O pacote tem 23 bytes: acelerômetro, giroscópio, placeholder de temperatura,
ID atribuído pelo receptor e os oito estados de botão. Os valores de movimento
são escalonados para corresponder à configuração do LSM6DS3 do firmware.

## Sobre e créditos

O Protopanda Controller é um controle BLE de código aberto para a plataforma Protopanda, construído com a comunidade do projeto.

Código-fonte: [junglivre/ProtopandaController](https://github.com/junglivre/ProtopandaController).

- [GooDDu](https://github.com/GooDDu) — primeira versão do app.
- [mockthebear](https://github.com/mockthebear) — criador do Protopanda e colaborador em melhorias do app.
- [junglivre](https://github.com/junglivre) — melhorias do app e publicação no Google Play.
