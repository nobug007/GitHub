// ESP32-C5 flash/board sanity test: blink the on-board RGB LED (GPIO27 WS2812) every 2 s and
// print to Serial, so we can confirm both that flashing works and that the sketch is running.
#include <Arduino.h>

static const uint32_t INTERVAL_MS = 2000;
static uint32_t last = 0;
static bool on = false;
static uint32_t count = 0;

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println();
  Serial.println("==== ESP32-C5 LED Blink test (2s interval) ====");
#ifdef RGB_BUILTIN
  Serial.printf("RGB_BUILTIN present (on-board WS2812 @ GPIO27)\n");
#endif
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  uint32_t now = millis();
  if (now - last >= INTERVAL_MS) {
    last = now;
    on = !on;
    count++;
#ifdef RGB_BUILTIN
    if (on) rgbLedWrite(RGB_BUILTIN, 0, 80, 0);   // green
    else    rgbLedWrite(RGB_BUILTIN, 0, 0, 0);    // off
#endif
    digitalWrite(LED_BUILTIN, on ? HIGH : LOW);
    Serial.printf("[%lus] Blink #%lu  LED=%s\n", (unsigned long)(now / 1000), (unsigned long)count, on ? "ON" : "OFF");
  }
}
