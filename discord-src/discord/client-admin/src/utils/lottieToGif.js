import GIF from 'gif.js'
import lottie from 'lottie-web'

/**
 * 将 Lottie JSON 转换为 GIF Blob
 * @param {Object} lottieData - Lottie JSON 数据
 * @param {Object} options - 转换选项
 * @param {number} options.width - GIF 宽度（默认 320）
 * @param {number} options.height - GIF 高度（默认 320）
 * @param {number} options.fps - 帧率（默认 15）
 * @param {number} options.duration - 持续时间（秒，默认 3）
 * @returns {Promise<Blob>} GIF 文件 Blob
 */
export function lottieToGif(lottieData, options = {}) {
  return new Promise((resolve, reject) => {
    try {
      const width = options.width || 320
      const height = options.height || 320
      const fps = options.fps || 15
      const duration = options.duration || 3
      
      // 创建离屏 canvas
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      
      // 创建 lottie 实例
      let anim = null
      try {
        anim = lottie.loadAnimation({
          renderer: 'canvas',
          loop: false,
          autoplay: false,
          animationData: lottieData,
          rendererSettings: {
            canvas: canvas,
            clearCanvas: true
          }
        })
      } catch (e) {
        reject(new Error('Lottie load failed: ' + e.message))
        return
      }
      
      const totalFrames = Math.ceil(fps * duration)
      const frameDuration = 1000 / fps
      let animDuration = 0
      
      anim.addEventListener('data_failed', () => {
        anim.destroy()
        reject(new Error('Lottie data failed'))
      })
      
      anim.addEventListener('enterFrame', () => {
        if (animDuration === 0) {
          animDuration = anim.getDuration()
        }
      })
      
      // 等待第一帧加载
      setTimeout(() => {
        try {
          // 计算实际动画时长
          const actualDuration = animDuration || duration * 1000
          const framesInLoop = Math.ceil(actualDuration / frameDuration)
          const totalFramesToCapture = Math.min(totalFrames, framesInLoop)
          
          // 创建 GIF 编码器
          const gif = new GIF({
            workers: 2,
            quality: 10,
            width: width,
            height: height,
            workerScript: '/workers/gif.worker.js'
          })
          
          let capturedFrames = 0
          
          // 添加帧
          const captureFrames = () => {
            if (capturedFrames >= totalFramesToCapture) {
              // 完成
              gif.on('finished', (blob) => {
                anim.destroy()
                resolve(blob)
              })
              gif.render()
              return
            }
            
            // 跳到指定帧
            const frameMs = capturedFrames * frameDuration
            if (frameMs <= actualDuration) {
              anim.goToAndStop(frameMs, true)
            } else {
              // 循环
              const loopedMs = frameMs % actualDuration
              anim.goToAndStop(loopedMs, true)
            }
            
            // 添加当前帧
            const ctx = canvas.getContext('2d')
            gif.addFrame(ctx, { copy: true })
            
            capturedFrames++
            setTimeout(captureFrames, 10) // 10ms 间隔让渲染完成
          }
          
          captureFrames()
          
        } catch (e) {
          anim.destroy()
          reject(e)
        }
      }, 200) // 等待 lottie 加载
      
      // 超时保护
      setTimeout(() => {
        try { anim.destroy() } catch(e) {}
        reject(new Error('Lottie to GIF conversion timeout'))
      }, duration * 1000 + 5000)
      
    } catch (error) {
      reject(error)
    }
  })
}

/**
 * 将 GIF Blob 上传到服务器
 * @param {Blob} gifBlob - GIF 文件 Blob
 * @param {string} filename - 文件名
 * @returns {Promise<Object>} 上传结果
 */
export async function uploadGifBlob(gifBlob, filename) {
  const formData = new FormData()
  formData.append('file', gifBlob, filename)
  
  const response = await fetch('/api/stickers/upload-gif', {
    method: 'POST',
    body: formData
  })
  
  if (!response.ok) {
    throw new Error('Upload failed: ' + response.statusText)
  }
  
  return response.json()
}

export default { lottieToGif, uploadGifBlob }
