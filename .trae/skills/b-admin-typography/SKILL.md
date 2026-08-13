---
name: "b-admin-typography"
description: "B端管理后台字体规范与样式统一方案。当需要为管理后台项目设置统一字体规范、调整字号、字重、行高，或统一Element Plus/Ant Design组件字体样式时使用。"
---

# B端管理后台字体规范

## 概述

本技能提供B端（后台管理系统）字体规范的完整解决方案，包括：
- 字号、字重、行高的标准化定义
- CSS变量化，一处修改全局生效
- 主流UI框架（Element Plus / Ant Design）组件样式覆盖
- 通用页面布局样式类

## 设计令牌（Design Tokens）

### 字号规范

```css
/* 基准正文 14px，最小可读 12px，不使用 11px 及以下 */
--font-xs: 12px;      /* 辅助文字/提示/时间/badge标签 */
--font-sm: 12px;      /* 同 xs */
--font-base: 14px;    /* B端基准正文：表单label、按钮文字、表格内容 */
--font-lg: 16px;      /* 卡片/模块标题、弹窗标题、侧边一级菜单 */
--font-xl: 20px;      /* 页面大标题 */
--font-2xl: 24px;     /* 超大数据展示 */
--font-3xl: 28px;     /* 特大数据展示 */
```

### 字重规范

```css
--weight-normal: 400;     /* 正文、表格内容、表单label */
--weight-medium: 500;     /* 卡片标题、侧边一级菜单 */
--weight-semibold: 600;   /* 弹窗标题、页面大标题、表格表头 */
--weight-bold: 700;       /* 超大数据 */
```

### 行高规范

```css
--lh-tight: 18px;    /* 辅助文字 */
--lh-base: 22px;     /* 正文 */
--lh-loose: 24px;    /* 标题 */
--lh-xloose: 28px;   /* 大标题 */
```

## 完整 CSS 文件模板

将以下内容保存为 `styles/global.css`（或项目对应的全局样式文件）：

```css
/* ============================================================
   B端管理后台 - 统一字体规范与样式系统
   适用框架：Vue3 + Element Plus / React + Ant Design
   ============================================================ */

:root {
  /* ===== 字号规范 ===== */
  --font-xs: 12px;
  --font-sm: 12px;
  --font-base: 14px;
  --font-lg: 16px;
  --font-xl: 20px;
  --font-2xl: 24px;
  --font-3xl: 28px;

  /* ===== 字重规范 ===== */
  --weight-normal: 400;
  --weight-medium: 500;
  --weight-semibold: 600;
  --weight-bold: 700;

  /* ===== 行高规范 ===== */
  --lh-tight: 18px;
  --lh-base: 22px;
  --lh-loose: 24px;
  --lh-xloose: 28px;

  /* ===== 间距系统 (4px base) ===== */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 20px;
  --space-6: 24px;
  --space-8: 32px;
  --space-10: 40px;
  --space-12: 48px;
  --space-16: 64px;

  /* ===== 圆角 ===== */
  --radius-xs: 4px;
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --radius-xl: 16px;
  --radius-full: 9999px;

  /* ===== 颜色（可根据品牌调整） ===== */
  --color-bg: #F2F3F5;
  --color-bg-2: #FFFFFF;
  --color-text: #1F2328;
  --color-text-2: #4E5660;
  --color-text-3: #8A919F;
  --color-primary: #165DFF;

  /* ===== 布局 ===== */
  --sidebar-width: 240px;
  --header-height: 60px;
  --page-padding: 24px;
}

/* ===== 基础重置 ===== */
html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  width: 100%;
  background: var(--color-bg);
  color: var(--color-text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
    "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif;
  font-size: var(--font-base);
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
}

/* ============================================================
   Element Plus 组件样式覆盖
   ============================================================ */

/* 按钮 */
.el-button {
  font-size: var(--font-base) !important;
  font-weight: var(--weight-medium) !important;
}

/* 表格 */
.el-table th.el-table__cell {
  font-size: var(--font-base) !important;
  font-weight: var(--weight-semibold) !important;
  line-height: var(--lh-base) !important;
}
.el-table td.el-table__cell {
  font-size: var(--font-base) !important;
  line-height: var(--lh-base) !important;
  font-weight: var(--weight-normal) !important;
}

/* 弹窗标题 */
.el-dialog__title {
  font-size: var(--font-lg) !important;
  font-weight: var(--weight-semibold) !important;
  line-height: var(--lh-loose) !important;
}
.el-dialog__body {
  font-size: var(--font-base) !important;
}

/* 表单 */
.el-form-item__label {
  font-size: var(--font-base) !important;
  font-weight: var(--weight-normal) !important;
}

/* 侧边菜单 */
.el-menu-item:not(.el-sub-menu .el-menu-item),
.el-sub-menu__title {
  font-size: var(--font-lg) !important;
  font-weight: var(--weight-medium) !important;
  line-height: var(--lh-loose) !important;
}
.el-sub-menu .el-menu-item {
  font-size: var(--font-base) !important;
}

/* 标签 */
.el-tag {
  font-size: var(--font-xs) !important;
  font-weight: var(--weight-medium) !important;
}

/* 徽章 */
.el-badge__content {
  font-size: var(--font-xs) !important;
  font-weight: var(--weight-semibold) !important;
}

/* 分页 */
.el-pagination {
  font-size: var(--font-base) !important;
}

/* 消息提示 */
.el-message-box__title {
  font-size: var(--font-lg) !important;
  font-weight: var(--weight-semibold) !important;
}
.el-message-box__message p {
  font-size: var(--font-base) !important;
}

/* ============================================================
   Ant Design 组件样式覆盖
   ============================================================ */

/* 按钮 */
.ant-btn {
  font-size: var(--font-base);
  font-weight: var(--weight-medium);
}

/* 表格 */
.ant-table-thead > tr > th {
  font-size: var(--font-base);
  font-weight: var(--weight-semibold);
  line-height: var(--lh-base);
}
.ant-table-tbody > tr > td {
  font-size: var(--font-base);
  font-weight: var(--weight-normal);
}

/* 弹窗 */
.ant-modal-title {
  font-size: var(--font-lg);
  font-weight: var(--weight-semibold);
  line-height: var(--lh-loose);
}
.ant-modal-body {
  font-size: var(--font-base);
}

/* 表单 */
.ant-form-item-label > label {
  font-size: var(--font-base);
  font-weight: var(--weight-normal);
}

/* 菜单 */
.ant-menu-item,
.ant-menu-submenu-title {
  font-size: var(--font-base);
}
.ant-menu-sub.ant-menu-inline .ant-menu-item {
  font-size: var(--font-sm);
}

/* ============================================================
   通用页面布局样式（全局，勿在页面重复定义）
   ============================================================ */

/* 页面容器 */
.page-container {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 页面头部 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-5) var(--space-6);
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-bg-2);
  flex-shrink: 0;
}

/* 页面标题 20px / 600 / 行高28px */
.page-title {
  margin: 0 !important;
  font-size: var(--font-xl) !important;
  font-weight: var(--weight-semibold) !important;
  color: var(--color-text) !important;
  line-height: var(--lh-xloose) !important;
}

/* 页面描述 14px / 400 / 行高24px */
.page-desc {
  margin: 0 !important;
  font-size: var(--font-base) !important;
  font-weight: var(--weight-normal) !important;
  line-height: var(--lh-loose) !important;
  color: var(--color-text-2) !important;
}

/* 页面内容区 */
.page-body {
  flex: 1 !important;
  overflow: auto !important;
  padding: var(--space-5) var(--space-6) !important;
}

/* 卡片/模块标题 16px / 500 / 行高24px */
.card-title,
.module-title {
  font-size: var(--font-lg);
  font-weight: var(--weight-medium);
  line-height: var(--lh-loose);
}

/* 辅助文字 12px / 400 / 行高18px */
.text-xs,
.text-aux,
.text-muted {
  font-size: var(--font-xs);
  font-weight: var(--weight-normal);
  line-height: var(--lh-tight);
  color: var(--color-text-3);
}

/* 表格备注小字 */
.table-note,
.table-sub {
  font-size: var(--font-xs);
  font-weight: var(--weight-normal);
  line-height: var(--lh-tight);
  color: var(--color-text-3);
}
```

## 实现原则

### 1. 单一来源原则
- 所有字体大小必须通过 CSS 变量引用
- 禁止在组件中直接写 `font-size: 14px` 等硬编码值
- 修改字体规范只需改动 `:root` 中的变量

### 2. 全局覆盖原则
- 在全局样式文件（如 `global.css`）中覆盖 UI 组件库样式
- 使用 `!important` 确保覆盖组件库默认样式
- 组件文件中不再重复定义字体样式

### 3. 语义化类名
使用预定义的通用类名，而非在每个页面重复定义：
- `.page-title` - 页面大标题
- `.page-desc` - 页面描述
- `.card-title` / `.module-title` - 卡片标题
- `.text-xs` / `.text-muted` - 辅助文字
- `.table-note` / `.table-sub` - 表格小字

## 使用示例

### Vue3 + Element Plus

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title-wrap">
        <h1 class="page-title">用户管理</h1>
        <p class="page-desc">管理系统用户和权限</p>
      </div>
    </div>
    <div class="page-body">
      <el-table :data="users">
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="role" label="角色" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
// 无需在组件中定义字体样式
</script>

<style scoped>
/* 如需局部样式，仅定义与布局/颜色相关的样式，不定义字体 */
</style>
```

### React + Ant Design

```jsx
function UserManagement() {
  return (
    <div className="page-container">
      <div className="page-header">
        <div>
          <h1 className="page-title">用户管理</h1>
          <p className="page-desc">管理系统用户和权限</p>
        </div>
      </div>
      <div className="page-body">
        <Table columns={columns} dataSource={users} />
      </div>
    </div>
  );
}
```

## 检查清单

当应用此规范时，需完成以下检查：

- [ ] 所有页面标题使用 `.page-title` 类
- [ ] 所有页面描述使用 `.page-desc` 类
- [ ] 卡片标题使用 `.card-title` 或 `.module-title` 类
- [ ] 辅助文字使用 `.text-xs` 或 `.text-muted` 类
- [ ] 组件中无硬编码的 `font-size` 值
- [ ] 全局样式文件中包含完整的设计令牌
- [ ] Element Plus / Ant Design 组件样式已覆盖

## 版本信息

- 版本: 1.0.0
- 适用场景: B端管理后台、数据看板、CRM/ERP系统
- 设计基准: 14px 正文，12px 最小可读
