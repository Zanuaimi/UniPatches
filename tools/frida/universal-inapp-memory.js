'use strict';

/*
 * UniPatches Frida companion for an existing in-app Gadget.
 * Local entitlement getters are app-package scoped. Memory commands operate
 * in the current process only. BillingClient setup and product catalog paths
 * are deliberately left to the normal store or UniPatches adapters.
 */
const SETTINGS = {
  entitlementGetters: true,
  maxScanBytes: 64 * 1024 * 1024,
  maxRegionBytes: 16 * 1024 * 1024,
  maxResults: 1024,
  // Optional autonomous Gadget-script command, e.g. { value: 100, filter: 'libil2cpp.so' }.
  startupScan: null
};

const getterName = /^(?:is|has|can)(?:Premium|Pro|Subscribed|Subscription|Entitlement|Entitled|Paid|Unlocked|FullVersion)(?:Active|Enabled|Access|User)?$/;
const installed = new Set();
let matches = [];
let scanning = false;

function say(message) { console.log('[UniPatches Frida] ' + message); }
function int32(value) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < -2147483648 || number > 2147483647) {
    throw new Error('Expected signed int32');
  }
  return number;
}
function pattern(value) {
  const word = value >>> 0;
  return [0, 8, 16, 24].map(shift =>
    ('0' + ((word >>> shift) & 255).toString(16)).slice(-2)).join(' ');
}

function installEntitlementHooks(attempt) {
  if (!SETTINGS.entitlementGetters || !Java.available) return;
  Java.perform(function () {
    const app = Java.use('android.app.ActivityThread').currentApplication();
    if (app === null) {
      if (attempt < 50) setTimeout(() => installEntitlementHooks(attempt + 1), 100);
      else say('No Application instance; Java hooks skipped');
      return;
    }
    const prefix = String(app.getPackageName()) + '.';
    function discover() {
      let count = 0;
      for (const className of Java.enumerateLoadedClassesSync()) {
        if (!className.startsWith(prefix) || className.includes('.R$')) continue;
        try {
          const klass = Java.use(className);
          const declared = klass.class.getDeclaredMethods();
          for (let i = 0; i < declared.length; i++) {
            const reflected = declared[i];
            const name = String(reflected.getName());
            if (!getterName.test(name) || String(reflected.getReturnType().getName()) !== 'boolean' ||
                reflected.getParameterTypes().length !== 0) continue;
            const key = className + '#' + name;
            if (installed.has(key) || !klass[name]) continue;
            const overload = klass[name].overload();
            overload.implementation = function () { return true; };
            installed.add(key);
            count++;
          }
        } catch (error) {
          // A class can be unloading, inaccessible, or lack a Frida wrapper.
        }
      }
      say('App=' + prefix.slice(0, -1) + '; new local getter hooks=' + count +
          '; total=' + installed.size);
    }
    discover();
    setTimeout(discover, 4000);
    setTimeout(discover, 12000);
  });
}

async function scanInt(value, regionName) {
  value = int32(value);
  if (scanning) throw new Error('Scan already running');
  scanning = true;
  matches = [];
  let scannedBytes = 0;
  const filter = String(regionName || '');
  const ranges = Process.enumerateRanges({ protection: 'rw-', coalesce: true })
    .filter(r => r.size <= SETTINGS.maxRegionBytes &&
      (!filter || (r.file && String(r.file.path).includes(filter))));
  try {
    for (const range of ranges) {
      if (matches.length >= SETTINGS.maxResults ||
          scannedBytes + range.size > SETTINGS.maxScanBytes) break;
      scannedBytes += range.size;
      await new Promise(resolve => {
        Memory.scan(range.base, range.size, pattern(value), {
          onMatch(address) {
            matches.push({ address, value });
            if (matches.length >= SETTINGS.maxResults) return 'stop';
          },
          onError(reason) { say('Skipping unreadable range: ' + reason); resolve(); },
          onComplete() { resolve(); }
        });
      });
    }
    return { count: matches.length, scannedBytes, capped: matches.length >= SETTINGS.maxResults };
  } finally {
    scanning = false;
  }
}

function refine(mode, value) {
  if (scanning) throw new Error('Scan already running');
  if (!['exact', 'increased', 'decreased', 'unchanged'].includes(mode)) {
    throw new Error('Use exact, increased, decreased, or unchanged');
  }
  if (mode === 'exact') value = int32(value);
  matches = matches.filter(hit => {
    try {
      const now = hit.address.readS32();
      const keep = mode === 'exact' ? now === value :
        mode === 'increased' ? now > hit.value :
        mode === 'decreased' ? now < hit.value : now === hit.value;
      if (keep) hit.value = now;
      return keep;
    } catch (_) { return false; }
  });
  return { count: matches.length };
}

function results(limit) {
  const n = Math.max(0, Math.min(64, Number(limit) || 8));
  return { count: matches.length, first: matches.slice(0, n).map((hit, index) => ({
    index, address: hit.address.toString(), value: hit.value
  })) };
}
function writeInt(index, value) {
  if (scanning) throw new Error('Scan already running');
  const i = Number(index);
  value = int32(value);
  if (!Number.isInteger(i) || i < 0 || i >= matches.length) throw new Error('Result index out of range');
  matches[i].address.writeS32(value);
  matches[i].value = value;
  return { index: i, address: matches[i].address.toString(), value };
}

// An attached Frida host can call these RPC methods. A Gadget script can also
// receive { op, args, id } messages under the 'memory-command' channel.
rpc.exports = {
  scanint: scanInt,
  refine,
  results,
  writeint: writeInt,
  reset() { matches = []; return { count: 0 }; }
};
function receiveCommand() {
  recv('memory-command', async message => {
    const payload = message.payload || {};
    try {
      const operation = rpc.exports[payload.op];
      if (typeof operation !== 'function') throw new Error('Unknown operation');
      send({ type: 'memory-result', id: payload.id, result: await operation(...(payload.args || [])) });
    } catch (error) {
      send({ type: 'memory-result', id: payload.id, error: String(error) });
    }
    receiveCommand();
  });
}
receiveCommand();
installEntitlementHooks(0);
if (SETTINGS.startupScan) {
  setTimeout(() => scanInt(SETTINGS.startupScan.value, SETTINGS.startupScan.filter)
    .then(result => say('Startup scan: ' + JSON.stringify(result)))
    .catch(error => say('Startup scan failed: ' + error)), 2000);
}
