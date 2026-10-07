package com.wine.cellar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wine.cellar.entity.SaleOutItem;

public interface SaleOutItemRepository extends JpaRepository<SaleOutItem, Long> {

    List<SaleOutItem> findBySaleId(Long saleId);
}
