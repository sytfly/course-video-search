import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址：默认 8080，与 application.yml 的 server.port 保持一致。
// 8080 被别的项目占用时，后端会改用 --server.port=8088 启动，此时必须同步设置
// VITE_API_TARGET=http://localhost:8088 再 npm run dev，否则代理指向空端口，
// 浏览器上传会直接 ECONNREFUSED（表现为"传不上去"）。
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // SSE 必须关闭代理缓冲，否则进度事件会被攒包
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        // 流式转发
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache'
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        }
      }
    }
  }
})
