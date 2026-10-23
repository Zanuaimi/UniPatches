'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const memory = Buffer.alloc(32);
memory.writeInt32LE(42, 4);
memory.writeInt32LE(42, 16);
const pointer = offset => ({
  offset,
  readS32() { return memory.readInt32LE(offset); },
  writeS32(value) { memory.writeInt32LE(value, offset); },
  toString() { return '0x' + offset.toString(16); }
});
const context = {
  Java: { available: false },
  Process: { enumerateRanges: () => [{ base: pointer(0), size: memory.length }] },
  Memory: {
    scan(base, size, pattern, callbacks) {
      const bytes = Buffer.from(pattern.split(' ').map(x => parseInt(x, 16)));
      for (let i = base.offset; i <= base.offset + size - bytes.length; i++) {
        if (memory.subarray(i, i + bytes.length).equals(bytes) &&
            callbacks.onMatch(pointer(i)) === 'stop') break;
      }
      callbacks.onComplete();
    }
  },
  rpc: { exports: {} },
  recv() {},
  send() {},
  console: { log() {} },
  setTimeout() {}
};
vm.runInNewContext(fs.readFileSync(__dirname + '/universal-inapp-memory.js', 'utf8'), context);

const premium = { implementation: null };
const connected = { implementation: null };
const method = name => ({
  getName: () => name,
  getReturnType: () => ({ getName: () => 'boolean' }),
  getParameterTypes: () => []
});
const appClass = {
  class: { getDeclaredMethods: () => [method('isPremium'), method('isConnected')] },
  isPremium: { overload: () => premium },
  isConnected: { overload: () => connected }
};
const javaContext = {
  ...context,
  rpc: { exports: {} },
  Java: {
    available: true,
    perform(callback) { callback(); },
    enumerateLoadedClassesSync: () => ['com.example.game.Player', 'com.android.billingclient.api.BillingClient'],
    use(name) {
      if (name === 'android.app.ActivityThread') return {
        currentApplication: () => ({ getPackageName: () => 'com.example.game' })
      };
      if (name === 'com.example.game.Player') return appClass;
      throw new Error('Outside app package');
    }
  }
};
vm.runInNewContext(fs.readFileSync(__dirname + '/universal-inapp-memory.js', 'utf8'), javaContext);
assert.equal(premium.implementation(), true);
assert.equal(connected.implementation, null);

(async () => {
  const api = context.rpc.exports;
  assert.equal((await api.scanint(42)).count, 2);
  assert.equal(api.results(2).first[0].address, '0x4');
  memory.writeInt32LE(43, 4);
  assert.equal(api.refine('increased').count, 1);
  assert.equal(api.writeint(0, 99).value, 99);
  assert.equal(memory.readInt32LE(4), 99);
  assert.equal(api.results(1).first[0].value, 99);
  assert.throws(() => api.writeint(1, 10), /out of range/);
  assert.equal(api.reset().count, 0);
  await assert.rejects(api.scanint('not an int'), /signed int32/);
  console.log('Frida memory scan/refine/write checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
