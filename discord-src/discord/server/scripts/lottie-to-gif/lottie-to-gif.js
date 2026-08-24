/**
 * Lottie JSON 转 GIF 工具
 * 将 Lottie 动画转换为 GIF 动图
 */

const fs = require('fs');
const path = require('path');
const { createCanvas } = require('canvas');
const lottieWeb = require('lottie-web');
const GIFEncoder = require('gif-encoder-2');

// 解析命令行参数
const args = process.argv.slice(2);
const inputPath = args[0];
const outputPath = args[1] || inputPath.replace('.json', '.gif');

const options = {
  width: 320,
  height: 320,
  fps: 15,
  duration: 3,
  background: null,
  transparent: false
};

for (let i = 2; i < args.length; i++) {
  switch (args[i]) {
    case '--width':
      options.width = parseInt(args[++i]) || 320;
      break;
    case '--height':
      options.height = parseInt(args[++i]) || 320;
      break;
    case '--fps':
      options.fps = parseInt(args[++i]) || 15;
      break;
    case '--duration':
      options.duration = parseInt(args[++i]) || 3;
      break;
    case '--background':
      options.background = args[++i];
      break;
    case '--transparent':
      options.transparent = true;
      break;
  }
}

async function lottieToGif(inputPath, outputPath, options) {
  return new Promise((resolve, reject) => {
    try {
      if (!fs.existsSync(inputPath)) {
        reject(new Error(`Input file not found: ${inputPath}`));
        return;
      }

      const lottieData = JSON.parse(fs.readFileSync(inputPath, 'utf8'));

      // 获取原始尺寸
      const origWidth = lottieData.w || 320;
      const origHeight = lottieData.h || 320;
      
      // 计算缩放比例
      const scale = Math.min(options.width / origWidth, options.height / origHeight, 1);
      const renderWidth = Math.floor(origWidth * scale);
      const renderHeight = Math.floor(origHeight * scale);

      // 创建 Canvas
      const canvas = createCanvas(options.width, options.height);
      const ctx = canvas.getContext('2d');

      // 处理背景
      if (options.background && !options.transparent) {
        ctx.fillStyle = options.background;
        ctx.fillRect(0, 0, options.width, options.height);
      }

      // 计算总帧数
      const totalFrames = Math.ceil(options.fps * options.duration);
      
      // 创建 GIF 编码器
      const encoder = new GIFEncoder(options.width, options.height);
      encoder.setDelay(Math.round(1000 / options.fps));
      encoder.setRepeat(0);
      encoder.start();

      // 实例化 lottie
      const animation = lottieWeb.loadAnimation({
        animationData: lottieData,
        renderer: 'canvas',
        rendererSettings: {
          canvas: canvas,
          context: '2d',
          scale: scale,
          clearCanvas: false
        },
        loop: true,
        autoplay: false
      });

      let frameCount = 0;
      const frameInterval = 1000 / options.fps;
      let animDuration = animation.getDuration();
      
      // 如果动画没有正确加载，使用默认时长
      if (!animDuration || animDuration <= 0) {
        animDuration = options.duration * 1000;
      }

      // 先渲染第一帧
      animation.goToAndStop(0, true);
      encoder.addFrame(ctx.getImageData(0, 0, options.width, options.height).data);
      frameCount = 1;

      // 使用 setInterval 逐帧渲染
      const interval = setInterval(() => {
        try {
          const nextFrameMs = frameCount * frameInterval;
          
          if (nextFrameMs >= animDuration) {
            const loopedMs = nextFrameMs % animDuration;
            animation.goToAndStop(loopedMs, true);
          } else {
            animation.goToAndStop(nextFrameMs, true);
          }
          
          encoder.addFrame(ctx.getImageData(0, 0, options.width, options.height).data);
          frameCount++;

          if (frameCount >= totalFrames) {
            clearInterval(interval);
            encoder.finish();
            
            const buffer = encoder.out.getData();
            fs.writeFileSync(outputPath, buffer);
            
            animation.destroy();
            resolve(outputPath);
          }
        } catch (err) {
          clearInterval(interval);
          animation.destroy();
          reject(err);
        }
      }, frameInterval);

      // 超时保护
      setTimeout(() => {
        if (frameCount < totalFrames) {
          clearInterval(interval);
          try { animation.destroy(); } catch(e) {}
          reject(new Error('Timeout: Animation rendering too slow'));
        }
      }, options.duration * 1000 + 10000);

    } catch (error) {
      reject(error);
    }
  });
}

// 执行
if (require.main === module) {
  if (!inputPath) {
    console.error('Usage: node lottie-to-gif.js <input.json> [output.gif]');
    process.exit(1);
  }

  console.log(`Converting: ${inputPath} -> ${outputPath}`);
  console.log(`Options: ${JSON.stringify(options)}`);

  lottieToGif(inputPath, outputPath, options)
    .then(output => {
      console.log(`Done: ${output}`);
      process.exit(0);
    })
    .catch(err => {
      console.error('Failed:', err.message);
      process.exit(1);
    });
}

module.exports = { lottieToGif };
