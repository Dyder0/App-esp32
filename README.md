# App-esp32

Aplicacion Android para supervisar una banda transportadora. La app esta
desarrollada en Kotlin con Jetpack Compose; se comunica con Firebase para
usuarios e inventario, y con un broker MQTT para intercambiar lecturas y
comandos con un ESP32 o con el simulador incluido.

> La pantalla Android no esta hecha en HTML. El archivo HTML de este repositorio
> es un simulador independiente para probar la comunicacion MQTT sin el ESP32.

## Contenido del proyecto

- [`app/src/main/java/com/example/iot/MainActivity.kt`](app/src/main/java/com/example/iot/MainActivity.kt):
  pantallas, estado de la app, comunicacion MQTT y decisiones de color.
- [`app/src/main/java/com/example/iot/FirebaseRepository.kt`](app/src/main/java/com/example/iot/FirebaseRepository.kt):
  autenticacion y operaciones de Firestore.
- [`app/src/main/res/`](app/src/main/res/): iconos, temas y otros recursos Android.
- [`app/src/test/java/com/example/iot/ColorRulesTest.kt`](app/src/test/java/com/example/iot/ColorRulesTest.kt):
  pruebas de las reglas de clasificacion por color.
- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml):
  configuracion Android y permiso de Internet.
- [`app/build.gradle.kts`](app/build.gradle.kts) y
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml): plugins y dependencias.
- [`simulador_banda_3d.html`](simulador_banda_3d.html): simulador web de la banda.
- [`GUIA_PUESTA_EN_MARCHA.txt`](GUIA_PUESTA_EN_MARCHA.txt): pasos para configurar
  Firebase, Firestore y MQTT.

## Probar la app

1. Abre la carpeta raiz del proyecto en Android Studio y espera a que Gradle
   termine la sincronizacion.
2. Descarga desde Firebase el archivo de configuracion Android
   `google-services.json` para el paquete `com.example.iot` y colocalo en
   `app/google-services.json`. Ese archivo esta excluido de Git; cada persona
   que clone el proyecto debe obtener su propia configuracion.
3. Habilita Firebase Authentication con correo y contrasena y configura
   Firestore. Sigue la guia para publicar reglas seguras y asignar el rol de
   administrador.
4. Inicia un broker MQTT accesible por el telefono y, si usas el simulador,
   habilita MQTT TCP en el puerto `1883` y WebSockets en el `9001`.
5. Ejecuta la app desde Android Studio e inicia sesion. En la configuracion MQTT
   usa la IP local del broker y el puerto `1883`.
6. Para simular el ESP32, abre `simulador_banda_3d.html` en un navegador que
   tenga Internet, conecta al broker por WebSockets (`9001`) e ingresa las
   credenciales MQTT que configuraste. Si el navegador y Mosquitto estan en el
   mismo computador, deja `localhost`; el telefono debe usar la IP de ese
   computador, no `localhost`.

El simulador carga MQTT.js desde jsDelivr y necesita Internet para esa
biblioteca. El ESP32 real requiere su propio firmware; ese firmware no forma
parte de este repositorio. Usa el simulador o el ESP32, no ambos a la vez,
porque los dos publicarian lecturas en los mismos topics.

MQTT sin TLS no cifra las credenciales. Mantén el broker en una red local
confiable; no expongas sus puertos a Internet ni publiques el archivo de
contrasenas de Mosquitto o credenciales.

## Pruebas

En Windows, desde la raiz del proyecto:

```powershell
.\gradlew.bat testDebugUnitTest
```

Para compilar la app:

```powershell
.\gradlew.bat assembleDebug
```

No es necesario reducir el codigo para publicarlo: cada componente tiene una
responsabilidad distinta. Para preparar una explicacion oral, empieza por
`MainActivity.kt`, sigue con `FirebaseRepository.kt` y termina con el simulador
HTML y las pruebas. La explicacion detallada para uso local esta en
`EXPLICACION_CODIGO_LOCAL.txt`; ese archivo esta ignorado por Git y no se sube.
