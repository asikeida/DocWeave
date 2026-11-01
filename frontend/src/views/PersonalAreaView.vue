<script setup lang="ts">
import KnowledgeLibrary from '../components/KnowledgeLibrary.vue'
import SamplePreview from '../components/SamplePreview.vue'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { session } from '../auth/session'
import type { PersonalArea } from '../api/auth'
import { ApiError } from '../api/client'
const area = ref<PersonalArea | null>(null)
const busy = ref(false)
const message = ref('')
const requestId = ref('')
const needsInitialization = ref(false)
let pending: AbortController | undefined
async function load(initialize = false) {
  if (busy.value) return
  busy.value = true; message.value = ''; requestId.value = ''; area.value = null
  pending = new AbortController()
  try {
    area.value = await session.personalArea(initialize, pending.signal)
    needsInitialization.value = false
  } catch (error) {
    if (error instanceof ApiError && error.code === 'CANCELLED') return
    needsInitialization.value = error instanceof ApiError && error.code === 'PERSONAL_AREA_UNAVAILABLE'
    message.value = error instanceof ApiError ? error.message : '个人区域暂不可用'
    requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
  } finally { busy.value = false }
}
watch(() => session.user.value?.id, () => { area.value = null; pending?.abort() })
onMounted(() => { void load() })
onBeforeUnmount(() => pending?.abort())
</script>
<template>
  <section class="page-content">
    <h1>我的资料</h1>
    <p
      v-if="busy"
      role="status"
    >
      正在加载个人区域…
    </p>
    <div
      v-if="message"
      role="alert"
    >
      <p>{{ message }}</p><p v-if="requestId">
        请求编号：{{ requestId }}
      </p>
    </div>
    <button
      v-if="needsInitialization"
      class="button"
      :disabled="busy"
      @click="load(true)"
    >
      初始化个人区域
    </button>
    <button
      v-else
      class="button"
      :disabled="busy"
      @click="load()"
    >
      重新加载
    </button>
    <div v-if="area">
      <h2>{{ area.name }}</h2>
      <p>个人区域已建立。可查看入库文档与只读模板，目前不能上传文件或问答。</p>
      <KnowledgeLibrary />
      <SamplePreview />
      <p>准备完成后，可尝试“试用期员工能远程办公吗？”以及“出差三天需要哪些申请和报销材料？”</p>
    </div>
  </section>
</template>
