import { defineStore } from 'pinia'
import { ref } from 'vue'
import { aiApi } from '../api/modules/ai'

export const useAgentStore = defineStore('agent', () => {
  const agentTrace = ref<any[]>([])
  const observations = ref<any[]>([])
  const graph = ref<any>(null)
  const selectedNode = ref<any>(null)
  const rawPayload = ref<any>(null)

  async function runReact(payload: any) {
    const data = await aiApi.reactAgent(payload) as any
    rawPayload.value = data
    agentTrace.value = data?.agentTrace || []
    observations.value = data?.observations || []
    return data
  }

  async function runGraph(payload: any) {
    const data = await aiApi.graphAgent(payload) as any
    rawPayload.value = data
    graph.value = data?.graph || null
    agentTrace.value = data?.agentTrace || []
    observations.value = data?.observations || []
    return data
  }

  return { agentTrace, observations, graph, selectedNode, rawPayload, runReact, runGraph }
})
