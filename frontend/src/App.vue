<template>
  <div v-if="!user" class="auth-page">
    <form class="auth-card" @submit.prevent="submitAuth">
      <h1>课程视频语义检索平台</h1>
      <p class="muted">登录后只会看到、搜到、播放和删除自己上传的视频</p>
      <label>
        用户名
        <input v-model.trim="authForm.username" autocomplete="username"
               placeholder="3~32 位字母 / 数字 / 下划线 / 点 / 中划线"/>
      </label>
      <label>
        密码
        <input v-model="authForm.password" type="password" :autocomplete="isLogin ? 'current-password' : 'new-password'"
               placeholder="至少 6 位"/>
      </label>
      <label class="auth-remember">
        <input v-model="rememberMe" type="checkbox"/>
        记住账号密码（下次自动填好）
      </label>
      <p v-if="authError" class="auth-error">{{ authError }}</p>
      <button class="auth-submit" type="submit" :disabled="authBusy">
        {{ authBusy ? '请稍候…' : (isLogin ? '登录' : '注册') }}
      </button>
      <button class="auth-switch" type="button" @click="switchMode">
        {{ isLogin ? '没有账号？注册一个（自动创建独立空间）' : '已有账号？去登录' }}
      </button>
    </form>
  </div>

  <div v-else class="page">
    <header class="header">
      <div class="header-inner">
        <h1>课程视频语义检索平台</h1>
        <span class="header-tag">ASR · VAD 分段 · bge-m3 · pgvector</span>
        <span class="header-user">
          {{ user.username }}
          <button type="button" @click="signOut">退出</button>
        </span>
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
import {computed, onMounted, onUnmounted, reactive, ref, watch} from 'vue'
import UploadPanel from './components/UploadPanel.vue'
import VideoLibraryPanel from './components/VideoLibraryPanel.vue'
import SearchPanel from './components/SearchPanel.vue'
import {fetchMe, formatTime, getToken, getVideoInfo, login, logout, register, setToken} from './api.js'

// 「记住账号密码」：勾选后把凭据存在 localStorage，下次打开登录页自动填好。
// 说明：localStorage 里的密码是明文的，只能防「懒得再输一遍」，不能防本机被翻看；
// 浏览器自带的密码管理器（输入框上的 autocomplete 已标注）是更安全的替代品。
const REMEMBER_KEY = 'vsearch.remember'
const CREDENTIAL_KEY = 'vsearch.credentials'

/** 当前登录账号（null = 未登录，此时整页只显示登录/注册表单） */
const user = ref(null)
const authMode = ref('login')
const authForm = reactive({username: '', password: ''})
const rememberMe = ref(localStorage.getItem(REMEMBER_KEY) !== '0')
const authBusy = ref(false)
const authError = ref('')
const isLogin = computed(() => authMode.value === 'login')

function readRemembered() {
  if (localStorage.getItem(REMEMBER_KEY) === '0') return null
  try {
    return JSON.parse(localStorage.getItem(CREDENTIAL_KEY) || 'null')
  } catch (e) {
    return null
  }
}

function saveRemembered() {
  localStorage.setItem(REMEMBER_KEY, '1')
  localStorage.setItem(CREDENTIAL_KEY, JSON.stringify({
    username: authForm.username,
    password: authForm.password
  }))
}

function clearRemembered() {
  localStorage.setItem(REMEMBER_KEY, '0')
  localStorage.removeItem(CREDENTIAL_KEY)
}

/** 把记住的凭据填回表单（首次加载、退出登录、令牌失效后回到登录页时都要填） */
function applyRemembered() {
  const saved = readRemembered()
  if (saved) {
    authForm.username = saved.username || ''
    authForm.password = saved.password || ''
  }
}

watch(rememberMe, (on) => {
  if (!on) clearRemembered()
})

const currentVideoId = ref('')
const currentName = ref('')
const playerSrc = ref('')
const playerRef = ref(null)
const pendingSeek = ref(null)
const libraryRef = ref(null)

async function bootSession() {
  if (!getToken()) return
  try {
    user.value = await fetchMe()
  } catch (e) {
    // 令牌失效：请求层已清掉本地令牌并广播，这里只需留在登录页
  }
}

async function submitAuth() {
  authBusy.value = true
  authError.value = ''
  try {
    const resp = isLogin.value
      ? await login(authForm.username, authForm.password)
      : await register(authForm.username, authForm.password)
    setToken(resp.token)
    user.value = resp
    if (rememberMe.value) {
      saveRemembered()
    } else {
      clearRemembered()
      authForm.password = ''
    }
  } catch (e) {
    authError.value = e.message
  } finally {
    authBusy.value = false
  }
}

function switchMode() {
  authMode.value = isLogin.value ? 'register' : 'login'
  authError.value = ''
}

function signOut() {
  logout()
  onUnauthorized()
}

/** 令牌失效（任意请求返回 401）：退回登录页并清掉当前视频，避免残留上一个账号的画面 */
function onUnauthorized() {
  user.value = null
  currentVideoId.value = ''
  currentName.value = ''
  playerSrc.value = ''
  pendingSeek.value = null
  applyRemembered()
}

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
  let info = null
  try {
    info = await getVideoInfo(videoId)
    currentName.value = info.fileName
  } catch (e) {
    // 列表里可能混有失败的视频：取不到文件名时退回显示 ID，不影响播放尝试
    currentName.value = ''
  }
  pendingSeek.value = seekTime
  // 播放地址用带鉴权的 info 接口返回的预签名 URL：不再给 <video> 单独发票据（预签名 URL 本来就带 2 小时有效期）
  playerSrc.value = info?.playUrl || ''
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

onMounted(() => {
  window.addEventListener('vsearch:unauthorized', onUnauthorized)
  applyRemembered()
  bootSession()
})

onUnmounted(() => {
  window.removeEventListener('vsearch:unauthorized', onUnauthorized)
})
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.auth-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 14px;
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.12);
}

.auth-card h1 {
  font-size: 19px;
  margin: 0;
  text-align: center;
}

.auth-card label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: #475569;
}

.auth-card input {
  padding: 10px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}

.auth-card input:focus {
  border-color: #2563eb;
}

/* 记住账号密码：勾选框要横排，且不能继承上面输入框的内边距与边框 */
.auth-remember {
  flex-direction: row !important;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
}

.auth-remember input {
  width: 14px;
  height: 14px;
  padding: 0;
  border: none;
  accent-color: #2563eb;
}

.auth-error {
  margin: 0;
  color: #dc2626;
  font-size: 13px;
}

.auth-submit {
  padding: 11px;
  border: none;
  border-radius: 8px;
  background: #2563eb;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}

.auth-submit:disabled {
  opacity: 0.6;
  cursor: default;
}

.auth-switch {
  border: none;
  background: none;
  color: #2563eb;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

.page {
  min-height: 100vh;
}

.header-user {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.header-user button {
  border: 1px solid rgba(255, 255, 255, 0.5);
  background: none;
  color: #fff;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 99px;
  cursor: pointer;
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
