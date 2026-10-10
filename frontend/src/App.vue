<script setup lang="ts">
import { onMounted } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

// 刷新后若已有 token，拉取用户信息
onMounted(() => {
  if (userStore.isLoggedIn && !userStore.user) {
    userStore.fetchMe()
  }
})
</script>

<template>
  <router-view />
</template>
