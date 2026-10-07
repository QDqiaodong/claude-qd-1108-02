package com.wine.cellar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.SaleOutRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class WineService {

    private final WineRepository wineRepository;
    private final CellarBatchRepository batchRepository;
    private final SaleOutRepository saleRepository;
    private final SaleOutItemRepository itemRepository;

    public WineService(WineRepository wineRepository,
                       CellarBatchRepository batchRepository,
                       SaleOutRepository saleRepository,
                       SaleOutItemRepository itemRepository) {
        this.wineRepository = wineRepository;
        this.batchRepository = batchRepository;
        this.saleRepository = saleRepository;
        this.itemRepository = itemRepository;
    }

    // ---------------- 查询（统一回填可售资格：酒款列表 / 批次页 / 销售页同源） ----------------
    public List<Wine> list(String region, String type, String status) {
        List<Wine> all = wineRepository.findAll();
        if (region != null && !region.isBlank()) {
            all = all.stream().filter(w -> region.equals(w.region)).toList();
        }
        if (type != null && !type.isBlank()) {
            all = all.stream().filter(w -> type.equals(w.type)).toList();
        }
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(w -> status.equals(w.status)).toList();
        }
        return withQualification(all);
    }

    public Wine get(Long id) {
        return withQualification(wineRepository.findById(id)
                .orElseThrow(() -> new BizException("酒款不存在")));
    }

    /** 行锁读取酒款（库存联动时使用），调用方须在事务内 */
    public Wine getLocked(Long id) {
        return wineRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("酒款不存在"));
    }

    /**
     * 可售资格唯一口径：可售批次可用瓶数合计 + 是否可进入前台销售（在售且合计>0）。
     * 所有对外返回酒款的出口都经此填充，前端各页面不允许各算各的，避免出现"后台停售但前台仍可选购"
     * 或"只改状态却留下可售资格"的不一致观感。
     */
    private List<Wine> withQualification(List<Wine> wines) {
        if (wines.isEmpty()) {
            return wines;
        }
        // 一次拉全量批次再按酒款分组（系统规模为演示级台账），避免 N+1
        Map<Long, int[]> sums = new LinkedHashMap<>();   // wineId -> [可售批次可用合计]
        for (CellarBatch b : batchRepository.findAll()) {
            if (Dict.BATCH_SELLABLE.equals(b.status)) {
                sums.computeIfAbsent(b.wineId, k -> new int[1])[0] += b.getAvailableBottles();
            }
        }
        for (Wine w : wines) {
            fillQualification(w, sums.getOrDefault(w.id, new int[1])[0]);
        }
        return wines;
    }

    private Wine withQualification(Wine w) {
        int sum = 0;
        for (CellarBatch b : batchRepository.findByWineIdOrderByCellarDateAscIdAsc(w.id)) {
            if (Dict.BATCH_SELLABLE.equals(b.status)) {
                sum += b.getAvailableBottles();
            }
        }
        return fillQualification(w, sum);
    }

    private Wine fillQualification(Wine w, int sellableBatchStock) {
        w.sellableBatchStock = sellableBatchStock;
        w.saleEligible = Dict.WINE_ON_SALE.equals(w.status) && sellableBatchStock > 0;
        return w;
    }

    // ---------------- 新建 ----------------
    public static class WineInput {
        public String code;
        public String name;
        public String region;
        public String type;
        public Integer stock;
        public BigDecimal price;
        public String status;
    }

    public Wine create(WineInput input) {
        if (input.code == null || input.code.isBlank()) {
            throw new BizException("酒款编号不能为空");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("酒名不能为空");
        }
        if (!Dict.isValidWineRegion(input.region)) {
            throw new BizException("产区必须是：国产/法国/智利/澳洲/其他 之一");
        }
        if (!Dict.isValidWineType(input.type)) {
            throw new BizException("类型必须是：红/白/起泡/甜 之一");
        }
        // 库存由窖藏批次联动：新酒款一律 0，放行批次后才有可售库存
        if (input.stock != null && input.stock != 0) {
            throw new BizException("酒款库存不能直接录入，请先新建窖藏批次并放行，库存将随批次自动增加");
        }
        if (input.price == null || input.price.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不能为负");
        }
        if (wineRepository.existsByCode(input.code)) {
            throw new BizException("酒款编号已存在，不可重复");
        }

        Wine w = new Wine();
        w.code = input.code;
        w.name = input.name;
        w.region = input.region;
        w.type = input.type;
        w.stock = 0;
        w.price = input.price;
        // 新建只允许从「在售」起步；要停售的酒先走在售再走停售动作（带在途保护）
        String status = (input.status == null || input.status.isBlank()) ? Dict.WINE_ON_SALE : input.status;
        if (!Dict.WINE_ON_SALE.equals(status)) {
            throw new BizException("新酒款只能以「在售」建立，需要停售请在台账使用「停售」动作（含在途业务保护）");
        }
        w.status = status;
        return withQualification(wineRepository.save(w));
    }

    // ---------------- 更新（部分字段） ----------------
    public Wine update(Long id, WineInput input) {
        Wine w = get(id);
        if (input.code != null) {
            if (input.code.isBlank()) {
                throw new BizException("酒款编号不能为空");
            }
            if (!input.code.equals(w.code) && wineRepository.existsByCodeAndIdNot(input.code, id)) {
                throw new BizException("酒款编号已存在，不可重复");
            }
            w.code = input.code;
        }
        if (input.name != null) {
            if (input.name.isBlank()) {
                throw new BizException("酒名不能为空");
            }
            w.name = input.name;
        }
        if (input.region != null) {
            if (!Dict.isValidWineRegion(input.region)) {
                throw new BizException("产区必须是：国产/法国/智利/澳洲/其他 之一");
            }
            w.region = input.region;
        }
        if (input.type != null) {
            if (!Dict.isValidWineType(input.type)) {
                throw new BizException("类型必须是：红/白/起泡/甜 之一");
            }
            w.type = input.type;
        }
        if (input.stock != null && input.stock != w.stock) {
            // 不允许绕过批次规则直接改酒款库存
            throw new BizException(
                    "酒款库存与窖藏批次联动，不能直接修改；如数据不一致，请使用「按批次校准库存」（当前账面 "
                            + w.stock + "，提交值 " + input.stock + "）");
        }
        if (input.price != null) {
            if (input.price.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("单价不能为负");
            }
            w.price = input.price;
        }
        // 在售/停售只能通过动作接口（停售 / 复售）切换：PUT 一律拒绝，
        // 防止绕过在途业务保护与复售资格复核造成半成功状态与非法跳跃。
        if (input.status != null && !input.status.isBlank() && !input.status.equals(w.status)) {
            throw new BizException("酒款在售/停售不能在编辑中直接改写，请使用「"
                    + (Dict.WINE_ON_SALE.equals(w.status) ? "停售" : "复售")
                    + "」动作按钮（含在途业务与可售资格保护）");
        }
        return withQualification(wineRepository.save(w));
    }

    // ---------------- 库存校准：按各可售批次可用瓶数重算（数据不一致时的恢复入口） ----------------
    @Transactional
    public Wine reconcileStock(Long id) {
        Wine w = getLocked(id);
        int expected = batchRepository.findForUpdateByWineIdAndStatus(w.id, Dict.BATCH_SELLABLE).stream()
                .mapToInt(b -> b.bottleCount - b.soldCount)
                .sum();
        if (w.stock == expected) {
            throw new BizException("酒款「" + w.name + "」库存与批次可用瓶数一致（均为 " + expected + "），无需校准");
        }
        w.stock = expected;
        return withQualification(wineRepository.save(w));
    }

    // =====================================================================
    // 酒款停售与复售资格保护
    //
    // 资格口径（全站唯一，见 fillQualification）：
    //   可售量 = 各「可售」批次可用瓶数合计；可销售选择 = 酒款在售 且 可售量 > 0。
    // 停售只翻转酒款状态这一资格总开关：销售入口读取 saleEligible、批次页展示按酒款状态联动，
    // 批次本身（窖藏中/可售/已售罄）不动——复售时要重新核对它们，停售不应破坏批次台账。
    // 因此"前台销售入口 + 酒款可售标记 + 批次可售展示"在本事务提交的同一刻一起生效，
    // 不存在只改了一半的中间状态；动作失败则整事务回滚，原状态保持不变。
    // =====================================================================

    /**
     * 停售（在售 → 停售）：先做在途业务保护。
     * 只要存在依赖该酒款的未完成出窖单（已出窖未退窖，其批次扣减明细即待处理退窖明细），
     * 就拒绝停售、保持在售，并给出阻断原因与可继续处理的入口（销售出窖页完成退窖）。
     */
    @Transactional
    public Wine stopSelling(Long id) {
        // 加锁顺序与退窖保持一致：先按 id 升序锁定在途出窖单，再锁酒款，杜绝停售×退窖交叉死锁。
        // 先普通读找出候选在途单（未加锁，不阻塞），再对这些单行锁 + 锁后二次复查，
        // 保证拿到的是最新已提交状态（不依赖事务快照），与并发出窖/退窖严格互斥。
        List<Long> openSaleIds = itemRepository.findOpenSaleIdsByWineId(id, "已出窖");
        if (!openSaleIds.isEmpty()) {
            saleRepository.findLockingAllById(openSaleIds.stream().sorted().toList());
            // 锁后复查：候选单可能恰好在等待期间被退窖完成（状态变为已退窖）
            List<Long> openItemIds = itemRepository.findOpenItemIdsByWineId(id, "已出窖");
            if (!openItemIds.isEmpty()) {
                throwActiveBusinessBlocked(id, openItemIds.size());
            }
        }

        Wine w = getLocked(id);
        if (Dict.WINE_OFF_SHELF.equals(w.status)) {
            throw new BizException("酒款「" + w.name + "」当前已停售，请勿重复提交停售；如需恢复销售请使用「复售」");
        }
        if (!Dict.WINE_ON_SALE.equals(w.status)) {
            throw new BizException("酒款「" + w.name + "」状态为「" + w.status + "」，无法停售（非法状态跳跃）");
        }

        // 拿酒款锁后再复查一次在途业务：覆盖"先普通查为空、随后并发出窖插入新在途单"的交错，
        // 此时该出窖事务正持酒款行锁未提交，本事务在上面的 getLocked 处等待；其提交后本复查必能看到。
        List<Long> openItemIds = itemRepository.findOpenItemIdsByWineId(id, "已出窖");
        if (!openItemIds.isEmpty()) {
            throwActiveBusinessBlocked(id, openItemIds.size());
        }

        w.status = Dict.WINE_OFF_SHELF;
        // 单事务内仅翻转资格总开关：提交后前台销售选择立刻消失、批次可售展示同步标注停售、
        // 酒款可售标记变为不可售，三者同生共死，不会留下任何半成功资格。
        return withQualification(wineRepository.save(w));
    }

    private void throwActiveBusinessBlocked(Long wineId, int itemCount) {
        Wine name = wineRepository.findById(wineId).orElseThrow(() -> new BizException("酒款不存在"));
        throw new BizException(
                "酒款「" + name.name + "」存在 " + itemCount
                        + " 条未完成业务（已出窖未退窖的批次扣减明细，即待处理退窖明细），"
                        + "停售会阻断其退窖回补，已拒绝本次停售并保持「在售」。"
                        + "请先到「销售出窖」对相关出窖单完成退窖（或确认无需处理）后再停售。",
                BizException.CODE_ACTIVE_SALE_BLOCKED, "sale-outs");
    }

    /**
     * 复售（停售 → 在售）：不能只把状态改回在售，必须重新核对可售资格。
     * 任一不满足即拒绝、保持停售，并说明需要先处理的事项与入口：
     *   1) 至少存在一个「可售」且仍有可用瓶数的批次（批次已全部售罄 → 先放行窖藏批次）；
     *   2) 库存镜像与批次可用合计一致（不一致 → 先在台账校准）；
     *   3) 批次状态与扣账自洽（存在未完成冲正：可售/已售罄批次已扣为负、窖藏中批次已被扣减等 → 先处理冲正）。
     */
    @Transactional
    public Wine resumeSelling(Long id) {
        Wine w = getLocked(id);
        if (Dict.WINE_ON_SALE.equals(w.status)) {
            throw new BizException("酒款「" + w.name + "」当前已在售，请勿重复提交复售");
        }
        if (!Dict.WINE_OFF_SHELF.equals(w.status)) {
            throw new BizException("酒款「" + w.name + "」状态为「" + w.status + "」，无法复售（非法状态跳跃）");
        }

        // 行锁锁定该酒款全部批次后再核对（与出窖/退窖一致的 wine → batches 锁序，
        // 并发放行/出窖/退窖在此互斥，保证复核结果与最终状态是同一个完整结果）
        List<CellarBatch> batches = batchRepository.findAllForUpdateByWineId(id);

        // 1) 未完成冲正 / 批次状态不自洽：任何与状态机不符的扣账都视为冲正未完成，拒绝复售
        List<String> broken = new ArrayList<>();
        int sellableAvailable = 0;
        boolean anySellable = false;
        for (CellarBatch b : batches) {
            Integer bottleCount = b.bottleCount == null ? 0 : b.bottleCount;
            Integer soldCount = b.soldCount == null ? 0 : b.soldCount;
            String detail = "批次「" + b.batchNo + "」（" + b.status + "，总"
                    + bottleCount + "/已出窖" + soldCount + "）";
            if (bottleCount <= 0) {
                broken.add(detail + " 总瓶数必须大于 0");
            }
            if (soldCount < 0 || soldCount > bottleCount) {
                // 已出窖数为负（回补过头）或超过总瓶数（扣减过头）——典型的冲正未完成
                broken.add(detail + " 已出窖瓶数超出合法范围，疑似存在未完成冲正");
            }
            if (Dict.BATCH_CELLARING.equals(b.status)) {
                // 窖藏中批次不应有任何出窖扣减；有则说明放行/冲正流程未走完
                if (soldCount != 0) {
                    broken.add(detail + " 窖藏中批次不应已扣瓶数，疑似存在未完成冲正");
                }
            } else if (Dict.BATCH_SELLABLE.equals(b.status)) {
                anySellable = true;
                sellableAvailable += bottleCount - soldCount;
                if (bottleCount - soldCount <= 0) {
                    // 状态还挂「可售」却已无可用瓶数，按状态机应已自动转为已售罄——状态与扣账不一致
                    broken.add(detail + " 已无可用瓶数却仍为「可售」，批次状态与扣账不一致，疑似存在未完成冲正");
                }
            } else if (Dict.BATCH_SOLD_OUT.equals(b.status)) {
                if (bottleCount - soldCount != 0) {
                    // 已售罄却还有可用瓶数，说明退窖回补后状态没恢复——冲正未完成
                    broken.add(detail + " 标记已售罄却仍有可用瓶数，疑似退窖冲正未完成");
                }
            } else {
                broken.add(detail + " 批次状态非法");
            }
        }
        if (!broken.isEmpty()) {
            throw new BizException(
                    "酒款「" + w.name + "」存在未完成冲正或批次状态不自洽，复售已拒绝并保持「停售」，请先处理：\n- "
                            + String.join("\n- ", broken)
                            + "\n处理入口：「销售出窖」核对/完成退窖冲正后再复售。",
                    BizException.CODE_RESALE_NOT_QUALIFIED, "sale-outs");
        }

        // 2) 至少一个仍有可用瓶数的可售批次（全部售罄 / 只有窖藏中批次 → 先放行）
        if (!anySellable || sellableAvailable <= 0) {
            throw new BizException(
                    "酒款「" + w.name + "」没有任何仍有可用瓶数的可售批次（批次已全部售罄或仅有窖藏中批次），"
                            + "复售已拒绝并保持「停售」。请先到「窖藏批次」放行窖藏批次（或等待退窖回补）后再复售。",
                    BizException.CODE_RESALE_NOT_QUALIFIED, "cellar-batches");
        }

        // 3) 库存镜像必须与各可售批次可用瓶数合计一致，否则前台可售量与批次合计对不上
        if (w.stock == null || w.stock != sellableAvailable) {
            throw new BizException(
                    "酒款「" + w.name + "」账面库存（" + w.stock + "）与各可售批次可用瓶数合计（"
                            + sellableAvailable + "）不一致，复售已拒绝并保持「停售」。"
                            + "请先在「酒款台账」执行「按批次校准库存」后再复售。",
                    BizException.CODE_RESALE_NOT_QUALIFIED, "wines");
        }

        // 全部核对通过才恢复销售：状态翻转后 saleEligible 同事务恢复为 true，
        // 前台销售选择恢复且展示可售量 = 批次合计（同一口径字段）。
        w.status = Dict.WINE_ON_SALE;
        return withQualification(wineRepository.save(w));
    }
}
