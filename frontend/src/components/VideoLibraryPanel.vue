<template>
  <section class="card lib-card">
    <div class="lib-bar">
      <button class="lib-toggle" type="button" @click="open = !open">
        <span class="caret">{{ open ? '▾' : '▸' }}</span>
        视频库（{{ videos.length }}）
      </button>
      <span class="lib-scope">
        <template v-if="currentVideoId">
          范围：<b :title="currentName || currentVideoId">{{ currentName || currentVideoId }}</b>
          <span class="link" @click="emit('select', '')">切回全库</span>
        </template>
        <template v-else>范围：全库</template>
      </span>
    </div>

    <div v-show="open" class="lib-body">
      <div class="lib-head">
        <p class="muted hint">
          按上传时间倒序。点击文件名即把搜索范围限定到该视频并载入播放器，点击章节可直接跳到对应时间。
        </p>
        <button class="btn btn-ghost btn-sm" :disabled="loading" @click="load">
          {{ loading ? '读取中…' : '刷新' }}
        </button>
      </div>

    <div v-if="notice" class="warn-box">{{ notice }}</div>
    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-else-if="!videos.length" class="muted empty">
      {{ loading ? '读取中…' : '还没有视频，先在上方上传一个吧' }}
    </div>

    <div v-else class="video-list">
      <div
        v-for="v in videos"
        :key="v.videoId"
        class="video-item"
        :class="{active: v.videoId === currentVideoId}"
      >
        <div class="video-head" @click="select(v.videoId)">
          <span class="name" :title="v.fileName || v.videoId">{{ v.fileName || v.videoId }}</span>
          <span class="status" :class="'status-' + v.statusText">{{ statusLabel(v.statusText) }}</span>
          <span v-if="v.duration" class="meta">{{ formatTime(v.duration) }}</span>
          <span v-if="v.segmentCount" class="meta">{{ v.segmentCount }} 段</span>
          <span class="meta time-meta">{{ timeText(v.createdAt) }}</span>
          <span
            class="del"
            :class="{disabled: deleting === v.videoId}"
            :title="'删除该视频（连同对象存储文件、片段与 ASR 断点）'"
            @click.stop="remove(v)"
          >{{ deleting === v.videoId ? '删除中…' : '删除' }}</span>
        </div>
        <div v-if="v.errorMsg" class="err-line">{{ v.errorMsg }}</div>
        <div v-if="v.chapters && v.chapters.length" class="chapters">
          <span
            v-for="(c, i) in v.chapters"
            :key="i"
            class="chip"
            :title="'跳转到 ' + formatTime(c.startTime)"
            @click="emit('locate', {videoId: v.videoId, startTime: c.startTime})"
          >
            <span class="chip-time">{{ formatTime(c.startTime) }}</span>{{ c.title || `第 ${i + 1} 段` }}
          </span>
        </div>
      </div>
    </div>
    </div>
  </section>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {listVideos, deleteVideo, formatTime} from '../api.js'

defineProps({
  /** 当前搜索范围限定的视频 ID，空串表示全库 */
  currentVideoId: {type: String, default: ''},
  /** 当前搜索范围对应的文件名，供上方提示直接显示 */
  currentName: {type: String, default: ''}
})
const emit = defineEmits(['select', 'locate', 'deleted'])

const videos = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const deleting = ref('')
/** 面板默认收起，只留一行「视频库」按钮，把左栏版面让给上传与播放器 */
const open = ref(false)

/** 点选视频：限定搜索范围并收起列表，让播放器立刻可见 */
function select(videoId) {
  emit('select', videoId)
  open.value = false
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    videos.value = await listVideos()
  } catch (e) {
    error.value = '读取视频列表失败：' + e.message
  } finally {
    loading.value = false
  }
}

/** 删除视频：不可恢复，先确认；后端会拒绝删除「正在处理中」的任务 */
async function remove(v) {
  const name = v.fileName || v.videoId
  if (!confirm(`删除「${name}」？\n会同时清除对象存储里的源文件与可播放产物、数据库片段和 ASR 断点，不可恢复。`)) {
    return
  }
  deleting.value = v.videoId
  notice.value = ''
  error.value = ''
  try {
    const resp = await deleteVideo(v.videoId)
    // 数据库已删掉，但对象存储有残留：明确告知，避免误以为删干净了
    notice.value = resp.warnings?.length
      ? `已删除「${name}」，但有 ${resp.warnings.length} 个对象未清理干净：${resp.warnings.join('；')}`
      : ''
    await load()
    emit('deleted', v.videoId)
  } catch (e) {
    error.value = '删除失败：' + e.message
  } finally {
    deleting.value = ''
  }
}

function statusLabel(s) {
  return {done: '已完成', processing: '处理中', failed: '失败'}[s] || s
}

/** createdAt 由后端输出为 ISO 本地时间串（2026-09-25T10:39:47.123），截到分钟即可 */
function timeText(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

onMounted(load)
// 上传处理完成后由 App 调用刷新，使用户不必手动点「刷新」也能看到新视频
defineExpose({reload: load})
</script>

<style scoped>
/* 收起时整块只占一行 */
.lib-card {
  padding: 12px 20px;
}

.lib-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.lib-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: #eef2ff;
  color: #1f2937;
  font-size: 15px;
  font-weight: 600;
  padding: 7px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.lib-toggle:hover {
  background: #dbe4ff;
}

.caret {
  font-size: 11px;
  color: #6b7280;
}

.lib-scope {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 13px;
  color: #6b7280;
}

.lib-scope b {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1f2937;
  font-weight: 500;
}

.lib-body {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f1f2f4;
}

.lib-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.hint {
  margin: 0;
}

.btn-sm {
  padding: 5px 12px;
  font-size: 13px;
}

.link {
  color: #2563eb;
  cursor: pointer;
}

.empty {
  margin-top: 14px;
}

.video-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
}

.video-item {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 10px 12px;
}

.video-item.active {
  border-color: #2563eb;
  background: #f8faff;
}

.video-head {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  flex-wrap: wrap;
}

.name {
  font-weight: 500;
  color: #1f2937;
  word-break: break-all;
}

.video-head:hover .name {
  color: #2563eb;
}

.status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 6px;
  background: #eef2ff;
  color: #4338ca;
  white-space: nowrap;
}

.status-done {
  background: #ecfdf5;
  color: #047857;
}

.status-failed {
  background: #fef2f2;
  color: #b91c1c;
}

.status-processing {
  background: #fffbeb;
  color: #b45309;
}

.meta {
  font-size: 12px;
  color: #6b7280;
  white-space: nowrap;
}

.time-meta {
  margin-left: auto;
}

.del {
  font-size: 12px;
  color: #9ca3af;
  white-space: nowrap;
}

.del:hover {
  color: #dc2626;
}

.del.disabled {
  color: #d1d5db;
  cursor: default;
}

.chapters {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  font-size: 12px;
  background: #f3f4f6;
  color: #374151;
  border-radius: 6px;
  padding: 3px 8px;
  cursor: pointer;
}

.chip:hover {
  background: #e0e7ff;
  color: #4338ca;
}

.chip-time {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #6b7280;
  margin-right: 6px;
}

.err-line {
  margin-top: 6px;
  font-size: 12px;
  color: #b91c1c;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.error-box {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.warn-box {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fffbeb;
  color: #b45309;
  font-size: 13px;
  word-break: break-all;
}
</style>