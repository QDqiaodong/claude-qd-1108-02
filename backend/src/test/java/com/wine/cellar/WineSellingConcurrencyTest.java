package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.service.SaleOutService;
import com.wine.cellar.service.WineService;

/**
 * 停售/复售并发保护：
 *  - 重复提交停售只成功一次，状态只翻转一次；
 *  - 停售与出窖并发：二者互斥，要么出窖成功（随后停售因在途被阻断）、要么停售成功（出窖被拒），
 *    绝不能出现"已停售但还在出窖扣库存"或部分更新；
 *  - 重复提交复售只成功一次，可售资格只恢复一次。
 * 真实线程跑（不套外层事务），独立 context，结束销毁以免污染其他用例。
 */
@SpringBootTest
class WineSellingConcurrencyTest {

    @Autowired WineService wineService;
    @Autowired SaleOutService saleService;

    /** 并发两次停售（酒款2 无在途业务）：恰好一次成功，最终停售且只翻转一次 */
    @Test
    @DirtiesContext
    void duplicateStopOnlyOneSucceeds() throws Exception {
        runParallel(2, () -> wineService.stopSelling(2L));

        Wine w = wineService.get(2L);
        assertEquals("停售", w.status);
        assertEquals(false, w.saleEligible);
        assertEquals(0, w.stock);
    }

    /** 停售 与 出窖 并发：同一酒款行锁互斥，只产生一个完整结果，不允许部分更新 */
    @Test
    @DirtiesContext
    void stopSellingAndSaleOutAreMutuallyExclusive_noHalfResult() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stopOk = new AtomicInteger();
        AtomicInteger stopRejected = new AtomicInteger();
        AtomicInteger saleOk = new AtomicInteger();
        AtomicInteger saleRejected = new AtomicInteger();

        // 线程 A：停售酒款1（种子库存 80）
        pool.submit(() -> {
            try { start.await(); wineService.stopSelling(1L); stopOk.incrementAndGet(); }
            catch (Exception e) { stopRejected.incrementAndGet(); }
        });
        // 线程 B：同时出窖 5 瓶
        pool.submit(() -> {
            try {
                start.await();
                SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
                SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
                it.wineId = 1L; it.qty = 5;
                in.items = java.util.List.of(it);
                saleService.create(in);
                saleOk.incrementAndGet();
            } catch (Exception e) { saleRejected.incrementAndGet(); }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(1, stopOk.get() + stopRejected.get(), "停售请求必须有确定结果");
        assertEquals(1, saleOk.get() + saleRejected.get(), "出窖请求必须有确定结果");

        Wine w = wineService.get(1L);
        if (stopOk.get() == 1) {
            // 停售先得锁：出窖必须被拒，库存与批次完全未动，且酒款确实停售
            assertEquals(1, saleRejected.get(), "停售先成功时出窖必须被拒绝");
            assertEquals("停售", w.status);
            assertEquals(80, w.stock);
        } else {
            // 出窖先得锁：成交 5 瓶并产生在途单，随后停售必须被在途保护拦截，酒款仍在售
            assertEquals(1, saleOk.get(), "出窖先成功时应恰好成交一单");
            assertEquals(1, stopRejected.get(), "存在未完成出窖单时停售必须被阻断");
            assertEquals("在售", w.status);
            assertEquals(75, w.stock);
        }
        // 无论谁先，账实都必须一致（没有部分更新留下的偏差）
        assertEquals(w.stock, sumBatchAvailable(1L));
    }

    /** 并发两次复售（酒款3 种子停售、批次可售10、镜像一致）：恰好一次成功 */
    @Test
    @DirtiesContext
    void duplicateResumeOnlyOneSucceeds() throws Exception {
        runParallel(2, () -> wineService.resumeSelling(3L));

        Wine w = wineService.get(3L);
        assertEquals("在售", w.status);
        assertEquals(true, w.saleEligible);
        assertEquals(10, w.sellableBatchStock);
        assertEquals(10, w.stock);
    }

    /**
     * 停售 × 退窖 并发：锁序一致（先出窖单后酒款）不得死锁。
     * 先造一笔已出窖未退窖单，再同时「退窖该单」与「停售该酒款」：
     *  - 退窖必然成功（它始终有资格）；
     *  - 停售要么先抢到在途单锁 → 因在途被阻断（酒款仍在售），
     *    要么在退窖完成后执行 → 在途已清，停售成功；
     * 无论哪种，最终账实一致、无死锁、无部分更新。
     */
    @Test
    @DirtiesContext
    void stopSellingAndRefundDoNotDeadlock() throws Exception {
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = 1L; it.qty = 5;
        in.items = java.util.List.of(it);
        SaleOut sale = saleService.create(in);
        assertEquals(75, wineService.get(1L).stock);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stopOk = new AtomicInteger();
        AtomicInteger stopRejected = new AtomicInteger();
        AtomicInteger refundOk = new AtomicInteger();
        AtomicInteger refundRejected = new AtomicInteger();

        pool.submit(() -> {
            try { start.await(); wineService.stopSelling(1L); stopOk.incrementAndGet(); }
            catch (Exception e) { stopRejected.incrementAndGet(); }
        });
        pool.submit(() -> {
            try { start.await(); saleService.refund(sale.id); refundOk.incrementAndGet(); }
            catch (Exception e) { refundRejected.incrementAndGet(); }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "停售与退窖不得死锁，20 秒内必须全部结束");

        // 退窖一定成功且只一次；停售有确定结果（成功或被在途阻断）
        assertEquals(1, refundOk.get(), "退窖必须成功一次");
        assertEquals(0, refundRejected.get());
        assertEquals(1, stopOk.get() + stopRejected.get(), "停售请求必须有确定结果");

        // 最终：退窖已回补，库存 80、账实一致；停售成功者酒款停售，被阻断者仍在售
        Wine w = wineService.get(1L);
        assertEquals(80, w.stock);
        assertEquals(80, w.sellableBatchStock);
        if (stopOk.get() == 1) {
            assertEquals("停售", w.status);
        } else {
            assertEquals("在售", w.status);
        }
    }

    private int sumBatchAvailable(long wineId) {
        return wineService.get(wineId).sellableBatchStock;
    }

    private void runParallel(int threads, ThrowingRunnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    action.run();
                    ok.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(1, ok.get(), "恰好一个请求成功");
        assertEquals(threads - 1, rejected.get(), "其余重复/并发请求必须被拒绝");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
