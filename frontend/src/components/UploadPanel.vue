<template>
  <section class="card">
    <h2 class="title">1. 上传课程视频</h2>
    <p class="muted">
      支持 mp4/mkv/mov 等常见格式，可<b>一次多选任意数量</b>：B站 下载的 .m4s 画面轨与声音轨会被自动配对合并，
      其余文件各成一条独立视频。
      文件按 5MB 分片、3 路并行上传；<b>断网或刷新后重选同一批文件（保持原顺序）即可续传</b>（已收的分片不会重传）。
      上传后自动：提取音频 → ASR → 术语纠错 → 分段 → 向量化
    </p>

    <label
      class="dropzone"
      :class="{dragging}"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <input ref="fileInput" type="file" multiple accept="video/*,audio/*,.m4s" hidden @change="onPick"/>
      <div v-if="!files.length">拖拽视频到此处，或<span class="link">点击选择文件</span></div>
      <template v-else>
        <div v-for="(f, i) in files" :key="f.name + f.size" class="file-name">
          {{ i + 1 }}. 📹 {{ f.name }}（{{ humanSize(f.size) }}）
        </div>
        <div v-if="files.length > 1" class="muted merge-hint">
          已选 {{ files.length }} 个文件，将自动识别画面轨与声音轨配对，其余各成一条视频
        </div>
      </template>
    </label>

    <div v-if="overLimit" class="warn-box">
      一次最多 {{ MAX_FILES }} 个文件，已只保留前 {{ MAX_FILES }} 个。
    </div>
    <div v-else-if="files.length > SUGGESTED_MAX" class="warn-box">
      建议一次不超过 {{ SUGGESTED_MAX }} 个：处理流水线 2 路并发，批次越大后面的视频排得越久。
    </div>

    <div class="actions">
      <button class="btn" :disabled="!files.length || busy" @click="submit">
        {{ busy && uploadPct > 0 && uploadPct < 100 ? `上传中 ${uploadPct}%` : `排队上传（${files.length} 个）` }}
      </button>
      <button v-if="files.length" class="btn btn-ghost" :disabled="busy" @click="reset">重新选择</button>
    </div>

    <div v-if="stage" class="progress-block">
      <div class="progress-head">
        <span class="stage-label">{{ stageText(stage) }}</span>
        <span class="pct">{{ progress }}%</span>
      </div>
      <div class="bar-track">
        <div
          class="bar-fill"
          :class="{'bar-failed': stage === 'failed', 'bar-warn': stage === 'interrupted'}"
          :style="{width: progress + '%'}"
        ></div>
      </div>
      <div class="muted msg">{{ message }}</div>
      <div v-if="stage === 'failed'" class="error-box">{{ message }}</div>
      <div v-else-if="stage === 'interrupted'" class="warn-box">{{ message }}</div>
    </div>

    <div v-if="groups.length" class="progress-block">
      <div class="progress-head">
        <span class="stage-label">处理队列（{{ groups.length }} 组）</span>
        <span class="pct">{{ doneCount }}/{{ groups.length }}</span>
      </div>
      <div v-for="g in groups" :key="g.id" class="group-row">
        <div class="group-head">
          <span class="group-names">{{ g.names.join(' + ') }}</span>
          <span class="group-stage">{{ stageText(g.stage) }}</span>
        </div>
        <div class="bar-track">
          <div
            class="bar-fill"
            :class="{'bar-failed': g.stage === 'failed', 'bar-warn': g.stage === 'interrupted'}"
            :style="{width: g.progress + '%'}"
          ></div>
        </div>
        <div v-if="g.message" class="muted msg">{{ g.message }}</div>
      </div>
    </div>
  </section>
</template>

<script setup>
import {computed, ref} from 'vue'
import {uploadVideo, openProgressStream} from '../api.js'

const emit = defineEmits(['processed'])

const files = ref([])
const dragging = ref(false)
const fileInput = ref(null)
const busy = ref(false)
const uploadPct = ref(0)
const stage = ref('')
const progress = ref(0)
const message = ref('')
const overLimit = ref(false)
// 每个处理组一行：names 为该组文件（配对组是两个），taskId 为空表示该组在进流水线前就被判失败
const groups = ref([])

let es = null
// 上传阶段的进度流（独立于任务进度流：服务端的指纹/归一化/合并/写存储在返回 taskId 之前就发生了）
let uploadStream = null
// 每组的处理进度流
let groupStreams = []

const MAX_FILES = 20
const SUGGESTED_MAX = 10

const doneCount = computed(() => groups.value.filter((g) => g.stage === 'done').length)

function pick(list) {
  const picked = Array.from(list || [])
  overLimit.value = picked.length > MAX_FILES
  files.value = picked.slice(0, MAX_FILES)
  clearResult()
}

function onPick(e) {
  pick(e.target.files)
}

function onDrop(e) {
  dragging.value = false
  pick(e.dataTransfer.files)
}

/** 清掉上一轮的进度与分组展示（不动已选文件，断点续传要保留同一批） */
function clearResult() {
  closeGroupStreams()
  groups.value = []
  stage.value = ''
  progress.value = 0
  message.value = ''
  uploadPct.value = 0
}

function reset() {
  files.value = []
  overLimit.value = false
  if (fileInput.value) fileInput.value = ''
  closeUploadStream()
  closeGroupStreams()
  es?.close()
  clearResult()
  busy.value = false
}

async function submit() {
  if (!files.value.length) return
  busy.value = true
  // 先清掉上一轮的展示，再设置本轮的上传期状态（顺序颠倒会把刚设的值清掉）
  clearResult()
  stage.value = 'uploading'
  progress.value = 0
  message.value = '文件上传中…'
  // 上传阶段的进度走另一条 SSE：服务端把「指纹 / 归一化合并 / 写对象存储」推到客户端自带的这个 ID 上，
  // 否则这几步（大文件可达数十秒）在前端完全是静止的
  const uploadTaskId = 'up_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
  closeUploadStream()
  uploadStream = openProgressStream(uploadTaskId, {
    onEvent: (evt) => {
      stage.value = evt.stage
      progress.value = evt.progress
      message.value = evt.message || ''
    }
  }, {probe: false})
  try {
    const resp = await uploadVideo(files.value, onUploadProgress, uploadTaskId)
    closeUploadStream()
    startGroups(resp.items || [])
  } catch (e) {
    closeUploadStream()
    stage.value = 'failed'
    progress.value = 100
    message.value = e.message
    busy.value = false
  }
}

/**
 * complete 返回分组后：把每个未失败的组渲染成一行，并各开一条进度流。
 * 「排队中」= 该项尚未收到任何进度事件——这正是服务端线程池队列里的真实状态，不需要新接口。
 */
function startGroups(items) {
  if (!items.length) {
    stage.value = 'failed'
    message.value = '服务端没有返回任何处理分组'
    busy.value = false
    return
  }
  groups.value = items.map((it, i) => ({
    id: i,
    names: it.fileNames || [],
    taskId: it.taskId,
    stage: it.error ? 'failed' : 'queued',
    progress: it.error ? 100 : 0,
    message: it.error || (it.duplicated ? '命中去重，任务已存在' : '')
  }))
  stage.value = ''
  const pending = groups.value.filter((g) => g.taskId)
  if (!pending.length) {
    finishAll()
    return
  }
  pendingCount = pending.length
  settledCount = 0
  for (const g of pending) {
    listenGroup(g)
  }
}

let pendingCount = 0
let settledCount = 0

function listenGroup(g) {
  const stream = openProgressStream(g.taskId, {
    onEvent: (evt) => {
      // 收到进度即代表它已从队列被取走："排队中" 随之消失，由真实阶段文案接替
      g.stage = evt.stage
      g.progress = evt.progress
      g.message = evt.message || ''
    },
    onDone: () => {
      g.stage = 'done'
      g.progress = 100
      g.message = '处理完成，可在视频库中检索'
      settle()
    },
    onFailed: (evt) => {
      // 可能来自进度事件，也可能来自静默看门狗的反查结果（此时无阶段事件）
      g.stage = 'failed'
      g.progress = 100
      g.message = evt?.message || '处理失败'
      settle()
    },
    onInterrupted: () => {
      // 后端进程消失导致任务僵死：不自动重传（大文件重传代价高），明确告知如何续跑
      g.stage = 'interrupted'
      g.message = `任务已中断：后端在处理过程中重启或被终止，进度停在第 ${g.progress}%。`
        + '用同一套文件再传一次即可复用断点续跑，已识别过的片段不会重跑。'
      settle()
    }
  })
  groupStreams.push(stream)
}

function settle() {
  settledCount += 1
  if (settledCount >= pendingCount) {
    finishAll()
  }
}

/**
 * 全部组都有了终态：解锁界面，并把已完成的 videoId 列表交给 App（只刷一次列表）。
 * 一组都没成功时也发空数组：失败的记录也进了视频库，列表需要刷新才能看到。
 */
function finishAll() {
  busy.value = false
  emit('processed', groups.value.filter((g) => g.stage === 'done' && g.taskId).map((g) => g.taskId))
}

/**
 * 分片上传的进度回调：pct 由「服务端已确认收下的分片」算出，info 带断点续传信息。
 * 断点续传命中时明确提示复用了多少片，让「不重传」这件事在界面上可见。
 */
function onUploadProgress(pct, info) {
  uploadPct.value = pct
  if (!info) return
  if (info.resumed) {
    message.value = `断点续传：已复用服务端已收的 ${info.skippedParts}/${info.totalParts} 片，只补传缺失分片…`
  } else if (pct < 100) {
    message.value = `分片上传中…（共 ${info.totalParts} 片，每片 5MB，3 路并行）`
  }
}

function closeUploadStream() {
  uploadStream?.close()
  uploadStream = null
}

function closeGroupStreams() {
  groupStreams.forEach((s) => s?.close())
  groupStreams = []
}

function humanSize(n) {
  if (n > 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

function stageText(s) {
  return {
    queued: '排队中 ⏳',
    uploading: '上传与媒体预处理',
    uploaded: '上传完成',
    audio_extract: '提取音频',
    vad: 'VAD 静音检测',
    asr: 'ASR 语音识别',
    correct: '术语纠错',
    segment: '话题分段',
    embed: '文本向量化',
    chapter: '生成章节标题',
    done: '处理完成 ✅',
    failed: '处理失败 ❌',
    interrupted: '任务已中断 ⚠️'
  }[s] || s
}
</script>

<style scoped>
.title {
  margin: 0 0 4px;
  font-size: 17px;
}

.dropzone {
  display: block;
  margin-top: 14px;
  border: 2px dashed #cbd5e1;
  border-radius: 10px;
  padding: 34px 16px;
  text-align: center;
  color: #64748b;
  font-size: 14px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.dropzone:hover,
.dropzone.dragging {
  border-color: #2563eb;
  background: #f8faff;
}

.link {
  color: #2563eb;
}

.file-name {
  font-weight: 500;
  color: #1f2937;
  word-break: break-all;
}

.merge-hint {
  margin-top: 6px;
  font-size: 12px;
}

.actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.progress-block {
  margin-top: 18px;
}

.progress-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 14px;
}

.stage-label {
  font-weight: 500;
}

.pct {
  color: #2563eb;
  font-variant-numeric: tabular-nums;
}

.msg {
  margin-top: 6px;
  min-height: 18px;
}

.group-row {
  margin-top: 12px;
}

.group-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
  font-size: 13px;
}

.group-names {
  font-weight: 500;
  color: #1f2937;
  word-break: break-all;
}

.group-stage {
  flex: none;
  color: #64748b;
}

.error-box {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.warn-box {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fffbeb;
  color: #b45309;
  font-size: 13px;
}
</style>