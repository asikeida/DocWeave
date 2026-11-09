<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { session } from '../auth/session'
import { ApiError } from '../api/client'
import type { SampleSummary, SamplePreview } from '../api/samples'
const samples = ref<SampleSummary[]>([])
const preview = ref<SamplePreview | null>(null)
const message = ref('')
const requestId = ref('')
const loading = ref(false)
let pending: AbortController | undefined
let generation = 0
function report(error: unknown) {
  if (error instanceof ApiError && error.code === 'CANCELLED') return
  message.value = error instanceof ApiError ? error.message : '示例加载失败，请重试'
  requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
}
async function load() {
  pending?.abort(); pending = new AbortController(); const current = ++generation
  message.value = ''; requestId.value = ''; loading.value = true; preview.value = null
  try { samples.value = await session.samples(pending.signal) }
  catch (error) { report(error) }
  finally { if (current === generation) loading.value = false }
}
async function open(id: string) {
  pending?.abort(); pending = new AbortController(); const current = ++generation
  message.value = ''; requestId.value = ''; loading.value = true; preview.value = null
  try { const result = await session.samplePreview(id, pending.signal); if (current === generation) preview.value = result }
  catch (error) { if (current === generation) report(error) }
  finally { if (current === generation) loading.value = false }
}
onMounted(() => { void load() })
onBeforeUnmount(() => { generation++; pending?.abort() })
</script>
<template>
  <section aria-labelledby="sample-title">
    <h3 id="sample-title">
      虚构示例原文（只读模板）
    </h3>
    <p>可先阅读资料和推荐问题。模板尚未导入个人知识库或建立索引，目前不能问答。</p>
    <p
      v-if="loading"
      role="status"
    >
      正在加载示例…
    </p>
    <div
      v-if="message"
      role="alert"
    >
      <p>{{ message }}</p><p v-if="requestId">
        请求编号：{{ requestId }}
      </p><button
        class="button"
        @click="load"
      >
        重试
      </button>
    </div>
    <ul>
      <li
        v-for="sample in samples"
        :key="sample.id"
      >
        <button
          class="button"
          :disabled="loading"
          @click="open(sample.id)"
        >
          {{ sample.title }} · 阅读原文
        </button>
        <ul>
          <li
            v-for="question in sample.suggestedQuestions"
            :key="question"
          >
            推荐问题：{{ question }}（问答待接入）
          </li>
        </ul>
      </li>
    </ul>
    <article
      v-if="preview"
      aria-label="示例原文"
    >
      <h4>{{ preview.sample.title }}</h4>
      <p>版本 {{ preview.sample.version }} · 只读预览</p>
      <pre class="sample-content">{{ preview.content }}</pre>
    </article>
  </section>
</template>
<style scoped>
.sample-content { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; line-height: 1.8; padding: 20px; border: 1px solid #dce4d8; border-radius: 8px; }
li { margin-block: 12px; }
</style>
