
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <Wire.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <ESP32Servo.h>
#include <hd44780.h>
#include <hd44780ioClass/hd44780_I2Cexp.h>
#include <string.h>

const char* TOPIC_COLOR = "banda/sensor/color";
const char* TOPIC_ESTADO = "banda/estado";
const char* TOPIC_RESULTADO = "banda/resultado";
const char* TOPIC_COMANDOS = "banda/comandos";

// Pines ESP32 DevKit de 30 pines
const int S0 = 32, S1 = 33, S2 = 18, S3 = 19, OUT_COLOR = 34;
const int PIN_SERVO = 13;
const int MOTOR[4] = {14, 27, 26, 25}; // IN1..IN4 del ULN2003

// LCD I2C 16x2; ajustar direccion y pines segun el modulo y la placa.
const uint8_t LCD_DIRECCION = 0x27;
const int LCD_SDA = 21;
const int LCD_SCL = 22;
hd44780_I2Cexp lcd(LCD_DIRECCION);

// Calibracion del sensor TCS3200/TCS230
const unsigned long UMBRAL_OBJETO = 250; // ajustar con las lecturas del monitor serie
const int ANGULO[3] = {30, 90, 150};
const char* NOMBRE[3] = {"ROJO", "VERDE", "AZUL"};
// Valores de referencia para coincidir con los colores iniciales de la app.
const uint8_t RGB[3][3] = {{220, 30, 30}, {30, 170, 60}, {30, 90, 230}};

WiFiClient wifiClient;
WiFiClientSecure wifiTlsClient;
PubSubClient mqtt;
Servo servo;
Preferences prefs;

String wifiSsid;
String wifiPassword;
String brokerHost;
String brokerUsername;
String brokerPassword;
String brokerCaCertificate;
uint16_t brokerPort = 1883;
bool brokerUsesTls = false;

bool bandaOn = false;
bool objetoDisponible = true;
bool lecturaPendiente = false;
bool resultadoPendiente = false;
bool resultadoAceptado = false;
bool servoActivo = false;
uint32_t idPendiente = 0;
uint8_t colorPendiente = 0;
uint32_t idResultadoPendiente = 0;
uint8_t colorResultadoPendiente = 0;
uint32_t siguienteId = 1;
uint32_t aceptadas = 0, expulsadas = 0;
uint32_t tWifi = 0, tMqtt = 0, tMedir = 0, tPaso = 0, tEstado = 0;
uint32_t tLecturaPendiente = 0, tResultadoPendiente = 0;
uint32_t inicioServo = 0, inicioEnfriamiento = 0;
uint16_t intervaloPasoMs = 16;
int pasoIdx = 0;
bool servoAttached = false;
bool servoReturning = false;
int servoAngle = 90;
bool lcdDisponible = false;
uint32_t tPaginaLcd = 0;
uint8_t paginaLcd = 0;

String leerLineaSerial(const String& mensaje) {
  Serial.println(mensaje);
  while (true) {
    while (!Serial.available()) delay(20);
    String valor = Serial.readStringUntil('\n');
    valor.trim();
    return valor;
  }
}

String leerDatoObligatorio(const String& etiqueta, const String& actual) {
  while (true) {
    String valor = leerLineaSerial(etiqueta + (actual.isEmpty() ? "" : " (vacío conserva el actual)"));
    if (valor.isEmpty() && !actual.isEmpty()) return actual;
    if (!valor.isEmpty()) return valor;
    Serial.println("Este dato es obligatorio.");
  }
}

String leerDatoOpcional(const String& etiqueta, const String& actual) {
  String valor = leerLineaSerial(etiqueta + (actual.isEmpty() ? "" : " (vacío conserva; - borra)"));
  if (valor == "-") return "";
  return valor.isEmpty() ? actual : valor;
}

String leerCertificadoCa(const String& actual) {
  Serial.println("Pega el certificado CA PEM, una línea por vez. Termina con FIN_CA.");
  if (!actual.isEmpty()) Serial.println("Deja la primera línea vacía para conservar el certificado actual.");
  String certificado;
  while (true) {
    while (!Serial.available()) delay(20);
    String linea = Serial.readStringUntil('\n');
    linea.trim();
    if (certificado.isEmpty() && linea.isEmpty() && !actual.isEmpty()) return actual;
    if (linea == "FIN_CA") break;
    if (!linea.isEmpty()) certificado += linea + "\n";
    if (certificado.length() > 3500) {
      Serial.println("Certificado demasiado largo; vuelve a ingresarlo.");
      certificado = "";
    }
  }
  if (certificado.indexOf("-----BEGIN CERTIFICATE-----") >= 0 &&
      certificado.indexOf("-----END CERTIFICATE-----") >= 0) return certificado;
  Serial.println("El PEM no parece un certificado X.509 válido.");
  return "";
}

void configurarRedPorSerial() {
  Serial.println("Configuración Wi-Fi/MQTT local. Los datos se guardan en NVS y el firmware no los imprime.");
  wifiSsid = leerDatoObligatorio("SSID de Wi-Fi local:", wifiSsid);
  wifiPassword = leerDatoOpcional("Clave Wi-Fi:", wifiPassword);
  brokerHost = leerDatoObligatorio("IP local del broker MQTT:", brokerHost);
  String puerto = leerLineaSerial("Puerto MQTT (1883 TCP, 8883 TLS):");
  String tlsInput = leerLineaSerial("Usar TLS: escribe 1 para sí o 0 para no:");
  if (tlsInput == "1") brokerUsesTls = true;
  if (tlsInput == "0") brokerUsesTls = false;
  if (puerto.isEmpty()) brokerPort = brokerUsesTls ? 8883 : 1883;
  else {
    long valorPuerto = puerto.toInt();
    if (valorPuerto < 1 || valorPuerto > 65535) {
      Serial.println("Puerto inválido; se usará el predeterminado según TLS.");
      brokerPort = brokerUsesTls ? 8883 : 1883;
    } else brokerPort = (uint16_t)valorPuerto;
  }
  brokerUsername = leerDatoOpcional("Usuario MQTT (vacío si el broker no requiere autenticación):", brokerUsername);
  brokerPassword = leerDatoOpcional("Clave MQTT (vacío si el broker no requiere autenticación):", brokerPassword);
  if (brokerUsesTls) {
    brokerCaCertificate = leerCertificadoCa(brokerCaCertificate);
    while (brokerCaCertificate.isEmpty()) brokerCaCertificate = leerCertificadoCa("");
  } else {
    brokerCaCertificate = "";
  }

  prefs.putString("wifi_ssid", wifiSsid);
  prefs.putString("wifi_pass", wifiPassword);
  prefs.putString("br_host", brokerHost);
  prefs.putUShort("br_port", brokerPort);
  prefs.putBool("br_tls", brokerUsesTls);
  prefs.putString("mqtt_user", brokerUsername);
  prefs.putString("mqtt_pass", brokerPassword);
  prefs.putString("br_ca", brokerCaCertificate);
  Serial.println("Configuración guardada. Reiniciando el ESP32.");
  delay(500);
  ESP.restart();
}

void cargarConfiguracionRed() {
  wifiSsid = prefs.getString("wifi_ssid", "");
  wifiPassword = prefs.getString("wifi_pass", "");
  brokerHost = prefs.getString("br_host", "");
  brokerPort = prefs.getUShort("br_port", 1883);
  brokerUsesTls = prefs.getBool("br_tls", false);
  brokerUsername = prefs.getString("mqtt_user", "");
  brokerPassword = prefs.getString("mqtt_pass", "");
  brokerCaCertificate = prefs.getString("br_ca", "");
  if (wifiSsid.isEmpty() || brokerHost.isEmpty() || (brokerUsesTls && brokerCaCertificate.isEmpty())) {
    configurarRedPorSerial();
  }
}

void escribirLineaLCD(uint8_t fila, const char* texto) {
  if (!lcdDisponible) return;
  char linea[17];
  memset(linea, ' ', 16);
  linea[16] = '\0';
  size_t largo = strlen(texto);
  if (largo > 16) largo = 16;
  memcpy(linea, texto, largo);

  static char lineasAnteriores[2][17] = {};
  if (strcmp(lineasAnteriores[fila], linea) == 0) return;
  lcd.setCursor(0, fila);
  lcd.print(linea);
  memcpy(lineasAnteriores[fila], linea, sizeof(linea));
}

void actualizarLCD() {
  if (!lcdDisponible) return;
  uint32_t ahora = millis();
  if (ahora - tPaginaLcd >= 2500) {
    tPaginaLcd = ahora;
    paginaLcd = (paginaLcd + 1) % 3;
  }

  char linea1[17];
  char linea2[17];
  if (paginaLcd == 0) {
    if (lecturaPendiente) {
      snprintf(linea1, sizeof(linea1), "Caja: esperando");
    } else if (resultadoPendiente) {
      snprintf(linea1, sizeof(linea1), "Res. pendiente");
    } else if (bandaOn) {
      snprintf(linea1, sizeof(linea1), "Banda: en marcha");
    } else {
      snprintf(linea1, sizeof(linea1), "Banda: detenida");
    }
    snprintf(linea2, sizeof(linea2), "WiFi %s MQTT %s",
             WiFi.status() == WL_CONNECTED ? "OK" : "--",
             mqtt.connected() ? "OK" : "--");
  } else if (paginaLcd == 1) {
    snprintf(linea1, sizeof(linea1), "Aceptadas: %lu", (unsigned long)aceptadas);
    snprintf(linea2, sizeof(linea2), "Expulsadas: %lu", (unsigned long)expulsadas);
  } else {
    snprintf(linea1, sizeof(linea1), "Velocidad: %lu", (unsigned long)(1000 / intervaloPasoMs));
    snprintf(linea2, sizeof(linea2), "Servo angulo:%d", servoAngle);
  }
  escribirLineaLCD(0, linea1);
  escribirLineaLCD(1, linea2);
}

const uint8_t SEQ[8][4] = {
  {1,0,0,0}, {1,1,0,0}, {0,1,0,0}, {0,1,1,0},
  {0,0,1,0}, {0,0,1,1}, {0,0,0,1}, {1,0,0,1}
};

unsigned long leerPeriodo(bool s2, bool s3) {
  digitalWrite(S2, s2);
  digitalWrite(S3, s3);
  delay(8);
  unsigned long periodo = pulseIn(OUT_COLOR, LOW, 30000);
  return periodo == 0 ? 30000 : periodo;
}

int medirColor() {
  unsigned long r = leerPeriodo(LOW, LOW);
  unsigned long g = leerPeriodo(HIGH, HIGH);
  unsigned long b = leerPeriodo(LOW, HIGH);
  Serial.printf("R=%lu G=%lu B=%lu\n", r, g, b);
  unsigned long menor = min(r, min(g, b));
  if (menor > UMBRAL_OBJETO) return -1;
  if (menor == r) return 0;
  if (menor == g) return 1;
  return 2;
}

void publicarEstado() {
  actualizarLCD();
  if (!mqtt.connected()) return;
  StaticJsonDocument<256> doc;
  doc["banda"] = (lecturaPendiente || resultadoPendiente)
    ? "Esperando decision"
    : (bandaOn ? "En marcha" : "Detenida");
  doc["sensor"] = mqtt.connected() ? "Conectado" : "Desconectado";
  doc["servo"] = servoAngle;
  doc["aceptadas"] = aceptadas;
  doc["expulsadas"] = expulsadas;
  doc["velocidad"] = (1000.0f / intervaloPasoMs);
  doc["unidadVelocidad"] = "pasos/s";
  char mensaje[256];
  size_t largo = serializeJson(doc, mensaje, sizeof(mensaje));
  mqtt.publish(TOPIC_ESTADO, mensaje, largo);
}

void publicarLectura(uint8_t color) {
  StaticJsonDocument<192> doc;
  doc["id"] = idPendiente;
  doc["r"] = RGB[color][0];
  doc["g"] = RGB[color][1];
  doc["b"] = RGB[color][2];
  doc["nombre"] = NOMBRE[color];
  doc["ts"] = millis();
  char mensaje[192];
  size_t largo = serializeJson(doc, mensaje, sizeof(mensaje));
  mqtt.publish(TOPIC_COLOR, mensaje, largo);
}

bool publicarResultado(bool aceptada) {
  if (!mqtt.connected()) return false;
  StaticJsonDocument<192> doc;
  doc["id"] = idResultadoPendiente;
  doc["r"] = RGB[colorResultadoPendiente][0];
  doc["g"] = RGB[colorResultadoPendiente][1];
  doc["b"] = RGB[colorResultadoPendiente][2];
  doc["resultado"] = aceptada ? "aceptada" : "expulsada";
  doc["ts"] = millis();
  char mensaje[192];
  size_t largo = serializeJson(doc, mensaje, sizeof(mensaje));
  return mqtt.publish(TOPIC_RESULTADO, mensaje, largo);
}

void completarDecision(bool aceptada) {
  if (!lecturaPendiente) return;

  idResultadoPendiente = idPendiente;
  colorResultadoPendiente = colorPendiente;
  resultadoAceptado = aceptada;
  prefs.putUInt("resId", idResultadoPendiente);
  prefs.putUChar("resColor", colorResultadoPendiente);
  prefs.putBool("resAceptado", resultadoAceptado);
  prefs.putBool("resPendiente", true);
  resultadoPendiente = true;

  if (aceptada) {
    aceptadas++;
    prefs.putUInt("aceptadas", aceptadas);
  } else {
    expulsadas++;
    prefs.putUInt("expulsadas", expulsadas);
    if (!servoAttached) {
      servo.attach(PIN_SERVO);
      servoAttached = true;
    }
    servoAngle = ANGULO[colorPendiente];
    servo.write(servoAngle);
    servoActivo = true;
    inicioServo = millis();
  }

  lecturaPendiente = false;
  prefs.putBool("pendiente", false);
  publicarResultado(resultadoAceptado);
  tResultadoPendiente = millis();
  publicarEstado();
}

void procesarComando(char* payload, unsigned int largo) {
  StaticJsonDocument<256> doc;
  DeserializationError error = deserializeJson(doc, payload, largo);
  if (error) {
    Serial.printf("Comando JSON invalido: %s\n", error.c_str());
    return;
  }

  const char* accion = doc["accion"] | "";
  if (strcmp(accion, "iniciar") == 0) {
    bandaOn = true;
  } else if (strcmp(accion, "detener") == 0) {
    bandaOn = false;
  } else if (strcmp(accion, "velocidad") == 0) {
    if (!doc["valor"].is<int>()) {
      Serial.println("Velocidad inválida: se requiere un entero entre 10 y 100 pasos/s.");
      return;
    }
    int pasosPorSegundo = doc["valor"].as<int>();
    if (pasosPorSegundo < 10 || pasosPorSegundo > 100) {
      Serial.println("Velocidad descartada: fuera del rango de 10 a 100 pasos/s.");
      return;
    }
    intervaloPasoMs = max(1, 1000 / pasosPorSegundo);
  } else if (strcmp(accion, "resultado_ack") == 0) {
    if (!doc["id"].is<uint32_t>()) {
      Serial.println("ACK de resultado invalido: se requiere un ID entero.");
      return;
    }
    uint32_t id = doc["id"].as<uint32_t>();
    if (!resultadoPendiente || id != idResultadoPendiente) {
      Serial.printf("ACK ignorado para resultado %lu; no coincide con el pendiente.\n",
                    (unsigned long)id);
      return;
    }
    resultadoPendiente = false;
    prefs.putBool("resPendiente", false);
    inicioEnfriamiento = millis();
    Serial.printf("Resultado de caja %lu confirmado por la app.\n", (unsigned long)id);
  } else if (strcmp(accion, "aceptar") == 0 || strcmp(accion, "expulsar") == 0) {
    uint32_t id = doc["id"] | 0UL;
    if (!lecturaPendiente || id != idPendiente) {
      Serial.printf("Decision ignorada para caja %lu; no coincide con la pendiente.\n", (unsigned long)id);
      return;
    }
    completarDecision(strcmp(accion, "aceptar") == 0);
  } else {
    Serial.printf("Accion no reconocida: %s\n", accion);
    return;
  }
  publicarEstado();
}

void alRecibirMqtt(char* topic, byte* payload, unsigned int largo) {
  if (strcmp(topic, TOPIC_COMANDOS) != 0) return;
  char mensaje[257];
  if (largo >= sizeof(mensaje)) {
    Serial.println("Comando MQTT demasiado largo.");
    return;
  }
  memcpy(mensaje, payload, largo);
  mensaje[largo] = '\0';
  procesarComando(mensaje, largo);
}

void conectarMqtt() {
  if (WiFi.status() != WL_CONNECTED || mqtt.connected()) return;
  if (millis() - tMqtt < 2000) return;
  tMqtt = millis();

  char clientId[32];
  snprintf(clientId, sizeof(clientId), "clasificador-%04X", (uint16_t)(ESP.getEfuseMac() & 0xFFFF));
  bool conectado = brokerUsername.isEmpty()
    ? mqtt.connect(clientId)
    : mqtt.connect(clientId, brokerUsername.c_str(), brokerPassword.c_str());
  if (conectado) {
    Serial.println("Conectado a Mosquitto.");
    mqtt.subscribe(TOPIC_COMANDOS, 1);
    publicarEstado();
    if (resultadoPendiente) {
      publicarResultado(resultadoAceptado);
      tResultadoPendiente = millis();
    } else if (lecturaPendiente) {
      publicarLectura(colorPendiente);
      tLecturaPendiente = millis();
    }
  } else {
    Serial.printf("Error MQTT: estado %d\n", mqtt.state());
  }
}

void asegurarWifi() {
  if (WiFi.status() == WL_CONNECTED || millis() - tWifi < 5000) return;
  tWifi = millis();
  WiFi.disconnect();
  WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
}

void clasificarSiHayObjeto() {
  if (!bandaOn || lecturaPendiente || resultadoPendiente || servoActivo || servoReturning ||
      millis() - tMedir < 150) return;
  if (millis() - inicioEnfriamiento < 500) return;
  tMedir = millis();

  int color = medirColor();
  if (color < 0) {
    objetoDisponible = true;
    return;
  }
  if (!objetoDisponible) return;

  objetoDisponible = false;
  lecturaPendiente = true;
  colorPendiente = (uint8_t)color;
  idPendiente = siguienteId++;
  prefs.putUInt("siguienteId", siguienteId);
  prefs.putBool("pendiente", true);
  prefs.putUInt("pendId", idPendiente);
  prefs.putUChar("pendColor", colorPendiente);
  Serial.printf("Caja %lu: %s, esperando decision de la app.\n",
                (unsigned long)idPendiente, NOMBRE[colorPendiente]);

  if (mqtt.connected()) {
    publicarLectura(colorPendiente);
    tLecturaPendiente = millis();
  } else {
    Serial.println("Lectura guardada en memoria no volatil; esperando reconexion para consultar la app.");
  }
  publicarEstado();
}

void pasoMotor() {
  bool avanzar = bandaOn && !lecturaPendiente && !resultadoPendiente &&
      !servoActivo && !servoReturning;
  if (!avanzar) {
    for (int i = 0; i < 4; i++) digitalWrite(MOTOR[i], LOW);
    return;
  }
  if (millis() - tPaso < intervaloPasoMs) return;
  tPaso = millis();
  for (int i = 0; i < 4; i++) digitalWrite(MOTOR[i], SEQ[pasoIdx][i]);
  pasoIdx = (pasoIdx + 1) % 8;
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(30000);
  prefs.begin("inventario", false);
  cargarConfiguracionRed();

  Wire.begin(LCD_SDA, LCD_SCL);
  int estadoLcd = lcd.begin(16, 2);
  if (estadoLcd == 0) {
    lcdDisponible = true;
    lcd.backlight();
    actualizarLCD();
  } else {
    Serial.printf("LCD I2C no disponible (codigo %d); el clasificador continuara sin pantalla.\n",
                  estadoLcd);
  }

  pinMode(S0, OUTPUT);
  pinMode(S1, OUTPUT);
  pinMode(S2, OUTPUT);
  pinMode(S3, OUTPUT);
  pinMode(OUT_COLOR, INPUT);
  digitalWrite(S0, HIGH);
  digitalWrite(S1, LOW); // escala de frecuencia 20 %
  for (int i = 0; i < 4; i++) pinMode(MOTOR[i], OUTPUT);

  servo.attach(PIN_SERVO);
  servoAttached = true;
  servo.write(servoAngle);
  servoReturning = true;
  inicioServo = millis();

  siguienteId = prefs.getUInt("siguienteId", 1);
  aceptadas = prefs.getUInt("aceptadas", 0);
  expulsadas = prefs.getUInt("expulsadas", 0);
  lecturaPendiente = prefs.getBool("pendiente", false);
  if (lecturaPendiente) {
    idPendiente = prefs.getUInt("pendId", 0);
    colorPendiente = prefs.getUChar("pendColor", 0);
    if (idPendiente == 0 || colorPendiente >= 3) {
      lecturaPendiente = false;
      prefs.putBool("pendiente", false);
      Serial.println("Se descarto un registro de lectura local invalido.");
    } else {
      Serial.printf("Lectura pendiente recuperada: caja %lu, %s.\n",
                    (unsigned long)idPendiente, NOMBRE[colorPendiente]);
    }
  }
  resultadoPendiente = prefs.getBool("resPendiente", false);
  if (resultadoPendiente) {
    idResultadoPendiente = prefs.getUInt("resId", 0);
    colorResultadoPendiente = prefs.getUChar("resColor", 0);
    resultadoAceptado = prefs.getBool("resAceptado", false);
    if (idResultadoPendiente == 0 || colorResultadoPendiente >= 3) {
      resultadoPendiente = false;
      prefs.putBool("resPendiente", false);
      Serial.println("Se descarto un resultado local invalido.");
    } else {
      lecturaPendiente = false;
      prefs.putBool("pendiente", false);
      Serial.printf("Resultado pendiente recuperado: caja %lu.\n",
                    (unsigned long)idResultadoPendiente);
    }
  }

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);
  Serial.println("Conectando a la red Wi-Fi...");
  WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
  if (brokerUsesTls) {
    wifiTlsClient.setCACert(brokerCaCertificate.c_str());
    mqtt.setClient(wifiTlsClient);
  } else {
    mqtt.setClient(wifiClient);
  }
  mqtt.setServer(brokerHost.c_str(), brokerPort);
  mqtt.setCallback(alRecibirMqtt);
  mqtt.setKeepAlive(30);
  mqtt.setSocketTimeout(2);
  mqtt.setBufferSize(256);
}

void loop() {
  if (Serial.available()) {
    String comandoSerie = Serial.readStringUntil('\n');
    comandoSerie.trim();
    if (comandoSerie == "CONFIGURAR") {
      mqtt.disconnect();
      WiFi.disconnect(true, true);
      configurarRedPorSerial();
    }
  }

  asegurarWifi();
  conectarMqtt();
  if (mqtt.connected()) mqtt.loop();
  actualizarLCD();
  if (mqtt.connected() && lecturaPendiente && millis() - tLecturaPendiente >= 5000) {
    publicarLectura(colorPendiente);
    tLecturaPendiente = millis();
  }
  if (mqtt.connected() && resultadoPendiente && millis() - tResultadoPendiente >= 5000) {
    publicarResultado(resultadoAceptado);
    tResultadoPendiente = millis();
  }

  if (servoActivo && millis() - inicioServo >= 500) {
    servoAngle = 90;
    servo.write(servoAngle);
    servoActivo = false;
    servoReturning = true;
    inicioServo = millis();
  }
  if (servoReturning && millis() - inicioServo >= 300) {
    servo.detach();
    servoAttached = false;
    servoReturning = false;
    inicioEnfriamiento = millis();
  }

  clasificarSiHayObjeto();
  pasoMotor();

  if (mqtt.connected() && millis() - tEstado >= 1000) {
    tEstado = millis();
    publicarEstado();
  }
}
