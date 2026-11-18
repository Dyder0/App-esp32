# Informe de comunicacion, almacenamiento y energia IoT

## Revision del proyecto

La app usa Kotlin, una sola Activity con interfaz Jetpack Compose, Firebase Auth/Firestore y MQTT con Eclipse Paho Java Client. La comunicacion del clasificador es Wi-Fi local con MQTT; Firebase requiere Internet solo para cuentas y sincronizacion externa. La libreria MQTT existente en el firmware ESP32 es PubSubClient.

La interfaz incluye acceso/registro, Banda, Inventario y Configuracion. Se agregaron Energia e Historial de conexion. El acceso de Operador muestra solo inventario; Mantenimiento tambien puede detener la banda y ajustar velocidad; Administrador dispone de alta/gestion de colores y usuarios, inicio y parada. La app no incluye Bluetooth ni permisos Bluetooth.

## Cambios implementados

- La pantalla Configuracion captura host local, puerto, usuario, clave y opcion TLS. Android cifra usuario/clave MQTT con AES-GCM y una clave de Android Keystore. No hay un broker ni una credencial MQTT escritos como valores predeterminados en el codigo.
- MQTT usa red Wi-Fi disponible, estado visible Conectado/Reconectando/Sin conexion, reintentos 2/4/8/16/30 segundos y resuscripcion despues de conectar. En segundo plano se desconecta al completar dos minutos y se reconecta al volver a primer plano.
- Los JSON se validan contra los campos del protocolo y sus rangos. Los mensajes invalidos se registran y descartan; los IDs se deduplican. Las decisiones requieren el ID pendiente y se descartan despues de 10 segundos.
- Room guarda lecturas, resultados, cache/inventario, cola Firebase e historial de conexion. WorkManager reintenta la cola cuando hay red y una sesion Firebase valida. La pantalla muestra el total pendiente.
- Energia muestra bateria/carga, ahorro del sistema, duracion MQTT y conteos de mensajes. En ahorro sube keep-alive, reduce refresco visual y solicita QoS 0 para banda/estado.
- La app advierte cortes, conserva visibles los ultimos datos y deshabilita controles de banda sin broker. Android declara INTERNET y ACCESS_NETWORK_STATE; minSdk 24 y targetSdk 37. No se agregaron servicios en primer plano, wake locks ni codigo nativo propio.
- El firmware ESP32 usa PubSubClient, almacena configuracion Wi-Fi/MQTT en Preferences/NVS, permite TLS con CA y mantiene la banda detenida mientras espera aceptar/expulsar para el ID pendiente. Conserva cada resultado hasta el `resultado_ack` que Android publica despues de persistirlo localmente.

## Seguridad y limites

La implementacion aplica controles recomendados por la familia ISO/IEC 27000, pero no declara ni certifica conformidad. Cuando TLS esta desactivado, el usuario, la clave y los mensajes circulan sin cifrado de transporte en el broker TCP local. Para TLS, el telefono necesita confiar en el certificado del broker y el ESP32 necesita su CA. Las credenciales del ESP32 se guardan en NVS, no se protegen con Android Keystore. Firebase requiere Internet para autenticacion y sincronizacion. El historial se limita a UID/rol/motivo/hora requeridos por la operacion.

La banda, sensor, LCD, broker, TLS y comportamiento ante cortes deben validarse en el equipo real. Una compilacion no prueba el cableado, direccion I2C, certificado o ejecucion fisica.

## Lista de pruebas manuales

1. Con telefono, ESP32 y broker en la misma Wi-Fi sin acceso a Internet, confirmar conexion y recepcion MQTT.
2. Apagar Wi-Fi del telefono y detener/reiniciar el broker; verificar estado, secuencia de esperas 2/4/8/16/30 s, botones deshabilitados, ultimo dato/hora y evento de historial. Restablecer red y confirmar resuscripcion.
3. Publicar JSON mal formado, sin campos, con tipos incorrectos y RGB/velocidad fuera de rango; confirmar descarte y registro sin cierre de la app. Repetir un ID y confirmar que no se contabiliza dos veces.
4. Enviar aceptar/expulsar con ID incorrecto y con una lectura de mas de 10 s; confirmar descarte. Con ID actual, verificar que ESP32 publique el resultado y que la app lo guarde antes de enviar `resultado_ack`; comprobar en el monitor serie que el ACK limpia NVS.
5. Desconectar Internet/Firebase conservando broker; recibir lecturas/resultados y modificar inventario. Confirmar que aumente la cola Room y que WorkManager la sincronice al recuperar Internet y sesion.
6. Activar ahorro Android y ahorro manual; comprobar keep-alive 120 s, QoS 0 en banda/estado y refresco visual reducido. Verificar bateria/carga y contadores MQTT.
7. Dejar la app en segundo plano mas de 2 min; verificar desconexion y reconexion al volver.
8. Probar cuentas Operador, Mantenimiento y Administrador; confirmar pantallas y controles disponibles para cada rol.
9. Configurar TLS con certificado confiable y puerto 8883; confirmar conexion cifrada. En puerto 1883, confirmar conexion local sin TLS.
10. Compilar y ejecutar el firmware con la placa, LCD y sensores reales; comprobar que el LCD alterne estado/conectividad, contadores y velocidad/servo, además de I2C, pines, motor, servo, calibracion RGB y persistencia NVS tras reinicio.

Las pruebas de hardware requieren la placa y el broker; la compilacion de software por si sola no valida esos resultados.
