#!/usr/bin/env node
/**
 * 下载 Tesseract OCR 语言包到 backend/data/tessdata/（后端 OCR_TESSDATA_PATH 默认目录）。
 * 来源 tessdata_fast：LSTM 快速版，体积与速度均衡（chi_sim ≈ 2.4MB / eng ≈ 4.7MB）；
 * 追求更高识别精度可手动换用 tessdata_best 仓库的同名文件覆盖，后端无需改动。
 * 用法：node scripts/download-tessdata.mjs
 */
import { createWriteStream, existsSync, mkdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

// import.meta.dirname 需要 Node 20.11+（前端 Vite 7 对 Node 的要求更高，无兼容问题）
const TARGET_DIR = path.resolve(import.meta.dirname, '..', 'backend', 'data', 'tessdata');
const LANGS = ['chi_sim', 'eng'];
// 双源兜底：GitHub 直连失败（常见于国内网络）自动切 jsDelivr 的 GitHub 镜像
const SOURCES = [
  (lang) => `https://github.com/tesseract-ocr/tessdata_fast/raw/main/${lang}.traineddata`,
  (lang) => `https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/${lang}.traineddata`,
];

mkdirSync(TARGET_DIR, { recursive: true });

for (const lang of LANGS) {
  const target = path.join(TARGET_DIR, `${lang}.traineddata`);
  if (existsSync(target) && statSync(target).size > 0) {
    console.log(`已存在，跳过：${target}`);
    continue;
  }
  let done = false;
  for (const source of SOURCES) {
    const url = source(lang);
    process.stdout.write(`下载 ${lang}.traineddata（${new URL(url).host}）... `);
    try {
      const response = await fetch(url, { redirect: 'follow' });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      await pipeline(Readable.fromWeb(response.body), createWriteStream(target));
      console.log(`完成（${(statSync(target).size / 1024 / 1024).toFixed(1)} MB）`);
      done = true;
      break;
    } catch (e) {
      console.log(`失败：${e.message}`);
    }
  }
  if (!done) {
    console.error(`\n${lang}.traineddata 全部下载源失败，请手动下载后放入：${TARGET_DIR}`);
    process.exitCode = 1;
  }
}

console.log(`\n语言包目录：${TARGET_DIR}`);
console.log('后端默认即指向该目录（./data/tessdata，相对后端工作目录），无需额外配置。');
