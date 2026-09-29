#include "sfa_ble.h"
#include "sfa_config.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <ArduinoJson.h>

static SfaStore* gStore = nullptr;
static BLEServer* gServer = nullptr;
static BLECharacteristic* gInfoChar = nullptr;
static BLECharacteristic* gStatusChar = nullptr;
static bool gAdvertising = false;
static bool gCentralConnected = false;

static String gConfigBuffer;
static bool   gReceiving = false;
static int    gExpectedBytes = 0;
static String gPendingConfig;
static bool   gHasPending = false;

static String gRegState = "WAITING";
static int    gServerCode = -1;
static String gServerBody = "";

static String deviceInfoJson() {
  JsonDocument d;
  String id = gStore ? gStore->deviceId() : SFA_DEFAULT_DEVICE_ID;
  d["deviceId"] = id;
  d["serialNo"] = id;
  d["name"] = SFA_BLE_DEVICE_NAME;
  String s; serializeJson(d, s); return s;
}

static String statusJson() {
  JsonDocument d;
  d["deviceId"] = gStore ? gStore->deviceId() : SFA_DEFAULT_DEVICE_ID;
  d["configReceived"] = gHasPending || gRegState != "WAITING";
  d["registrationState"] = gRegState;
  if (gServerCode >= 0) d["serverStatusCode"] = gServerCode; else d["serverStatusCode"] = nullptr;
  d["serverResponseBody"] = gServerBody.length() ? gServerBody.substring(0, 180) : String("");
  d["registered"] = (gServerCode >= 200 && gServerCode < 300);
  String s; serializeJson(d, s); return s;
}

static void processCompleteConfig(const String& json) {
  JsonDocument doc;
  if (deserializeJson(doc, json) != DeserializationError::Ok) {
    gRegState = "FAILED";
    return;
  }
  // Hand off to the main loop (it decides: full config -> save+register, or safeZoneUpdate -> apply).
  gPendingConfig = json;
  gHasPending = true;
  gRegState = "REGISTERING";
  gServerCode = -1;
  gServerBody = "";
}

static void handleConfigWrite(const String& value) {
  if (value.startsWith("BEGIN:")) {
    gExpectedBytes = value.substring(6).toInt();
    gConfigBuffer = "";
    gReceiving = true;
  } else if (value == "END") {
    if (!gReceiving) return;
    gReceiving = false;
    if (gExpectedBytes > 0 && (int)gConfigBuffer.length() != gExpectedBytes) {
      gRegState = "FAILED";
      return;
    }
    processCompleteConfig(gConfigBuffer);
    gConfigBuffer = "";
  } else if (gReceiving) {
    gConfigBuffer += value;
  } else {
    JsonDocument probe;
    if (deserializeJson(probe, value) == DeserializationError::Ok) processCompleteConfig(value);
  }
}

// Single-argument overrides work under both Bluedroid and NimBLE (the ESP32-C5 uses NimBLE,
// where the 2-arg param type esp_ble_gatts_cb_param_t is not defined).
class CharCB : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic* c) override {
    String uuid = c->getUUID().toString().c_str();
    if (uuid.equalsIgnoreCase(SFA_DEVICE_INFO_UUID)) c->setValue(deviceInfoJson().c_str());
    else if (uuid.equalsIgnoreCase(SFA_STATUS_UUID)) c->setValue(statusJson().c_str());
  }
  void onWrite(BLECharacteristic* c) override {
    String uuid = c->getUUID().toString().c_str();
    if (uuid.equalsIgnoreCase(SFA_CONFIG_WRITE_UUID)) handleConfigWrite(c->getValue());
  }
};

class SrvCB : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { gCentralConnected = true; }
  void onDisconnect(BLEServer*) override {
    gCentralConnected = false;
    if (gAdvertising) BLEDevice::startAdvertising();   // keep discoverable
  }
};

void sfaBleBegin(SfaStore* store) {
  gStore = store;
  BLEDevice::init(SFA_BLE_DEVICE_NAME);
  gServer = BLEDevice::createServer();
  gServer->setCallbacks(new SrvCB());
  BLEService* svc = gServer->createService(SFA_SERVICE_UUID);

  gInfoChar = svc->createCharacteristic(SFA_DEVICE_INFO_UUID, BLECharacteristic::PROPERTY_READ);
  gInfoChar->setCallbacks(new CharCB());
  gInfoChar->setValue(deviceInfoJson().c_str());

  BLECharacteristic* cfg = svc->createCharacteristic(
      SFA_CONFIG_WRITE_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cfg->setCallbacks(new CharCB());

  gStatusChar = svc->createCharacteristic(SFA_STATUS_UUID, BLECharacteristic::PROPERTY_READ);
  gStatusChar->setCallbacks(new CharCB());
  gStatusChar->setValue(statusJson().c_str());

  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SFA_SERVICE_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);
}

void sfaBleSetAdvertising(bool on) {
  if (on == gAdvertising) return;
  gAdvertising = on;
  if (on) BLEDevice::startAdvertising();
  else BLEDevice::stopAdvertising();
}
bool sfaBleIsAdvertising() { return gAdvertising; }
bool sfaBleCentralConnected() { return gCentralConnected; }

bool sfaBleTakePendingConfig(String& out) {
  if (!gHasPending) return false;
  out = gPendingConfig;
  gHasPending = false;
  gPendingConfig = "";
  return true;
}

void sfaBleSetRegistrationState(const String& state, int serverCode, const String& body) {
  gRegState = state;
  gServerCode = serverCode;
  gServerBody = body;
}
