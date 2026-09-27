<template>
  <section class="card">
    <h2 class="title">1. 上传课程视频</h2>
    <p class="muted">
      支持 mp4/mkv/mov 等常见格式；B站 下载的 .m4s 请把画面轨与声音轨<b>两个文件一起选中</b>，平台会自动合并成带画面与声音的完整视频。
      文件按 5MB 分片、3 路并行上传；<b>断网或刷新后重选同一批文件即可续传</b>（已收的分片不会重传）。
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
        <div v-for="f in files" :key="f.name + f.size" class="file-name">
          📹 {{ f.name }}（{{ humanSize(f.size) }}）
        </div>
        <div v-if="files.length === 2" class="muted merge-hint">将自动识别画面轨与声音轨并合并</div>
      </template>
    </label>

    <div class="actions">
      <button class="btn" :disabled="!files.length || busy" @click="submit">
        {{ uploadPct > 0 && uploadPct < 100 ? `上传中 ${uploadPct}%` : '上传并处理' }}
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
  </section>
</template>

<script setup>
import {ref} from 'vue'
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

let es = null
// 上传阶段的进度流（独立于任务进度流：服务端的指纹/归一化/合并/写存储在返回 taskId 之前就发生了）
let uploadStream = null

const MAX_FILES = 2

function pick(list) {
  files.value = Array.from(list || []).slice(0, MAX_FILES)
}

function onPick(e) {
  pick(e.target.files)
}

function onDrop(e) {
  dragging.value = false
  pick(e.dataTransfer.files)
}

function reset() {
  files.value = []
  if (fileInput.value) fileInput.value = ''
  closeUploadStream()
  es?.close()
  stage.value = ''
  progress.value = 0
  message.value = ''
  uploadPct.value = 0
}

async function submit() {
  if (!files.value.length) return
  busy.value = true
  uploadPct.value = 0
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
    message.value = resp.duplicated ? '命中去重，任务已存在' : '任务已提交，等待处理'
    listen(resp.taskId)
  } catch (e) {
    closeUploadStream()
    stage.value = 'failed'
    progress.value = 100
    message.value = e.message
    busy.value = false
  }
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

function listen(taskId) {
  es?.close()
  es = openProgressStream(taskId, {
    onEvent: (evt) => {
      stage.value = evt.stage
      progress.value = evt.progress
      message.value = evt.message || ''
    },
    onDone: () => {
      busy.value = false
      emit('processed', taskId)
    },
    onFailed: (evt) => {
      // 可能来自进度事件，也可能来自静默看门狗的反查结果（此时无阶段事件）
      busy.value = false
      stage.value = 'failed'
      progress.value = 100
      message.value = evt?.message || '处理失败'
    },
    onInterrupted: () => {
      // 后端进程消失导致任务僵死：不自动重传（大文件重传代价高），明确告知如何续跑
      busy.value = false
      stage.value = 'interrupted'
      message.value = `任务已中断：后端在处理过程中重启或被终止，进度停在第 ${progress.value}%。`
        + '用同一套文件再传一次即可复用断点续跑，已识别过的片段不会重跑。'
    }
  })
}

function humanSize(n) {
  if (n > 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

function stageText(s) {
  return {
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
