import { defineStore } from 'pinia'
import { ref } from 'vue'
import { aiApi } from '../api/modules/ai'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const docs = ref<any[]>([])
  const searchHits = ref<any[]>([])
  const reindexTasks = ref<any[]>([])

  async function createDoc(payload: any) {
    const doc = await aiApi.createKnowledge(payload)
    docs.value.unshift(doc)
    return doc
  }

  async function search(payload: any) {
    const data = await aiApi.searchKnowledge(payload) as any
    searchHits.value = data?.hits || []
    return data
  }

  async function reindex(docId: number) {
    const task = await aiApi.reindexKnowledge(docId)
    reindexTasks.value.unshift(task)
    return task
  }

  return { docs, searchHits, reindexTasks, createDoc, search, reindex }
})
