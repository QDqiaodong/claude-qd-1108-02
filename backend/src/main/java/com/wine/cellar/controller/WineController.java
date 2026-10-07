package com.wine.cellar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wine.cellar.common.ApiResponse;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.service.WineService;

@RestController
@RequestMapping("/api/wines")
public class WineController {

    private final WineService wineService;

    public WineController(WineService wineService) {
        this.wineService = wineService;
    }

    @GetMapping
    public ApiResponse<List<Wine>> list(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(wineService.list(region, type, status));
    }

    @PostMapping
    public ApiResponse<Wine> create(@RequestBody WineService.WineInput input) {
        return ApiResponse.success(wineService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Wine> update(@PathVariable Long id, @RequestBody WineService.WineInput input) {
        return ApiResponse.success(wineService.update(id, input));
    }

    /** 库存校准：按各可售批次可用瓶数重算酒款库存（批次/酒款不一致时的恢复入口） */
    @PostMapping("/{id}/reconcile-stock")
    public ApiResponse<Wine> reconcileStock(@PathVariable Long id) {
        return ApiResponse.success(wineService.reconcileStock(id));
    }
}
