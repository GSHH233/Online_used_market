import { defineStore } from 'pinia'
import { ref } from 'vue'
import { aiApi } from '../api/modules/ai'

export const useMemoryStore = defineStore('memory', () => {
  const l2Hits = ref<any[]>([])
  const profile = ref<any>(null)
  const memoryAssemblyDebug = ref<any>(null)

  async function queryL2(payload: any) {
    const data = await aiApi.l2Query(new URLSearchParams(payload))
    l2Hits.value = (data as any)?.hits || data || []
    return data
  }

  async function loadProfile(payload: any) {
    const data = await aiApi.l2Profile(new URLSearchParams(payload))
    profile.value = data
    return data
  }

  async function extractGraph(payload: any) {
    const data = await aiApi.graphExtract(payload)
    memoryAssemblyDebug.value = data
    return data
  }

  return { l2Hits, profile, memoryAssemblyDebug, queryL2, loadProfile, extractGraph }
})
