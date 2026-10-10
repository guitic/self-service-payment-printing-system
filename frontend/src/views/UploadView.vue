<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type UploadFile, type UploadRawFile } from 'element-plus'
import { uploadFile } from '@/api/file'
import { useOrderStore } from '@/stores/order'
import { formatBytes } from '@/utils/format'

const router = useRouter()
const orderStore = useOrderStore()

const selectedFile = ref<UploadRawFile | null>(null)
const uploading = ref(false)

// 支持的文件类型（与后端 2001 错误码约定一致）
const ACCEPT = '.pdf,.doc,.docx,.txt,.png,.jpg,.jpeg'

function handleChange(file: UploadFile) {
  selectedFile.value = file.raw || null
}

function handleRemove() {
  selectedFile.value = null
}

async function handleUpload() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const info = await uploadFile(selectedFile.value)
    orderStore.setFile(info)
    ElMessage.success(`解析成功，共 ${info.pageCount} 页`)
    router.push('/params')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="card-title">上传打印文件</div>

      <el-upload
        class="uploader"
        drag
        :auto-upload="false"
        :show-file-list="false"
        :accept="ACCEPT"
        :on-change="handleChange"
        :on-remove="handleRemove"
      >
        <div class="uploader-icon">📄</div>
        <div class="uploader-text">
          将文件拖到此处，或<em>点击选择</em>
        </div>
        <template #tip>
          <div class="uploader-tip">
            支持 PDF / Word / 文本 / 图片，单个文件大小以服务端限制为准
          </div>
        </template>
      </el-upload>

      <div v-if="selectedFile" class="file-info">
        <div class="file-row">
          <span class="file-label">文件名</span>
          <span class="file-value">{{ selectedFile.name }}</span>
        </div>
        <div class="file-row">
          <span class="file-label">大小</span>
          <span class="file-value">{{ formatBytes(selectedFile.size) }}</span>
        </div>
        <div class="file-row">
          <span class="file-label">解析页数</span>
          <span class="file-value text-muted">上传后自动解析</span>
        </div>
      </div>

      <div class="actions mt-24">
        <el-button
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!selectedFile"
          @click="handleUpload"
        >
          上传并解析
        </el-button>
        <el-button v-if="selectedFile" size="large" @click="handleRemove">重新选择</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.uploader :deep(.el-upload-dragger) {
  padding: 48px 20px;
  border-radius: 12px;
  transition: all 0.2s;
}
.uploader-icon {
  font-size: 52px;
  line-height: 1;
}
.uploader-text {
  margin-top: 12px;
  font-size: 15px;
  color: #606266;
}
.uploader-text em {
  color: var(--sp-primary);
  font-style: normal;
}
.uploader-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.file-info {
  margin-top: 24px;
  padding: 16px 20px;
  background: #f7f9fc;
  border-radius: 10px;
}
.file-row {
  display: flex;
  padding: 6px 0;
  font-size: 14px;
}
.file-label {
  width: 90px;
  color: #909399;
  flex-shrink: 0;
}
.file-value {
  color: #303133;
  word-break: break-all;
}
.actions {
  display: flex;
  gap: 12px;
}
</style>
