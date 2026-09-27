<template>
  <div class="page">
    <header class="header">
      <div class="header-inner">
        <h1>课程视频语义检索平台</h1>
        <span class="header-tag">ASR · VAD 分段 · bge-m3 · pgvector</span>
      </div>
    </header>

    <main class="layout">
      <div class="col col-left">
        <UploadPanel @processed="onProcessed"/>

        <VideoLibraryPanel
          ref="libraryRef"
          :current-video-id="currentVideoId"
          :current-name="currentName"
          @select="onSelectVideo"
          @locate="onLocate"
          @deleted="onDeleted"
        />

        <section v-if="playerSrc" class="card player-card">
          <h2 class="title">3. 视频定位播放</h2>
          <p v-if="currentName" class="muted">正在播放：{{ currentName }}</p>
          <video
            ref="playerRef"
            class="player"
            :src="playerSrc"
            controls
            preload="metadata"
            @loadedmetadata="onLoadedMeta"
          ></video>
          <div v-if="pendingSeek !== null" class="muted seek-hint">
            即将跳转到 {{ formatTime(pendingSeek) }}
          </div>
        </section>
      </div>

      <div class="col col-right">
        <SearchPanel :video-id="currentVideoId" @locate="onLocate"/>
      </div>
    </main>
  </div>
</template>

<script setup>
import {ref} from 'vue'
import UploadPanel from './components/UploadPanel.vue'
import VideoLibraryPanel from './components/VideoLibraryPanel.vue'
import SearchPanel from './components/SearchPanel.vue'
import {getVideoInfo, playUrl, formatTime} from './api.js'

const currentVideoId = ref('')
const currentName = ref('')
const playerSrc = ref('')
const playerRef = ref(null)
const pendingSeek = ref(null)
const libraryRef = ref(null)

async function onProcessed(videoId) {
  // 新视频刚处理完，让视频库立刻能看到它（不必手动刷新）
  libraryRef.value?.reload()
  await openVideo(videoId)
}

/** 视频库里点选某个视频：限定搜索范围并载入播放器；选空串表示切回全库，播放器保持现状 */
function onSelectVideo(videoId) {
  if (videoId === currentVideoId.value) return
  currentVideoId.value = videoId
  if (videoId) openVideo(videoId)
}

async function openVideo(videoId, seekTime = null) {
  currentVideoId.value = videoId
  try {
    const info = await getVideoInfo(videoId)
    currentName.value = info.fileName
  } catch (e) {
    // 列表里可能混有失败的视频：取不到文件名时退回显示 ID，不影响播放尝试
    currentName.value = ''
  }
  pendingSeek.value = seekTime
  // 加时间戳避免浏览器复用旧 src 不触发 loadedmetadata
  playerSrc.value = playUrl(videoId) + '?t=' + Date.now()
}

async function onLocate(result) {
  if (result.videoId !== currentVideoId.value) {
    await openVideo(result.videoId, result.startTime)
  } else {
    seekTo(result.startTime)
  }
}

/** 视频被删除：若删的正是当前选中的那个，收起播放器并回到全库搜索 */
function onDeleted(videoId) {
  if (videoId !== currentVideoId.value) return
  currentVideoId.value = ''
  currentName.value = ''
  playerSrc.value = ''
  pendingSeek.value = null
}

function onLoadedMeta() {
  if (pendingSeek.value !== null) {
    seekTo(pendingSeek.value)
    pendingSeek.value = null
  }
}

function seekTo(sec) {
  const v = playerRef.value
  if (v) {
    v.currentTime = sec
    v.play().catch(() => {
      // 自动播放被浏览器拒绝时不报错，用户手动点播放即可
    })
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
}

.header {
  background: linear-gradient(135deg, #1e3a8a, #2563eb);
  color: #fff;
}

.header-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 18px 24px;
  display: flex;
  align-items: center;
  gap: 14px;
}

.header h1 {
  font-size: 20px;
  margin: 0;
}

.header-tag {
  font-size: 12px;
  background: rgba(255, 255, 255, 0.18);
  padding: 3px 10px;
  border-radius: 99px;
}

.layout {
  max-width: 1280px;
  margin: 24px auto;
  padding: 0 24px;
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: 20px;
  align-items: start;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

.title {
  margin: 0 0 6px;
  font-size: 17px;
}

.player-card {
  padding-bottom: 16px;
}

.player {
  width: 100%;
  border-radius: 10px;
  background: #000;
  margin-top: 8px;
  max-height: 360px;
}

.seek-hint {
  margin-top: 8px;
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
