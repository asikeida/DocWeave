<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { session } from '../auth/session'
import { ApiError } from '../api/client'
import type { KnowledgeBase, DocumentSummary, DocumentDetail } from '../api/knowledge'
const libraries = ref<KnowledgeBase[]>([])
const docs = ref<DocumentSummary[]>([])
const detail = ref<DocumentDetail | null>(null)
const active = ref('')
const busy = ref(false)
const message = ref('')
const requestId = ref('')
let pending: AbortController | undefined
let generation = 0
async function execute(action: (signal: AbortSignal) => Promise<void>) {
  pending?.abort(); pending = new AbortController(); const current = ++generation
  busy.value = true; message.value = ''; requestId.value = ''
  try { await action(pending.signal) }
  catch (error) {
    if (current !== generation || (error instanceof ApiError && error.code === 'CANCELLED')) return
    message.value = error instanceof ApiError ? error.message : '知识库暂不可用'
    requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
  } finally { if (current === generation) busy.value = false }
}
function load(initialize = false) {
  docs.value = []; detail.value = null; active.value = ''; libraries.value = []
  return execute(async signal => { libraries.value = await session.knowledgeBases(initialize, signal) })
}
function open(kb: KnowledgeBase) {
  active.value = kb.name; docs.value = []; detail.value = null
  return execute(async signal => { docs.value = await session.knowledgeDocuments(kb.id, signal) })
}
function read(doc: DocumentSummary) {
  detail.value = null
  return execute(async signal => { detail.value = await session.knowledgeDocument(doc.id, signal) })
}
onMounted(() => { void load() })
onBeforeUnmount(() => { generation++; pending?.abort() })
</script>
<template>
  <section aria-labelledby="knowledge-title">
    <h3 id="knowledge-title">
      我的知识库
    </h3>
    <p>文档入库后仍未解析或建立索引，当前不能问答。</p>
    <button
      class="button"
      :disabled="busy"
      @click="load()"
    >
      刷新知识库
    </button>
    <button
      class="button"
      :disabled="busy"
      @click="load(true)"
    >
      初始化或重试示例入库
    </button>
    <p
      v-if="busy"
      role="status"
    >
      正在处理知识库…
    </p>
    <div
      v-if="message"
      role="alert"
    >
      <p>{{ message }}</p><p v-if="requestId">
        请求编号：{{ requestId }}
      </p>
    </div>
    <p v-if="!busy && !message && !libraries.length">
      示例正在等待初始化；可以手动触发或稍后刷新。
    </p>
    <ul>
      <li
        v-for="kb in libraries"
        :key="kb.id"
      >
        <button
          class="button"
          :disabled="busy"
          @click="open(kb)"
        >
          {{ kb.name }} · {{ kb.documentCount }} 份文档 · 已入库未索引
        </button>
      </li>
    </ul>
    <h4 v-if="active">
      {{ active }}的文档
    </h4>
    <ul>
      <li
        v-for="doc in docs"
        :key="doc.id"
      >
        <button
          class="button"
          :disabled="busy"
          @click="read(doc)"
        >
          {{ doc.title }} · 查看已入库正文
        </button>
      </li>
    </ul>
    <article
      v-if="detail"
      aria-label="已入库文档正文"
    >
      <h4>{{ detail.document.title }}</h4><p>示例来源版本 {{ detail.document.seedVersion }} · 未索引</p><pre class="document-text">{{ detail.content }}</pre>
    </article>
  </section>
</template>
<style scoped>
.document-text { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; line-height: 1.8; padding: 20px; border: 1px solid #dce4d8; border-radius: 8px; }
li { margin-block: 12px; }
</style>
