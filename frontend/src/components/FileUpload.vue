<script setup lang="ts">
import { ref, onBeforeUnmount, watch } from 'vue'
import { session } from '../auth/session'
import { ApiError } from '../api/client'
const props = defineProps<{ knowledgeBaseId: string }>()
const emit = defineEmits<{ uploaded: [] }>()
const file = ref<File | null>(null)
const busy = ref(false), percent = ref(0), message = ref(''), requestId = ref('')
let key = crypto.randomUUID(), pending: AbortController | undefined
function choose(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null; key = crypto.randomUUID(); message.value = ''; requestId.value = '' }
async function submit() {
  if (!file.value || busy.value) return
  if (file.value.size <= 0 || file.value.size > 20 * 1024 * 1024) { message.value = '请选择非空、最大20MiB的文件'; return }
  busy.value = true; percent.value = 0; message.value = ''; requestId.value = ''; pending = new AbortController()
  try {
    const result = await session.uploadDocument(props.knowledgeBaseId, file.value, key, value => { percent.value = value }, pending.signal)
    message.value = result.replayed ? '相同内容已存在，已复用已有文档' : '原文件已保存，尚未解析或建立索引'
    key = crypto.randomUUID(); emit('uploaded')
  } catch (error) {
    message.value = error instanceof ApiError ? error.message : '上传暂不可用'
    requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
    if (error instanceof ApiError && error.status && error.code !== 'UPLOAD_IN_PROGRESS') key = crypto.randomUUID()
  } finally { busy.value = false }
}
watch(() => props.knowledgeBaseId, () => { pending?.abort(); file.value = null; message.value = '' })
onBeforeUnmount(() => pending?.abort())
</script>
<template>
  <form
    class="upload-form"
    @submit.prevent="submit"
  >
    <h4>上传自己的资料</h4>
    <p>PDF、UTF-8 TXT、Markdown，最大20MiB。当前只保存原文件，不进行解析或问答。</p>
    <label for="document-file">选择文件</label>
    <input
      id="document-file"
      type="file"
      accept=".pdf,.txt,.md"
      :disabled="busy"
      @change="choose"
    >
    <button
      class="button"
      type="submit"
      :disabled="!file || busy"
    >
      {{ busy ? '正在上传…' : '上传文件' }}
    </button>
    <button
      v-if="busy"
      class="button"
      type="button"
      @click="pending?.abort()"
    >
      停止等待
    </button>
    <p
      v-if="busy"
      role="status"
    >
      传输进度 {{ percent }}%；传输结束后仍需服务端确认
    </p>
    <p
      v-if="message"
      role="status"
    >
      {{ message }}
    </p>
    <p v-if="requestId">
      请求编号：{{ requestId }}
    </p>
  </form>
</template>
<style scoped>
.upload-form { padding: 16px; margin-block: 16px; border: 1px solid #dce4d8; border-radius: 8px; }
input { max-width: 100%; margin: 8px; }
</style>
