package com.wine.cellar.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "wine")
public class Wine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "code", nullable = false, unique = true, length = 32)
    public String code;            // 酒款编号

    @Column(name = "name", nullable = false, length = 128)
    public String name;            // 酒名

    @Column(name = "region", nullable = false, length = 16)
    public String region;          // 产区: 国产/法国/智利/澳洲/其他

    @Column(name = "type", nullable = false, length = 16)
    public String type;            // 类型: 红/白/起泡/甜

    @Column(name = "stock", nullable = false)
    public Integer stock;          // 库存

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    public BigDecimal price;       // 单价（用于销售计价）

    @Column(name = "status", nullable = false, length = 16)
    public String status;          // 在售/停售

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 各「可售」批次可用瓶数合计（资格的唯一真实来源，由服务端按批次实时填充，非持久化列）。
     * 前台展示的可售量一律取此字段，保证酒款列表、批次页、销售出窖页看到同一结果。
     */
    @Transient
    public Integer sellableBatchStock;

    /**
     * 是否可进入前台销售选择：在售 且 至少存在一个仍有可用瓶数的可售批次。
     * 停售/全批次售罄/无放行批次均为 false（非持久化列，服务端统一计算）。
     */
    @Transient
    public Boolean saleEligible;
}
