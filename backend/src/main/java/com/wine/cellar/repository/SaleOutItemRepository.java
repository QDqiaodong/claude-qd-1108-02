package com.wine.cellar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wine.cellar.entity.SaleOutItem;

public interface SaleOutItemRepository extends JpaRepository<SaleOutItem, Long> {

    List<SaleOutItem> findBySaleId(Long saleId);

    /**
     * 依赖指定酒款、且所在出窖单仍为「已出窖」（未退窖）的批次扣减明细 id。
     * 停售保护据此识别"待处理退窖明细 / 未完成出窖业务"：明细存在即说明该酒款仍被在途销售占用。
     */
    @Query("select i.id from SaleOutItem i, SaleOut s "
            + "where i.saleId = s.id and i.wineId = :wineId and s.status = :status")
    List<Long> findOpenItemIdsByWineId(@Param("wineId") Long wineId,
                                       @Param("status") String status);

    /**
     * 依赖指定酒款、且整单仍为「已出窖」的出窖单 id（去重）。
     * 停售保护据此对在途单加行锁做当前读复查，保证与退窖/新建出窖并发时阻断结论不依赖事务快照。
     */
    @Query("select distinct i.saleId from SaleOutItem i, SaleOut s "
            + "where i.saleId = s.id and i.wineId = :wineId and s.status = :status")
    List<Long> findOpenSaleIdsByWineId(@Param("wineId") Long wineId,
                                       @Param("status") String status);
}
