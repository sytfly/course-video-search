<template>
  <section class="card">
    <h2 class="title">2. 自然语言搜索</h2>
    <p class="muted">用一句话描述知识点，按语义相关性返回最相关的 5 个视频片段，点击即可跳转播放；相关性过低的片段会被过滤，相关性不足时会标出「低置信」</p>

    <div class="search-row">
      <input
        v-model="query"
        class="input"
        placeholder='例如：Redis 持久化 RDB 和 AOF 的区别'
        @keyup.enter="doSearch"
      />
      <button class="btn" :disabled="!query.trim() || loading" @click="doSearch">
        {{ loading ? '搜索中…' : '搜索' }}
      </button>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>

    <div v-if="results.length" class="results">
      <div v-if="allLowConfidence" class="low-hint">
        没有找到高置信片段，以下候选相关性偏低，仅供参考
      </div>
      <div
        v-for="(r, i) in results"
        :key="r.videoId + '-' + r.segmentIndex"
        class="result-item"
        :class="{active: activeKey === r.videoId + '-' + r.segmentIndex}"
        @click="locate(r)"
      >
        <div class="result-head">
          <span class="rank">#{{ i + 1 }}</span>
          <span v-if="r.chapterTitle" class="chapter">{{ r.chapterTitle }}</span>
          <span class="time">{{ formatTime(r.startTime) }} - {{ formatTime(r.endTime) }}</span>
          <span v-if="r.lowConfidence" class="low-badge">低置信</span>
          <span class="score" :title="'相关性 ' + r.score.toFixed(3)">相关性 {{ Math.round(r.score * 100) }}%</span>
        </div>
        <div class="text">{{ r.text }}</div>
        <div class="source muted">来源：{{ r.videoName || r.videoId }}</div>
      </div>
    </div>
    <div v-else-if="searched && !loading" class="muted empty">没有搜到相关片段，换个说法试试</div>
  </section>
</template>

<script setup>
import {ref, computed} from 'vue'
import {search, formatTime} from '../api.js'

const props = defineProps({
  videoId: {type: String, default: ''}
})
const emit = defineEmits(['locate'])

const query = ref('')
const results = ref([])
const loading = ref(false)
const searched = ref(false)
const error = ref('')
const activeKey = ref('')

// 全部候选都是低置信：整条结果只能当参考，顶部给一条明确提示
const allLowConfidence = computed(() => results.value.length > 0 && results.value.every(r => r.lowConfidence))

async function doSearch() {
  const q = query.value.trim()
  if (!q) return
  loading.value = true
  error.value = ''
  try {
    results.value = await search(q, 5, props.videoId)
    searched.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function locate(r) {
  activeKey.value = r.videoId + '-' + r.segmentIndex
  emit('locate', r)
}
</script>

<style scoped>
.title {
  margin: 0 0 4px;
  font-size: 17px;
}

.search-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.results {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.result-item {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.result-item:hover {
  border-color: #93c5fd;
  box-shadow: 0 2px 10px rgba(37, 99, 235, 0.08);
}

.result-item.active {
  border-color: #2563eb;
  background: #f8faff;
}

.result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.rank {
  font-weight: 600;
  color: #2563eb;
}

.chapter {
  font-weight: 500;
}

.time {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: #f3f4f6;
  padding: 2px 8px;
  border-radius: 6px;
}

.score {
  margin-left: auto;
  font-size: 12px;
  color: #6b7280;
}

.text {
  margin-top: 8px;
  font-size: 14px;
  line-height: 1.6;
  color: #374151;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.source {
  margin-top: 6px;
}

.empty {
  margin-top: 16px;
}

.low-hint {
  padding: 8px 10px;
  border-radius: 8px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
  font-size: 13px;
}

.low-badge {
  font-size: 12px;
  color: #92400e;
  background: #fef3c7;
  border-radius: 6px;
  padding: 2px 8px;
}

.error-box {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}
</style>
