package com.wine.cellar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wine.cellar.entity.SaleOut;

import jakarta.persistence.LockModeType;

public interface SaleOutRepository extends JpaRepository<SaleOut, Long> {

    List<SaleOut> findByStatus(String status);

    /** 行锁读取出窖单（退窖防重复提交） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SaleOut s where s.id = :id")
    Optional<SaleOut> findLockingById(@Param("id") Long id);

    /**
     * 行锁按 id 升序锁定一批出窖单（停售保护复查在途业务时使用）。
     * 锁序「先出窖单（id 升序）后酒款」与退窖一致，杜绝停售 × 退窖交叉死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SaleOut s where s.id in :ids order by s.id asc")
    List<SaleOut> findLockingAllById(@Param("ids") List<Long> ids);
}
