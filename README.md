# App-esp32

Aplicación Android para una banda clasificadora. Está desarrollada en Kotlin y
Jetpack Compose; usa Firebase para cuentas, inventario y sincronización, y Paho
MQTT para comunicarse con un broker de la red Wi-Fi local.

## Arquitectura y pantallas

- `MainActivity.kt`: inicio de sesión y navegación Compose. Presenta Banda,
  Inventario, Configuración, Energía e Historial de conexión.
- `MqttController.kt`: conexión MQTT por Wi-Fi, reintentos, suscripciones,
  publicación de comandos, validación de decisiones y estadísticas.
- `MqttJsonValidator.kt`: valida los campos, tipos y rangos del protocolo.
- `BrokerConfig.kt`: configuración del broker; usuario y clave se cifran con
  Android Keystore. No se incluyen valores de conexión en el código.
- `LocalDataStore.kt` y `FirebaseSyncWorker.kt`: Room, cola local y sincronización
  con Firestore mediante WorkManager cuando hay red.
- `FirebaseRepository.kt`: autenticación, perfiles, roles e inventario.
- `clasificador_color_esp32.ino`: firmware ESP32 con PubSubClient.

## Configuración

1. Abre la carpeta raíz en Android Studio y configura Firebase Authentication y
   Firestore. Publica las reglas de `firestore.rules` y coloca tu
   `google-services.json` en `app/google-services.json`.
2. Inicia un broker MQTT en la misma red Wi-Fi del teléfono y el ESP32. El puerto
   predeterminado es `1883`; activa TLS en Configuración para usar `8883`.
3. En la app inicia sesión y abre Configuración. Ingresa IP local, puerto, usuario
   y clave del broker. El estado debe pasar a Conectado.
4. En Arduino IDE instala ESP32Servo, PubSubClient, ArduinoJson 6 y
   hd44780. Carga el sketch en la placa y abre el Monitor Serie a
   115200 baudios con fin de línea. En el primer arranque ingresa SSID, clave
   Wi-Fi, host, puerto y credenciales MQTT. Si activas TLS, ingresa el PEM de la
   CA confiable y termina con `FIN_CA`. La configuración se conserva en NVS.
5. Para cambiar los datos del ESP32 envía `CONFIGURAR` por el Monitor Serie.
   Configura en el ESP32 los mismos datos MQTT que en la pantalla Android.

El LCD I2C 16x2 alterna cada 2,5 segundos entre estado/conectividad,
contadores de cajas y velocidad/servo. Si no se detecta, el firmware informa
el error por Serial y continúa funcionando sin LCD.

El teléfono y el ESP32 deben compartir la red del broker; una red de invitados
puede bloquear el tráfico entre dispositivos. El ESP32 y la app intercambian los
tópicos `banda/sensor/color`, `banda/estado`, `banda/resultado` y
`banda/comandos`. La banda espera una decisión `aceptar` o `expulsar` para el ID
pendiente. El estado se publica cada segundo y la velocidad se expresa en
pasos/s entre 10 y 100. El ESP32 conserva los resultados en NVS y los reenvía
cada cinco segundos hasta recibir `resultado_ack` de la app después de su
persistencia local.

Firebase requiere Internet para iniciar sesión y sincronizar. Si Firebase no
está disponible, lecturas, resultados e inventario permanecen en Room y se
sincronizan al volver la conectividad. MQTT sigue siendo local e independiente.
Para TLS, Android debe confiar en el certificado del broker y el ESP32 debe
recibir por serie el certificado CA correspondiente.

## Compilar

```powershell
.\gradlew.bat :app:assembleDebug
```

Para revisar las pruebas existentes:

```powershell
.\gradlew.bat testDebugUnitTest
```
