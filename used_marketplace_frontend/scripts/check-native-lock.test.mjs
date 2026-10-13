import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { checkNativeLock } from './check-native-lock.mjs'

function readLock() {
  return JSON.parse(readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'))
}

test('all declared native variants are locked', () => {
  assert.deepEqual(checkNativeLock(readLock()), [])
})

for (const name of ['@rolldown/binding-linux-x64-gnu', 'lightningcss-linux-x64-gnu']) {
  test(`rejects a lock missing ${name}`, () => {
    const lock = readLock()
    delete lock.packages[`node_modules/${name}`]
    assert.ok(checkNativeLock(lock).some(error => error.includes(name)))
  })
}

test('rejects mismatched native versions and incomplete integrity metadata', () => {
  const lock = readLock()
  lock.packages['node_modules/@rolldown/binding-linux-x64-gnu'].version = '0.0.0'
  delete lock.packages['node_modules/lightningcss-linux-x64-gnu'].integrity
  assert.equal(checkNativeLock(lock).length, 2)
})
