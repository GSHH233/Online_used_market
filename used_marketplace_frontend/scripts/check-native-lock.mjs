import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function checkNativeLock(lock) {
  const errors = []
  const packages = lock.packages ?? {}
  for (const owner of ['rolldown', 'lightningcss']) {
    const dependencies = packages[`node_modules/${owner}`]?.optionalDependencies
    if (!dependencies || Object.keys(dependencies).length === 0) {
      errors.push(`Missing native dependency declarations for ${owner}`)
      continue
    }
    for (const [name, version] of Object.entries(dependencies)) {
      const entry = packages[`node_modules/${name}`]
      if (!entry) {
        errors.push(`Missing cross-platform lock entry: ${name}@${version}`)
      } else if (entry.version !== version || !entry.resolved || !entry.integrity) {
        errors.push(`Invalid cross-platform lock entry: ${name}@${version}`)
      }
    }
  }
  return errors
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const lock = JSON.parse(readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'))
  const errors = checkNativeLock(lock)
  if (errors.length) {
    console.error(errors.join('\n'))
    process.exitCode = 1
  } else {
    console.log('Cross-platform native dependency lock: OK')
  }
}
