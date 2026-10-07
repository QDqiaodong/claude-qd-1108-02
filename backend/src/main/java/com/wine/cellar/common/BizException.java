package com.wine.cellar.common;

/**
 * 业务异常：由业务规则主动抛出，统一异常处理会转成 {ok:false, message:"..."}。
 * code   —— 机器可读的阻断类型（如 WINE_ACTIVE_SALE_BLOCKED / WINE_RESALE_*），前端可据此决定交互；
 * action —— 可继续处理的入口标识（前端路由名：sale-outs / wines / cellar-batches），可空。
 */
public class BizException extends RuntimeException {

    /** 阻断类型：存在依赖该酒款的未完成出窖单（待退窖明细），停售被保护拦截 */
    public static final String CODE_ACTIVE_SALE_BLOCKED = "WINE_ACTIVE_SALE_BLOCKED";
    /** 阻断类型：复售资格复核未通过（批次售罄/库存不一致/存在未完成冲正） */
    public static final String CODE_RESALE_NOT_QUALIFIED = "WINE_RESALE_NOT_QUALIFIED";

    private final String code;
    private final String action;

    public BizException(String message) {
        this(message, null, null);
    }

    public BizException(String message, String code, String action) {
        super(message);
        this.code = code;
        this.action = action;
    }

    public String getCode() {
        return code;
    }

    public String getAction() {
        return action;
    }
}
