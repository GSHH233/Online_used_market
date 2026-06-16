<script setup lang="ts">
defineProps<{ title: string }>()
</script>

<template>
  <section class="workbench">
    <header class="wb-header"><h2>{{ title }}</h2></header>
    <div class="wb-grid">
      <aside class="wb-left"><slot name="left" /></aside>
      <main class="wb-center"><slot name="center" /></main>
      <aside class="wb-right"><slot name="right" /></aside>
      <footer class="wb-bottom"><slot name="bottom" /></footer>
    </div>
  </section>
</template>