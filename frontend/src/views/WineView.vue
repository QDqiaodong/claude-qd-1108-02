<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const REGIONS = ['国产', '法国', '智利', '澳洲', '其他']
const TYPES = ['红', '白', '起泡', '甜']
const STATUSES = ['在售', '停售']
const STATUS_TAG = { '在售': 'success', '停售': 'info' }

const all = ref([])
const batches = ref([])
const activeNames = ref([...REGIONS])
const reconciling = ref(new Set())

const grouped = computed(() =>
  REGIONS.map(r => ({
    region: r,
    wines: all.value.filter(w => w.region === r)
  })).filter(g => g.wines.length > 0)
)

// 每个酒款：各可售批次的可用瓶数合计（库存的唯一真实来源）
const batchAvailableMap = computed(() => {
  const m = {}
  for (const b of batches.value) {
    if (b.status === '可售') {
      m[b.wineId] = (m[b.wineId] || 0) + (b.availableBottles || 0)
    }
  }
  return m
})
const batchCountMap = computed(() => {
  const m = {}
  for (const b of batches.value) m[b.wineId] = (m[b.wineId] || 0) + 1
  return m
})
function batchAvailable(w) { return batchAvailableMap.value[w.id] || 0 }
function isInconsistent(w) { return w.stock !== batchAvailable(w) }

const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const [w, b] = await Promise.all([api.listWines(), api.listCellarBatches()])
    all.value = w; batches.value = b
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 新增 / 编辑（库存不可录入、不可修改，随批次联动） ----------------
const dialog = ref(false)
const editing = ref(null)
const form = reactive({ code: '', name: '', region: '国产', type: '红', price: 0, status: '在售' })

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', region: '国产', type: '红', price: 0, status: '在售' })
  dialog.value = true
}
function openEdit(w) {
  editing.value = w.id
  Object.assign(form, { code: w.code, name: w.name, region: w.region, type: w.type, price: w.price, status: w.status })
  dialog.value = true
}
async function submit() {
  try {
    if (editing.value) {
      // 回传当前库存：若后端账面与提交不一致会拒绝，防止绕过批次改库存
      const current = all.value.find(w => w.id === editing.value)
      await api.updateWine(editing.value, { ...form, stock: current.stock })
      ElMessage.success('已更新酒款')
    } else {
      await api.createWine({ ...form, stock: 0 })
      ElMessage.success('已新增酒款（初始库存 0，放行窖藏批次后自动增加）')
    }
    dialog.value = false
    await load()
    emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function reconcile(w) {
  if (reconciling.value.has(w.id)) return
  reconciling.value.add(w.id)
  try {
    await api.reconcileWineStock(w.id)
    ElMessage.success(`已按可售批次把「${w.name}」库存校准为 ${batchAvailable(w)} 瓶`)
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
  finally { reconciling.value.delete(w.id) }
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <span class="hint">按产区分组折叠查看酒款 · 库存随窖藏批次放行/出窖/退窖自动联动，不能直接修改</span>
      <el-button type="primary" @click="openCreate">新增酒款</el-button>
    </div>

    <el-collapse v-model="activeNames" class="groups">
      <el-collapse-item v-for="g in grouped" :key="g.region" :name="g.region" :title="`${g.region}（${g.wines.length}）`">
        <ul class="wine-list">
          <li v-for="w in g.wines" :key="w.id" class="wine-item">
            <div class="wine-main">
              <div class="wine-name">
                <span class="code">{{ w.code }}</span>
                <strong>{{ w.name }}</strong>
              </div>
              <div class="wine-meta">
                <el-tag size="small" effect="plain">{{ w.type }}</el-tag>
                <span class="price">¥{{ w.price }}</span>
                <span class="stock">
                  库存 <strong :class="{ 'stock-num': true, 'stock-bad': isInconsistent(w) }">{{ w.stock }}</strong>
                  <span class="stock-from">（{{ batchCountMap[w.id] || 0 }} 个批次，可售批次可用 {{ batchAvailable(w) }}）</span>
                </span>
                <el-tag size="small" :type="STATUS_TAG[w.status]">{{ w.status }}</el-tag>
                <el-tag v-if="isInconsistent(w)" size="small" type="danger" effect="dark">批次库存不一致</el-tag>
              </div>
            </div>
            <div class="wine-ops">
              <el-button
                v-if="isInconsistent(w)"
                size="small" type="warning"
                :loading="reconciling.has(w.id)"
                @click="reconcile(w)"
              >按批次校准库存</el-button>
              <el-button size="small" @click="openEdit(w)">编辑</el-button>
            </div>
          </li>
        </ul>
      </el-collapse-item>
    </el-collapse>

    <el-empty v-if="!loading && grouped.length === 0" description="暂无酒款" />

    <el-dialog v-model="dialog" :title="editing ? '编辑酒款' : '新增酒款'" width="460px">
      <el-form label-width="80px">
        <el-form-item label="酒款编号"><el-input v-model="form.code" placeholder="如 W-1001" /></el-form-item>
        <el-form-item label="酒名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="产区">
          <el-select v-model="form.region"><el-option v-for="r in REGIONS" :key="r" :label="r" :value="r" /></el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type"><el-option v-for="t in TYPES" :key="t" :label="t" :value="t" /></el-select>
        </el-form-item>
        <el-form-item label="库存">
          <span class="stock-readonly">
            {{ editing ? all.find(w => w.id === editing)?.stock : 0 }} 瓶（随窖藏批次联动，不可直接修改）
          </span>
        </el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.price" :min="0" :precision="2" :step="10" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status"><el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; gap: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.groups { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 0 8px; }
.wine-list { list-style: none; margin: 0; padding: 0; }
.wine-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 8px; border-bottom: 1px dashed var(--line); gap: 12px;
}
.wine-item:last-child { border-bottom: none; }
.wine-name { display: flex; align-items: center; gap: 10px; }
.code { color: var(--muted); font-size: 12px; font-family: monospace; }
.wine-meta { margin-top: 4px; display: flex; align-items: center; gap: 14px; color: var(--muted); font-size: 12px; flex-wrap: wrap; }
.price { color: var(--primary); font-weight: 700; }
.stock-num { color: var(--ink); }
.stock-bad { color: #e44; }
.stock-from { color: var(--muted); }
.wine-ops { display: flex; gap: 8px; flex-shrink: 0; }
.stock-readonly { color: var(--muted); font-size: 13px; }
</style>
