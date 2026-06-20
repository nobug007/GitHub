const defaultConfig = {
  groupId: "korea-seoul-00001",
  interval: 600000,
  safeZones: [],
  families: []
};

const state = globalThis.safeFinderServerConfig || structuredClone(defaultConfig);
globalThis.safeFinderServerConfig = state;

export function nowKst() {
  const date = new Date();
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace("Z", "+09:00");
}

export function getConfig() {
  return state;
}

export function syncResponse() {
  return {
    groupId: state.groupId,
    success: true,
    interval: state.interval,
    serverTime: nowKst(),
    data: {
      safeZones: state.safeZones,
      families: state.families
    }
  };
}

export function upsertSafeZone(input) {
  const safeZone = {
    operation: "CREATE",
    safeZoneId: input.safeZoneId || `SZ-${Date.now()}`,
    type: input.type || "wifi",
    name: input.name || "",
    familyId: input.familyId || undefined
  };

  if (safeZone.type === "gps") {
    safeZone.latitude = Number(input.latitude || 0);
    safeZone.longitude = Number(input.longitude || 0);
    safeZone.radius = Number(input.radius || 0);
  }

  state.safeZones = state.safeZones.filter(item => item.safeZoneId !== safeZone.safeZoneId);
  state.safeZones.push(safeZone);
  return safeZone;
}

export function deleteSafeZone(safeZoneId) {
  state.safeZones = state.safeZones.filter(item => item.safeZoneId !== safeZoneId);
}

export function upsertFamily(input) {
  const family = {
    operation: "CREATE",
    familyId: input.familyId || `FM-${Date.now()}`,
    name: input.name || "",
    relation: input.relation || "FAMILY",
    phoneNo: input.phoneNo || "",
    priority: Number(input.priority || 1),
    notificationEnabled: input.notificationEnabled !== false
  };

  state.families = state.families.filter(item => item.familyId !== family.familyId);
  state.families.push(family);
  return family;
}

export function deleteFamily(familyId) {
  state.families = state.families.filter(item => item.familyId !== familyId);
}
