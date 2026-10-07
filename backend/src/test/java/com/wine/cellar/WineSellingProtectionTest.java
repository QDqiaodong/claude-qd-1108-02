package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.WineRepository;
import com.wine.cellar.service.CellarBatchService;
import com.wine.cellar.service.SaleOutService;
import com.wine.cellar.service.WineService;

/**
 * 酒款停售与复售资格保护：在途业务阻断、复售资格复核（可售批次/库存镜像/未完成冲正）、
 * 重复提交与非法跳跃拒绝、资格口径三端同源、PUT 不可绕改状态。
 *
 * 种子：酒款1 在售库存80（B-T-01 可售50 + B-T-02 可售30）；
 *      酒款2 在售库存0（B-T-03 窖藏中40）；酒款3 停售库存10（B-T-04 可售10）。
 */
@SpringBootTest
@Transactional
class WineSellingProtectionTest {

    @Autowired WineService wineService;
    @Autowired SaleOutService saleService;
    @Autowired CellarBatchService batchService;
    @Autowired WineRepository wineRepository;
    @Autowired CellarBatchRepository batchRepository;

    // ---------------- 资格口径：对外返回统一字段 ----------------
    @Test
    void qualificationFieldsAreFilledByBackend() {
        Wine w1 = wineService.get(1L);
        assertEquals("在售", w1.status);
        assertEquals(80, w1.sellableBatchStock);
        assertTrue(w1.saleEligible);

        // 停售酒款：即使批次可售有瓶数，也不具备销售选择资格
        Wine w3 = wineService.get(3L);
        assertEquals("停售", w3.status);
        assertEquals(10, w3.sellableBatchStock);
        assertFalse(w3.saleEligible);

        // 在售但只有窖藏中批次：可售量 0，不可销售选择
        Wine w2 = wineService.get(2L);
        assertEquals(0, w2.sellableBatchStock);
        assertFalse(w2.saleEligible);
    }

    // ---------------- 无在途业务：停售成功且资格同步关闭 ----------------
    @Test
    void stopSellingSucceedsAndClosesSaleQualificationAtomically() {
        Wine stopped = wineService.stopSelling(2L);
        assertEquals("停售", stopped.status);
        assertEquals(0, stopped.sellableBatchStock);
        assertFalse(stopped.saleEligible);

        // 列表口径同样关闭（酒款列表 / 批次页 / 销售页消费的同一字段）
        Wine listed = wineService.list(null, null, null).stream().filter(w -> w.id == 2L).findFirst().orElseThrow();
        assertFalse(listed.saleEligible);

        // 批次台账保留且状态不被破坏（停售不动批次）
        assertEquals("窖藏中", batchService.get(3L).status);
    }

    // ---------------- 停售保护：存在未完成出窖（待处理退窖明细）必须阻断 ----------------
    @Test
    void stopSellingBlockedByOpenSale_keepsOriginalState_withReasonAndEntry() {
        // 酒款1 出窖 5 瓶 → 产生已出窖未退窖的在途单及其批次扣减明细
        SaleOut sale = sell(1L, 5);
        assertNotNull(sale.id);

        BizException ex = assertThrows(BizException.class, () -> wineService.stopSelling(1L));
        assertEquals(BizException.CODE_ACTIVE_SALE_BLOCKED, ex.getCode());
        assertEquals("sale-outs", ex.getAction());
        assertTrue(ex.getMessage().contains("销售出窖"), "必须提示可继续处理的入口：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("退窖"), "必须说明阻断原因是待处理退窖明细：" + ex.getMessage());

        // 原状态保持在售，库存/批次未动，没有半成功结果
        Wine w = wineService.get(1L);
        assertEquals("在售", w.status);
        assertTrue(w.saleEligible);
        assertEquals(75, w.stock);

        // 处理完在途（退窖回补）后，停售才放行
        saleService.refund(sale.id);
        Wine stopped = wineService.stopSelling(1L);
        assertEquals("停售", stopped.status);
        assertFalse(stopped.saleEligible);
        assertEquals(80, wineService.get(1L).stock);   // 退窖回补后库存完整
    }

    // ---------------- 停售后不可再出窖（前台/后台同一资格） ----------------
    @Test
    void stoppedWineCannotBeSold() {
        wineService.stopSelling(2L);
        BizException ex = assertThrows(BizException.class, () -> sell(2L, 1));
        assertTrue(ex.getMessage().contains("停售"));
        assertEquals("停售", wineService.get(2L).status);
    }

    // ---------------- 重复停售被拒 ----------------
    @Test
    void duplicateStopSellingRejected() {
        wineService.stopSelling(2L);
        BizException ex = assertThrows(BizException.class, () -> wineService.stopSelling(2L));
        assertTrue(ex.getMessage().contains("已停售"));
        assertEquals("停售", wineService.get(2L).status);
    }

    // ---------------- 复售：已在售拒绝重复提交 ----------------
    @Test
    void resumeSellingRejectedWhenAlreadyOnSale() {
        BizException ex = assertThrows(BizException.class, () -> wineService.resumeSelling(1L));
        assertTrue(ex.getMessage().contains("已在售"));
        assertEquals("在售", wineService.get(1L).status);
    }

    // ---------------- 复售成功：批次/库存/状态一致才恢复，且可售量=批次合计 ----------------
    @Test
    void resumeSellingSucceedsAndRestoresQualification() {
        // 种子酒款3：停售，B-T-04 可售10、库存镜像10、批次自洽 → 可复售
        Wine resumed = wineService.resumeSelling(3L);
        assertEquals("在售", resumed.status);
        assertEquals(10, resumed.sellableBatchStock);
        assertTrue(resumed.saleEligible);
        // 前台展示可售量与批次合计一致
        assertEquals(10, batchService.sumAvailable(3L));
        assertEquals(resumed.sellableBatchStock, batchService.sumAvailable(3L));
    }

    // ---------------- 复售拒绝：批次已全部售罄 ----------------
    @Test
    void resumeSellingRejectedWhenAllBatchesSoldOut() {
        // 先让酒款1 无在途并停售，再把两个批次置为已售罄（库存镜像同步为 0）
        wineService.stopSelling(1L);
        forceBatch(1L, 50, "已售罄");
        forceBatch(2L, 30, "已售罄");
        Wine w = wineService.getLocked(1L);
        w.stock = 0;
        wineRepository.save(w);

        BizException ex = assertThrows(BizException.class, () -> wineService.resumeSelling(1L));
        assertEquals(BizException.CODE_RESALE_NOT_QUALIFIED, ex.getCode());
        assertEquals("cellar-batches", ex.getAction());
        assertTrue(ex.getMessage().contains("售罄"), "应说明批次已全部售罄：" + ex.getMessage());
        // 原停售状态不变
        assertEquals("停售", wineService.get(1L).status);
    }

    // ---------------- 复售拒绝：库存镜像不一致 → 先校准 ----------------
    @Test
    void resumeSellingRejectedWhenStockInconsistent_thenRecoverByReconcile() {
        // 种子酒款3：批次合计 10，把账面改成 9 制造不一致
        Wine w = wineService.getLocked(3L);
        w.stock = 9;
        wineRepository.save(w);

        BizException ex = assertThrows(BizException.class, () -> wineService.resumeSelling(3L));
        assertEquals(BizException.CODE_RESALE_NOT_QUALIFIED, ex.getCode());
        assertEquals("wines", ex.getAction());
        assertTrue(ex.getMessage().contains("不一致"));
        assertTrue(ex.getMessage().contains("校准"));
        assertEquals("停售", wineService.get(3L).status);

        // 台账校准恢复后复售成功
        wineService.reconcileStock(3L);
        Wine resumed = wineService.resumeSelling(3L);
        assertEquals("在售", resumed.status);
        assertTrue(resumed.saleEligible);
    }

    // ---------------- 复售拒绝：存在未完成冲正（批次状态/扣账不自洽） ----------------
    @Test
    void resumeSellingRejectedWhenUnfinishedReversalExists() {
        // 种子酒款3：B-T-04 总10，制造已出窖 15（扣减过头/回补为负同类）→ 冲正未完成
        CellarBatch b = batchService.get(4L);
        b.soldCount = 15;
        batchRepository.save(b);

        BizException ex = assertThrows(BizException.class, () -> wineService.resumeSelling(3L));
        assertEquals(BizException.CODE_RESALE_NOT_QUALIFIED, ex.getCode());
        assertEquals("sale-outs", ex.getAction());
        assertTrue(ex.getMessage().contains("冲正"), "应说明存在未完成冲正：" + ex.getMessage());
        assertEquals("停售", wineService.get(3L).status);
    }

    @Test
    void cellaringBatchChargedBlocksResumeAfterStop() {
        CellarBatch b = batchService.get(3L);
        b.soldCount = 3;
        batchRepository.save(b);
        Wine w = wineService.getLocked(2L);
        w.stock = 0;   // 镜像保持 0（窖藏中不计库存），仅冲正异常
        wineRepository.save(w);

        wineService.stopSelling(2L);
        BizException ex = assertThrows(BizException.class, () -> wineService.resumeSelling(2L));
        assertEquals(BizException.CODE_RESALE_NOT_QUALIFIED, ex.getCode());
        assertTrue(ex.getMessage().contains("冲正"));
        assertEquals("停售", wineService.get(2L).status);
    }

    // ---------------- PUT 编辑不能绕过保护改状态（防非法跳跃/半成功） ----------------
    @Test
    void updateCannotChangeSellingStatus() {
        WineService.WineInput in = new WineService.WineInput();
        in.status = "停售";
        BizException ex = assertThrows(BizException.class, () -> wineService.update(1L, in));
        assertTrue(ex.getMessage().contains("停售") || ex.getMessage().contains("动作"));
        assertEquals("在售", wineService.get(1L).status);

        // 非法状态值依旧拒绝
        WineService.WineInput bad = new WineService.WineInput();
        bad.status = "下架中";
        assertThrows(BizException.class, () -> wineService.update(1L, bad));
    }

    // ---------------- 新建不允许直接停售（必须走带保护的动作） ----------------
    @Test
    void createCannotStartOffShelf() {
        WineService.WineInput in = new WineService.WineInput();
        in.code = "W-NEW-STOP";
        in.name = "直接停售酒";
        in.region = "国产";
        in.type = "红";
        in.price = new BigDecimal("88.00");
        in.stock = 0;
        in.status = "停售";
        BizException ex = assertThrows(BizException.class, () -> wineService.create(in));
        assertTrue(ex.getMessage().contains("在售"));
    }

    // ---------------- 全闭环：停售备货后复售，销售选择随资格恢复 ----------------
    @Test
    void stopThenReleaseCellaringBatchThenResume_restoresSaleWithBatchTotal() {
        // 酒款2 在售但无可售批次（库存0），停售 → 放行窖藏批次备货 → 复售
        wineService.stopSelling(2L);
        assertFalse(wineService.get(2L).saleEligible);

        CellarBatch released = batchService.release(3L);   // 停售期间允许放行备货
        assertEquals("可售", released.status);
        assertEquals(40, wineService.get(2L).stock);

        Wine resumed = wineService.resumeSelling(2L);
        assertEquals("在售", resumed.status);
        assertEquals(40, resumed.sellableBatchStock);
        assertTrue(resumed.saleEligible);

        // 恢复后可正常出窖，且按批次扣减
        SaleOut sale = sell(2L, 10);
        assertNotNull(sale.id);
        assertEquals(30, wineService.get(2L).stock);
    }

    private void forceBatch(long batchId, int sold, String status) {
        CellarBatch b = batchService.get(batchId);
        b.soldCount = sold;
        b.status = status;
        batchRepository.save(b);
    }

    private SaleOut sell(long wineId, int qty) {
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = wineId;
        it.qty = qty;
        in.items = java.util.List.of(it);
        return saleService.create(in);
    }
}
