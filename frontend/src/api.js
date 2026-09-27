const BASE = '/api/video'
const AUTH_BASE = '/api/auth'

/** 令牌存 localStorage：刷新页面后仍保持登录；登出 / 401 时清掉 */
const TOKEN_KEY = 'vsearch.token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/** 登录失效：清令牌并广播，App 收到后切回登录页（避免每个调用点各自处理跳转） */
function expireSession() {
  setToken('')
  window.dispatchEvent(new CustomEvent('vsearch:unauthorized'))
}

/** 所有请求都带 Bearer 令牌；multipart 请求不设 Content-Type，交给浏览器补 boundary */
function withAuth(headers) {
  const token = getToken()
  return token ? {...headers, Authorization: `Bearer ${token}`} : headers
}

/**
 * 统一解析后端的 {code,message,data} 包装。
 * 两种 401 都要按登录失效处理：Spring Security 拦下时是 HTTP 401，业务层抛的是 code=401。
 */
async function unwrap(resp) {
  if (resp.status === 401) {
    expireSession()
    throw new Error('登录状态已失效，请重新登录')
  }
  const body = await resp.json().catch(() => null)
  if (!body) {
    throw new Error(`请求失败(${resp.status})`)
  }
  if (body.code === 401) {
    expireSession()
  }
  if (body.code !== 0) {
    throw new Error(body.message || `请求失败(${resp.status})`)
  }
  return body.data
}

function request(url, options = {}) {
  return fetch(url, {...options, headers: withAuth(options.headers)}).then(unwrap)
}

/** 分片大小请求值：实际以后端 init 响应返回的 chunkSize 为准（越界时后端会改成它的默认值） */
const CHUNK_SIZE = 5 * 1024 * 1024

/**
 * 并行上传的分片数。实测结论：并行对总时长几乎没有帮助（瓶颈在上行带宽），
 * 它的作用是把「单片失败」的影响面缩小到一个请求上；3 路是延迟与吞吐的折中。
 */
const PART_CONCURRENCY = 3

/** 单片失败重试次数：分片按序号幂等覆盖，网络抖动 / 服务端瞬时错误都可安全重试 */
const PART_RETRIES = 3

/** 单个分片请求的超时（毫秒）：没有它，一个挂死的连接会永久占住一路并发，重试也就无从触发 */
const PART_TIMEOUT_MS = 60 * 1000
/** init / complete 的超时：complete 要在服务端合并分片并算指纹，给得更宽 */
const CONTROL_TIMEOUT_MS = 10 * 60 * 1000

/** SSE 静默超时：超过该时长没有任何进度事件，就反查任务真实状态（见 checkAlive） */
const SILENCE_TIMEOUT_MS = 60 * 1000

/** 后端 VideoStatus 常量 */
const STATUS_DONE = 2
const STATUS_FAILED = 3

/**
 * 分片上传会话指纹：由「文件名 + 大小 + 修改时间」算出，同一批文件任何时候都得到同一个 key。
 * 服务端据此复用会话，故刷新页面 / 断网重连后重新选同一批文件，就能拿回已上传的分片号实现断点续传。
 */
function sessionKeyOf(list) {
  const raw = list.map((f) => `${f.name}|${f.size}|${f.lastModified || 0}`).join('#')
  let hash = 5381
  for (let i = 0; i < raw.length; i++) {
    hash = ((hash * 33) ^ raw.charCodeAt(i)) >>> 0
  }
  return `sk_${hash.toString(36)}_${list.length}_${list[0].size % 100000}`
}

/** 第 n 片的字节数：末片通常短于 chunkSize，其余正好一片 */
function partSize(size, chunkSize, total, n) {
  return n < total ? chunkSize : size - chunkSize * (total - 1)
}

/** 带超时的 POST，统一解析后端的 {code,message,data} 包装 */
async function post(path, {json, form} = {}, timeoutMs = CONTROL_TIMEOUT_MS) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await request(`${BASE}${path}`, {
      method: 'POST',
      signal: controller.signal,
      headers: json ? {'Content-Type': 'application/json'} : undefined,
      body: json ? JSON.stringify(json) : form
    })
  } finally {
    clearTimeout(timer)
  }
}

/** 分片上传-初始化：返回 uploadId + 各文件分片计划（重连时带回服务端已收到的分片号） */
export function initChunkUpload(files) {
  const list = Array.isArray(files) ? files : [files]
  return post('/upload/init', {
    json: {
      sessionKey: sessionKeyOf(list),
      chunkSize: CHUNK_SIZE,
      files: list.map((f) => ({name: f.name, size: f.size, contentType: f.type || ''}))
    }
  })
}

/** 上传单个分片（服务端按分片号幂等覆盖，失败可直接重发） */
export function uploadChunkPart(uploadId, fileIndex, partNumber, blob) {
  const form = new FormData()
  // 必须带文件名：Spring 只把「有 filename 的 part」当文件，否则会当成普通表单字段而报"缺少 part"
  form.append('part', blob, `part-${fileIndex}-${partNumber}`)
  const qs = new URLSearchParams({uploadId, fileIndex: String(fileIndex), partNumber: String(partNumber)})
  return post(`/upload/part?${qs}`, {form}, PART_TIMEOUT_MS)
}

/** 合并分片：服务端校验分片齐全 → 按序合并 + 算内容指纹 → 走与单次上传相同的入库路径 */
export function completeChunkUpload(uploadId, uploadTaskId) {
  const qs = new URLSearchParams({uploadId})
  if (uploadTaskId) qs.set('uploadTaskId', uploadTaskId)
  return post(`/upload/complete?${qs}`, {form: new FormData()})
}

/** 固定并发度的任务池（分片并行上传）：任一片失败即停止派发后续分片，不留无人回收的请求 */
async function runPool(jobs, worker, concurrency = PART_CONCURRENCY) {
  let cursor = 0
  let failure = null
  const runners = Array.from({length: Math.min(concurrency, jobs.length)}, async () => {
    while (cursor < jobs.length && !failure) {
      const job = jobs[cursor++]
      try {
        await worker(job)
      } catch (e) {
        failure = failure || e
      }
    }
  })
  await Promise.all(runners)
  if (failure) {
    throw failure
  }
}

async function withRetry(fn, attempts = PART_RETRIES) {
  let last
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn()
    } catch (e) {
      last = e.name === 'AbortError' ? new Error('单片上传超时（已重试）') : e
      if (i < attempts - 1) {
        await new Promise((resolve) => setTimeout(resolve, 300 * (i + 1)))
      }
    }
  }
  throw last
}

/**
 * 上传视频：File.slice 切片 → 并行上传 → 服务端合并入流水线。
 * files 可为单个 File 或 [主文件, 声音轨]（B站 .m4s 双轨场景，顺序无关）。
 * uploadTaskId 为客户端自带的上传期进度 ID（up_xxx），服务端把「合并 / 归一化 / 写对象存储」的进度推给它。
 *
 * onProgress(pct, info)：pct 由「服务端已确认收下的分片字节数」算出（不是本地上传缓冲区的估算），
 * info = {resumed, skippedParts, totalParts}，用于提示本次是否为断点续传、复用了多少片。
 *
 * 断点续传：init 会带回服务端已收到的分片号，本次只补缺失的片；失败后重选同一批文件再传即可继续。
 */
export async function uploadVideo(files, onProgress, uploadTaskId) {
  const list = Array.isArray(files) ? files : [files]
  const plan = await initChunkUpload(list)
  const chunkSize = plan.chunkSize
  const total = plan.files.reduce((sum, f) => sum + f.size, 0)
  let doneBytes = 0
  let skippedParts = 0
  let totalParts = 0
  const jobs = []
  for (const f of plan.files) {
    const received = new Set(f.receivedParts || [])
    for (let n = 1; n <= f.totalParts; n++) {
      totalParts += 1
      if (received.has(n)) {
        // 服务端已收齐的片直接计入进度，不重传——这就是断点续传省下的那部分
        skippedParts += 1
        doneBytes += partSize(f.size, chunkSize, f.totalParts, n)
      } else {
        jobs.push({fileIndex: f.index, partNumber: n, size: f.size, totalParts: f.totalParts})
      }
    }
  }
  const info = {resumed: plan.resumed, skippedParts, totalParts}
  const report = (pct) => onProgress?.(Math.min(99, Math.round(pct)), info)
  if (doneBytes > 0) {
    report((doneBytes / total) * 100)
  }

  try {
    await runPool(jobs, async (job) => {
      const start = (job.partNumber - 1) * chunkSize
      const blob = list[job.fileIndex].slice(start, Math.min(start + chunkSize, job.size))
      await withRetry(() => uploadChunkPart(plan.uploadId, job.fileIndex, job.partNumber, blob))
      doneBytes += blob.size
      report((doneBytes / total) * 100)
    })
  } catch (e) {
    // 已上传的分片还留在服务端会话里（24h）：重选同一批文件再传，只会补缺失的片
    throw new Error(`${e.message}；已上传的分片保存在服务端，重新选择同一批文件再上传即可从断点续传`)
  }

  onProgress?.(100, info)
  try {
    return await completeChunkUpload(plan.uploadId, uploadTaskId)
  } catch (e) {
    // 分片已留在服务端（会话 24h 有效）：重选同一批文件再传即可续传，不用把整个文件重传一遍
    throw new Error(`${e.message}；已上传的分片保存在服务端，重新选择同一批文件再上传即可续传`)
  }
}

/**
 * 订阅处理进度。
 * opts.probe=false 关闭静默看门狗（上传期进度流用：那个 ID 不是真实任务，反查只会 404）。
 *
 * EventSource 带不了 Authorization 头，故先用带鉴权的换票接口拿一张与「租户 + 资源」绑定的票据，
 * 拿到票据后才真正建连。返回的对象只暴露 close()（调用方只用到它）。
 */
export function openProgressStream(taskId, handlers, opts = {}) {
  const probing = opts.probe !== false
  let timer = null
  let finished = false
  let es = null

  const stop = () => {
    finished = true
    clearTimeout(timer)
    es?.close()
  }

  // 每收到一次事件就重新计时；静默超时后不再干等，直接反查后端
  const arm = () => {
    if (!probing) return
    clearTimeout(timer)
    timer = setTimeout(checkAlive, SILENCE_TIMEOUT_MS)
  }

  async function checkAlive() {
    if (finished) return
    let info
    try {
      info = await getVideoInfo(taskId)
    } catch (e) {
      // 反查本身失败（后端不可达）：保持连接，下一轮再试
      arm()
      return
    }
    if (finished) return
    if (info.status === STATUS_DONE) {
      stop()
      handlers.onDone?.({stage: 'done', progress: 100, message: '处理完成'})
    } else if (info.status === STATUS_FAILED) {
      stop()
      handlers.onFailed?.({stage: 'failed', progress: 100, message: info.errorMsg || '处理失败'})
    } else if (info.interrupted) {
      // 状态仍是处理中，但处理锁空闲 + 进度已静默 ⇒ 后端进程消失，任务不会自愈
      stop()
      handlers.onInterrupted?.(info)
    } else {
      // 仍在正常处理（例如 ASR 单块耗时较长），继续等
      arm()
    }
  }

  fetchTicket(taskId)
    .then((ticket) => {
      if (finished) return
      es = new EventSource(`${BASE}/progress/${taskId}?ticket=${encodeURIComponent(ticket)}`)
      es.addEventListener('progress', (e) => {
        const evt = JSON.parse(e.data)
        handlers.onEvent?.(evt)
        if (evt.stage === 'done') {
          stop()
          handlers.onDone?.(evt)
          return
        }
        if (evt.stage === 'failed') {
          stop()
          handlers.onFailed?.(evt)
          return
        }
        arm()
      })
      es.onerror = () => {
        // 后端完成时会主动关闭连接，这里不弹错误；真正的中断交给静默看门狗判定
      }
      arm()
    })
    .catch((e) => {
      if (finished) return
      stop()
      handlers.onFailed?.({stage: 'failed', progress: 100, message: e.message})
    })

  return {close: stop}
}

/** 换访问票据：票据与「当前租户 + 该资源」绑定，故拿别人的 id 换不到 */
export function fetchTicket(resourceId) {
  const qs = new URLSearchParams({resourceId})
  return request(`${BASE}/ticket?${qs}`, {method: 'POST'})
}

export function getVideoInfo(videoId) {
  return request(`${BASE}/${videoId}`)
}

/** 视频库列表：按上传时间倒序，含文件名/时长/状态/片段数/章节骨架 */
export function listVideos() {
  return request(`${BASE}/list`)
}

/** 删除视频：后端会同对象存储文件、片段、ASR 断点、本地工作目录一起清掉，不可恢复 */
export function deleteVideo(videoId) {
  return request(`${BASE}/${videoId}`, {method: 'DELETE'})
}

export function search(query, topK = 5, videoId = '') {
  return request(`${BASE}/search`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({query, topK, videoId: videoId || undefined})
  })
}

// ------------------------------------------------------------------ 账号

/** 注册：后端自动开一个租户并把存量数据隔离在各自租户下，注册成功即返回令牌 */
export function register(username, password) {
  return request(`${AUTH_BASE}/register`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({username, password})
  })
}

export function login(username, password) {
  return request(`${AUTH_BASE}/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({username, password})
  })
}

/** 校验本地令牌是否仍有效（App 启动时调用；失效会被拦成 401 并触发回登录页） */
export function fetchMe() {
  return request(`${AUTH_BASE}/me`)
}

export function logout() {
  setToken('')
}

export function formatTime(sec) {
  const s = Math.max(0, Math.round(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(r).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}
